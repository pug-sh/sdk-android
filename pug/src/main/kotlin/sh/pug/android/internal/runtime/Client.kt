package sh.pug.android.internal.runtime

import android.os.Build
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import sh.pug.android.Events
import sh.pug.android.MemoryStorage
import sh.pug.android.Options
import sh.pug.android.Storage
import sh.pug.android.TrackOptions
import sh.pug.android.TrackingConsent
import sh.pug.android.internal.Clock
import sh.pug.android.internal.IdGenerator
import sh.pug.android.internal.UuidV7Generator
import sh.pug.android.internal.consent.TrackingConsentController
import sh.pug.android.internal.event.EventFactory
import sh.pug.android.internal.event.EventSchema
import sh.pug.android.internal.event.FieldType
import sh.pug.android.internal.event.MAX_STRING_BYTES
import sh.pug.android.internal.event.PropertyMapper
import sh.pug.android.internal.event.TraitsMapper
import sh.pug.android.internal.event.WellKnownEvents
import sh.pug.android.internal.event.durationText
import sh.pug.android.internal.identity.Identity
import sh.pug.android.internal.identity.RESERVED_ID_PREFIX
import sh.pug.android.internal.lifecycle.InstallTracker
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.platform.AutoProperties
import sh.pug.android.internal.platform.LifecycleObserver
import sh.pug.android.internal.platform.Platform
import sh.pug.android.internal.queue.EventQueue
import sh.pug.android.internal.storage.FileStorage
import sh.pug.android.internal.storage.SafeStorage
import sh.pug.android.internal.storage.StorageKeys
import sh.pug.android.internal.transport.Transport
import sh.pug.android.internal.transport.TransportException
import sh.pug.android.internal.truncateUtf8
import sh.pug.android.internal.wire.IdentifyRequest
import sh.pug.android.internal.wire.JsonValue
import sh.pug.android.internal.wire.PropertyValue

// Bounds destroy()'s final flush, which the next init() waits for.
internal const val TEARDOWN_TIMEOUT_MS: Long = 10_000L

/**
 * One configured SDK instance. Calls, and the app's lifecycle callbacks, arrive on any thread: they
 * read the clock and map their arguments there, then queue an op that the SDK worker runs in call
 * order. The components holding state are built on the worker once the previous client's teardown
 * is over, and live there.
 */
internal class Client(
    private val projectId: String,
    private val options: Options,
    private val transport: Transport,
    worker: CoroutineDispatcher,
    private val platform: Platform,
    private val clock: Clock = Clock.SYSTEM,
    private val ids: IdGenerator = UuidV7Generator(clock),
    private val random: Random = Random.Default,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    val logger: SafeLogger = options.newLogger()

    private val root = SupervisorJob()
    private val scope =
        CoroutineScope(root + worker + CoroutineExceptionHandler { _, t -> logger.error("Pug failed unexpectedly", t) })

    // Sends and identify calls: the work destroy() waits for.
    private val network = SupervisorJob(root)
    private val networkScope = CoroutineScope(scope.coroutineContext + network)
    private val ops = Channel<Op>(Channel.UNLIMITED)

    @Volatile private var lifetime: Job? = null

    @Volatile private var stopLifecycle: (() -> Unit)? = null

    private val propertyMapper = PropertyMapper(logger, sdkInt)
    private val traitsMapper = TraitsMapper(logger, sdkInt)
    private val factory = EventFactory(projectId, logger, propertyMapper, ids)

    // The consent getters answer on the caller's thread without waiting, each from one read of
    // consentView, so no answer mixes two moments: the latest opt-in or opt-out, else what start-up
    // read, else null for unknown. whenConsentReady() callbacks wait for start-up instead, in
    // consentWaiters until it has read consent.
    @Volatile private var consentView: ConsentView? = null
    private val consentLock = Any()

    @Volatile private var consentRead = false
    private val consentWaiters = ArrayList<Runnable>()

    @Volatile private var consentUnknownReported = false

    // Worker-confined, from restore() on.
    private lateinit var consent: TrackingConsentController
    private lateinit var identity: Identity
    private lateinit var dispatcher: EventDispatcher
    private lateinit var installs: InstallTracker
    private lateinit var deviceProperties: AutoProperties
    private var fileStorage: FileStorage? = null
    private var disposed = false
    private var identifyCalls = 0
    private var linkedIdentifyCall = 0

    // The app as the worker sees it: whether it is on screen, and whether this visit's app_open was
    // captured, which app_close must follow (Flutter's captured-or-not contract).
    private var visible = false
    private var opened = false

    // What `$url` and `$referrer` report.
    private var screen: String? = null
    private var screenClass: String? = null
    private var previousScreen: String? = null

    private class Op(val label: String, val block: () -> Unit)

    private class ConsentView(val consent: TrackingConsent, val isPending: Boolean)

    /**
     * Runs the client once [previousTeardown], the last client's [destroy], is done with storage, and
     * starts observing the app's lifecycle, whose events wait for start-up like any call.
     */
    fun start(previousTeardown: Job?) {
        val startTime = clock.nowMillis()
        lifetime = scope.launch { run(previousTeardown, startTime) }
        // The client is running now, so a failure here must not reach the facade, which would
        // leave it running unreachable.
        try {
            stopLifecycle = platform.observeLifecycle(AppLifecycle(), logger)
            if (stopLifecycle == null) {
                logger.warn("App lifecycle events are not tracked: the context does not lead to the Application")
            }
        } catch (t: Throwable) {
            logger.error("App lifecycle events are not tracked: observing them failed", t)
        }
    }

    /**
     * Runs the calls made so far, then a bounded final flush. The job completes once this client no
     * longer touches storage. Identity, consent and unsent events stay stored.
     */
    fun destroy(): Job {
        try {
            stopLifecycle?.invoke()
        } catch (t: Throwable) {
            logger.error("Pug could not stop observing the app lifecycle", t)
        }
        ops.close()
        return lifetime ?: CompletableDeferred(Unit)
    }

    fun track(kind: String, properties: Map<String, Any?>?, trackOptions: TrackOptions?) {
        val callTime = clock.nowMillis()
        if (!factory.acceptsKind(kind)) return
        // Mapped now, because the host may change the map or its values once track() returns.
        val custom = if (properties == null) emptyMap() else propertyMapper.mapCustom(kind, properties)
        WellKnownEvents.byKind[kind]?.let { reportMissing(it, custom) }
        submitCapture("Pug.track()", kind, custom, callTime, trackOptions)
    }

    /**
     * [values] are [event]'s properties in schema order, null where the caller left them out; [caller]
     * names the call in warnings.
     */
    fun trackTyped(
        event: EventSchema,
        values: Array<out Any?>,
        extras: Map<String, Any?>?,
        trackOptions: TrackOptions?,
        caller: String,
    ) {
        val callTime = clock.nowMillis()
        // An event object with a required property never set, or a null from Java.
        val missing = event.fields.filterIndexed { index, field -> field.required && values[index] == null }
        if (missing.isNotEmpty()) {
            val verb = if (missing.size == 1) "is" else "are"
            logger.warn("$caller was dropped: the required ${missing.joinToString { it.name }} $verb missing")
            return
        }
        val typed = LinkedHashMap<String, Any?>()
        event.fields.forEachIndexed { index, field ->
            val value = values[index]
            when {
                value is Long && field.type == FieldType.DURATION -> typed[field.name] = durationText(value)
                value != null -> typed[field.name] = value
            }
        }
        val custom = LinkedHashMap<String, PropertyValue>()
        if (extras != null) custom.putAll(propertyMapper.mapCustom(event.kind, extras))
        for ((name, value) in propertyMapper.mapCustom(event.kind, typed)) {
            if (custom.put(name, value) != null) {
                logger.warn("Extra \"$name\" of $caller was dropped: the event's own $name is set")
            }
        }
        submitCapture(caller, event.kind, custom, callTime, trackOptions)
    }

    private fun submitCapture(
        caller: String,
        kind: String,
        custom: Map<String, PropertyValue>,
        callTime: Long,
        trackOptions: TrackOptions?,
    ) {
        val occurTime = factory.occurTime(kind, trackOptions?.timestamp, callTime)
        val immediate = trackOptions?.immediate == true
        enqueue(caller) { capture(kind, custom, callTime, occurTime, immediate) }
    }

    fun identify(externalId: String, traits: Map<String, Any?>?) {
        if (externalId.isBlank()) {
            logger.error("identify() was dropped: externalId is blank")
            return
        }
        // As the distinct id it would fail every batch it is in.
        if (externalId.startsWith(RESERVED_ID_PREFIX)) {
            logger.error("identify() was dropped: externalId may not start with \"$RESERVED_ID_PREFIX\"")
            return
        }
        val mappedTraits = if (traits == null) emptyMap() else traitsMapper.map(traits)
        submit("identify") { sendIdentify(externalId, mappedTraits) }
    }

    fun reset() {
        val now = clock.nowMillis()
        submit("reset") {
            if (consent.isGranted) {
                identity.reset(now)
                // Queued events keep the outgoing user's distinct id; send them rather than hold them.
                dispatcher.flush()
            } else {
                identity.clear()
            }
            logger.debug("reset() cleared the identity; the next event starts a new one")
        }
    }

    fun rotateSession() {
        val now = clock.nowMillis()
        submit("rotateSession") {
            if (consent.isGranted) {
                identity.rotateSession(now)
            } else {
                logger.debug("rotateSession() was ignored: tracking consent is denied")
            }
        }
    }

    fun screen(name: String) {
        val callTime = clock.nowMillis()
        if (name.isBlank()) {
            logger.warn("A screen with a blank name was dropped")
            return
        }
        submit("screen") { showScreen(name, screenClass = null, callTime) }
    }

    fun flush() {
        submit("flush") { drainAndPersist() }
    }

    fun optInTracking() {
        setConsent("optInTracking", TrackingConsent.GRANTED)
    }

    fun optOutTracking() {
        setConsent("optOutTracking", TrackingConsent.DENIED)
    }

    val trackingConsent: TrackingConsent?
        get() = currentConsent()?.consent

    val isTrackingEnabled: Boolean
        get() = currentConsent()?.consent == TrackingConsent.GRANTED

    val isConsentPending: Boolean
        get() = currentConsent()?.isPending ?: true

    fun whenConsentReady(callback: Runnable) {
        synchronized(consentLock) {
            if (!consentRead) {
                consentWaiters += callback
                return
            }
        }
        runOnMainThread(callback)
    }

    private fun setConsent(name: String, value: TrackingConsent) {
        val now = clock.nowMillis()
        // Choices made at once on two threads: the one the getters report must be the one queued
        // last, which the worker applies last.
        synchronized(consentLock) {
            val queued = submit(name) {
                val wasGranted = consent.isGranted
                consent.set(value)
                // An opt-out and its purge must survive a process killed before the write-behind.
                fileStorage?.sync()
                logger.debug("Tracking consent is now ${value.name.lowercase()}")
                if (value == TrackingConsent.DENIED) {
                    // The identity that saw this visit's app_open is gone; the next visit opens afresh.
                    opened = false
                } else if (!wasGranted) {
                    // What a launch would have tracked, had consent allowed it then.
                    installs.emit { kind, properties -> captureInstallEvent(kind, properties, now) }
                    if (visible) {
                        open(now)
                        screen?.let { captureScreenView(it, screenClass, now) }
                    }
                }
            }
            // Refused once Pug is destroyed or failed to start: no choice to report.
            if (queued) consentView = ConsentView(value, isPending = false)
        }
    }

    private fun reportMissing(event: EventSchema, properties: Map<String, PropertyValue>) {
        if (!logger.isDebugEnabled) return
        val missing = event.fields.filter { it.required && it.name !in properties }
        if (missing.isEmpty()) return
        val names = missing.joinToString { "\"${it.name}\"" }
        logger.debug("Event \"${event.kind}\" is missing its required $names")
    }

    private fun currentConsent(): ConsentView? {
        consentView?.let { return it }
        // Read again only to word the warning: start-up sets consentView before consentRead, so a
        // view still unknown once consent was read means start-up failed.
        val message =
            if (consentRead && consentView == null) {
                "Tracking consent is reported as unknown: Pug failed to start"
            } else {
                "Tracking consent was asked for before Pug read it from storage; it is reported as unknown. " +
                    "Ask from Pug.whenConsentReady() instead"
            }
        if (consentUnknownReported) {
            logger.debug(message)
        } else {
            consentUnknownReported = true
            logger.warn(message)
        }
        return null
    }

    /** Releases the whenConsentReady() callbacks, once start-up has read consent or failed. */
    private fun consentReady() {
        val waiting =
            synchronized(consentLock) {
                if (consentRead) return
                consentRead = true
                consentWaiters.toList().also { consentWaiters.clear() }
            }
        waiting.forEach(::runOnMainThread)
    }

    private fun runOnMainThread(callback: Runnable) {
        try {
            platform.postToMainThread {
                // The host's own code, run from the main looper: a throw here would crash the app.
                try {
                    callback.run()
                } catch (t: Throwable) {
                    logger.error("The callback given to Pug.whenConsentReady() failed", t)
                }
            }
        } catch (t: Throwable) {
            logger.error("Pug.whenConsentReady() could not reach the main thread", t)
        }
    }

    private fun submit(name: String, block: () -> Unit): Boolean = enqueue("Pug.$name()", block)

    private fun enqueue(label: String, block: () -> Unit): Boolean {
        val queued = ops.trySend(Op(label, block)).isSuccess
        if (!queued) logger.debug("$label was ignored: Pug was destroyed")
        return queued
    }

    private suspend fun run(previousTeardown: Job?, startTime: Long) {
        try {
            previousTeardown?.join()
            restore(startTime)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Pug failed to start; calls are ignored", t)
            ops.cancel()
            // Consent is unknown from here: the choices queued so far went with the ops, never applied
            // or stored.
            synchronized(consentLock) { consentView = null }
            // A storage sync or a send started before the failure would otherwise write to storage
            // that the next client, which no longer waits for this one, is using.
            root.cancel()
            return
        } finally {
            consentReady()
        }
        for (op in ops) execute(op)
        tearDown()
    }

    private fun restore(startTime: Long) {
        val storage = SafeStorage(options.storage ?: defaultStorage(), logger)
        val keys = StorageKeys(projectId)
        consent =
            TrackingConsentController(
                storage,
                keys.consent,
                logger,
                options.defaultTrackingConsent,
                options.persistTrackingConsent,
                ConsentPurge(),
            )
        synchronized(consentLock) {
            // A choice made since init is queued behind this and applied after it, so it stays what
            // the getters report.
            if (consentView == null) consentView = ConsentView(consent.consent, consent.isPending)
        }
        consentReady()
        deviceProperties = readAutoProperties()
        // Before anything this launch writes, so the probe for earlier launches sees only theirs.
        installs = InstallTracker(storage, keys, logger)
        if (options.autoInstallEvents) installs.check(deviceProperties.appVersion, ::readInstallSource)
        identity = Identity(storage, keys, logger, ids, options.sessionIdleTimeoutMs, options.sessionMaxDurationMs)
        val queue = EventQueue(storage, keys.queue, logger, options.batchMaxQueueSize)
        dispatcher =
            EventDispatcher(
                queue,
                transport,
                logger,
                networkScope,
                options.batchMaxSize,
                options.batchMaxWaitMs,
                random,
            )
        consent.enforceAtInit()
        // Dated at init: the launch's app_open was dated on the main thread, and may precede this.
        installs.emit { kind, properties -> captureInstallEvent(kind, properties, startTime) }
        dispatcher.start()
        if (options.dryRun) logger.warn("dryRun is on: events and identify() are logged, never sent")
        logger.debug("Pug started for project \"$projectId\"")
    }

    private fun defaultStorage(): Storage {
        val directory =
            try {
                platform.storageDirectory()
            } catch (t: Throwable) {
                logger.warn("The storage directory could not be resolved", t)
                null
            }
        if (directory == null) {
            logger.warn("No storage directory is available; state is kept in memory until the app restarts")
            return MemoryStorage()
        }
        return FileStorage(directory, logger, scope).also { fileStorage = it }
    }

    private fun readAutoProperties(): AutoProperties = try {
        platform.autoProperties(logger)
    } catch (t: Throwable) {
        logger.warn("Device and app auto-properties could not be read", t)
        NoAutoProperties
    }

    private fun readInstallSource(): String? = try {
        platform.installSource()
    } catch (t: Throwable) {
        logger.warn("The install source could not be read", t)
        null
    }

    // Ops never suspend, so whatever one throws is that op's failure, never a cancellation of the
    // worker; catching it whole keeps the ops behind it running.
    private fun execute(op: Op) {
        try {
            op.block()
        } catch (t: Throwable) {
            logger.error("${op.label} failed", t)
        }
    }

    private suspend fun tearDown() {
        try {
            dispatcher.close()
            val settled =
                withTimeoutOrNull(TEARDOWN_TIMEOUT_MS) {
                    dispatcher.drain().join()
                    network.children.toList().joinAll()
                }
            if (settled == null) {
                logger.warn("Pug.destroy() stopped waiting for the network after $TEARDOWN_TIMEOUT_MS ms")
            }
        } finally {
            disposed = true
            dispatcher.dispose()
            fileStorage?.sync()
            val undelivered = dispatcher.undelivered
            if (undelivered > 0) logger.debug("Keeping ${eventCount(undelivered)} for the next Pug.init()")
            logger.debug("Pug was destroyed")
            root.cancel()
        }
    }

    /** Returns whether consent let the event through, which the lifecycle and install state follow. */
    private fun capture(
        kind: String,
        properties: Map<String, PropertyValue>,
        callTime: Long,
        occurTime: Long = callTime,
        immediate: Boolean = false,
    ): Boolean {
        if (!consent.isGranted) {
            logger.debug("Event \"$kind\" was dropped: tracking consent is denied")
            return false
        }
        val sessionId = identity.sessionId(callTime)
        val event =
            factory.create(kind, properties, autoProperties(), identity.distinctId, sessionId, occurTime)
                ?: return false
        if (options.dryRun) {
            logger.debug("dryRun: event \"$kind\" was not sent")
            return true
        }
        if (immediate) dispatcher.sendNow(event) else dispatcher.enqueue(event)
        if (logger.isDebugEnabled) logger.debug("Tracked \"$kind\"")
        return true
    }

    private fun autoProperties(): Map<String, Any?> {
        val properties = LinkedHashMap(deviceProperties.current())
        screen?.let { properties["\$url"] = it }
        previousScreen?.let { properties["\$referrer"] = it }
        return properties
    }

    private fun captureInstallEvent(kind: String, properties: Map<String, Any?>, time: Long): Boolean =
        capture(kind, propertyMapper.mapCustom(kind, properties), time)

    private fun enterForeground(now: Long) {
        visible = true
        open(now)
    }

    private fun open(now: Long) {
        if (!options.autoOpenCloseEvents || opened) return
        opened = capture(Events.APP_OPEN, emptyMap(), now)
    }

    private fun enterBackground(now: Long) {
        visible = false
        if (options.autoOpenCloseEvents && opened && capture(Events.APP_CLOSE, emptyMap(), now, immediate = true)) {
            opened = false
        }
        // The process may be frozen or killed from here on: whatever is queued gets its send now.
        drainAndPersist()
    }

    /** Sends every queued event, backoff or not, and writes storage now and once the sends settle. */
    private fun drainAndPersist() {
        val drained = dispatcher.drain()
        val files = fileStorage ?: return
        files.sync()
        scope.launch {
            drained.join()
            if (!disposed) files.sync()
        }
    }

    private fun showScreen(name: String, screenClass: String?, callTime: Long) {
        val shown = name.truncateUtf8(MAX_STRING_BYTES)
        if (shown.length != name.length) logger.warn("A screen name was truncated to $MAX_STRING_BYTES bytes")
        if (shown == screen) {
            logger.debug("Screen \"$shown\" is already the current screen")
            return
        }
        previousScreen = screen
        screen = shown
        this.screenClass = screenClass
        captureScreenView(shown, screenClass, callTime)
    }

    private fun captureScreenView(name: String, screenClass: String?, callTime: Long) {
        val properties = propertyMapper.mapCustom(
            Events.SCREEN_VIEW,
            mapOf("screenName" to name, "screenClass" to screenClass),
        )
        capture(Events.SCREEN_VIEW, properties, callTime)
    }

    private fun sendIdentify(externalId: String, traits: Map<String, JsonValue>) {
        if (!consent.isGranted) {
            logger.debug("identify() was dropped: tracking consent is denied")
            return
        }
        if (options.dryRun) {
            logger.debug("dryRun: identify() was not sent")
            return
        }
        val anonymousId = identity.anonymousId
        // Only the first identify links the anonymous history to the user.
        val request = IdentifyRequest(externalId, traits, anonymousId.takeIf { identity.externalId == null })
        val call = ++identifyCalls
        networkScope.launch {
            try {
                transport.identify(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                logger.error("identify() failed (${e.detail})", e)
                return@launch
            } catch (t: Throwable) {
                logger.error("identify() failed unexpectedly", t)
                return@launch
            }
            if (disposed) return@launch
            when {
                // A reset() or an opt-out during the request: linking now would bring back the
                // identity the host just cleared.
                !identity.isCurrentAnonymousId(anonymousId) ->
                    logger.debug("identify() finished after the identity was reset; it was not applied")

                call < linkedIdentifyCall ->
                    logger.debug("identify() finished after a later identify(); the later one stands")

                else -> {
                    identity.link(externalId)
                    linkedIdentifyCall = call
                    logger.debug("identify() succeeded")
                }
            }
        }
    }

    /** Main-thread callbacks, queued like calls so that they run in order with them. */
    private inner class AppLifecycle : LifecycleObserver {
        override fun onForeground() {
            val now = clock.nowMillis()
            enqueue("Tracking the app moving to the foreground") { enterForeground(now) }
        }

        override fun onBackground() {
            val now = clock.nowMillis()
            enqueue("Tracking the app moving to the background") { enterBackground(now) }
        }

        override fun onActivityResumed(screenName: String, screenClass: String) {
            if (!options.autoScreenViews) return
            val now = clock.nowMillis()
            enqueue("Tracking a screen view") { showScreen(screenName, screenClass, now) }
        }
    }

    private object NoAutoProperties : AutoProperties {
        override val appVersion: String? = null

        override fun current(): Map<String, Any?> = emptyMap()
    }

    private inner class ConsentPurge : TrackingConsentController.Purge {
        override val isIdentified: Boolean get() = identity.externalId != null

        override fun dropQueue() {
            val dropped = dispatcher.clear()
            if (dropped > 0) logger.debug("Dropped ${eventCount(dropped)} unsent: tracking consent is denied")
        }

        override fun forgetIdentity() {
            identity.clear()
        }
    }
}
