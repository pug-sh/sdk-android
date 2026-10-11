package sh.pug.android.internal.runtime

import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import sh.pug.android.MemoryPugStorage
import sh.pug.android.PugEvents
import sh.pug.android.PugOptions
import sh.pug.android.PugStorage
import sh.pug.android.TrackOptions
import sh.pug.android.TrackingConsent
import sh.pug.android.internal.IdGenerator
import sh.pug.android.internal.PugClock
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
import sh.pug.android.internal.logging.LogcatLogger
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.platform.AutoProperties
import sh.pug.android.internal.platform.LifecycleObserver
import sh.pug.android.internal.platform.Platform
import sh.pug.android.internal.queue.EventQueue
import sh.pug.android.internal.storage.FilePugStorage
import sh.pug.android.internal.storage.PugStorageKeys
import sh.pug.android.internal.storage.SafePugStorage
import sh.pug.android.internal.transport.PugTransport
import sh.pug.android.internal.transport.PugTransportException
import sh.pug.android.internal.truncateUtf8
import sh.pug.android.internal.wire.IdentifyRequest
import sh.pug.android.internal.wire.JsonValue
import sh.pug.android.internal.wire.PropertyValue

// Bounds destroy()'s final flush, which the next init() waits for.
internal const val TEARDOWN_TIMEOUT_MS: Long = 10_000L

// For how long after start the consent getters wait for start-up to read a stored choice; after
// that they answer "unknown" at once.
internal const val CONSENT_WAIT_MS: Long = 1_000L

/**
 * One configured SDK instance. Calls, and the app's lifecycle callbacks, arrive on any thread: they
 * read the clock and map their arguments there, then queue an op that the SDK worker runs in call
 * order. The components holding state are built on the worker once the previous client's teardown
 * is over, and live there.
 */
internal class PugClient(
    private val projectId: String,
    private val options: PugOptions,
    private val transport: PugTransport,
    worker: CoroutineDispatcher,
    private val platform: Platform,
    private val clock: PugClock = PugClock.SYSTEM,
    private val ids: IdGenerator = UuidV7Generator(clock),
    private val random: Random = Random.Default,
    sdkInt: Int = Build.VERSION.SDK_INT,
    private val consentWaitMs: Long = CONSENT_WAIT_MS,
) {
    val logger: SafeLogger = SafeLogger(options.logger ?: LogcatLogger, options.debug)

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

    // The consent getters answer on the caller's thread: the latest opt-in or opt-out wins, else
    // what start-up restored, which they wait for until the deadline start() sets and no longer.
    @Volatile private var chosenConsent: TrackingConsent? = null
    private val consentLock = Any()

    @Volatile private var restoredConsent: RestoredConsent? = null
    private val consentRestored = CountDownLatch(1)

    @Volatile private var consentDeadlineNanos = 0L

    @Volatile private var consentUnknownReported = false

    // Worker-confined, from restore() on.
    private lateinit var consent: TrackingConsentController
    private lateinit var identity: Identity
    private lateinit var dispatcher: EventDispatcher
    private lateinit var installs: InstallTracker
    private lateinit var deviceProperties: AutoProperties
    private var fileStorage: FilePugStorage? = null
    private var disposed = false
    private var identifyCalls = 0
    private var linkedIdentifyCall = 0

    // The app as the worker sees it: whether it is on screen, and whether this visit's app_open was
    // captured, which app_close must follow (Flutter's captured-or-not contract).
    private var visible = false
    private var opened = false

    // What `$url` and `$referrer` report (PLAN.md D18).
    private var screen: String? = null
    private var screenClass: String? = null
    private var previousScreen: String? = null

    private class Op(val label: String, val block: () -> Unit)

    private class RestoredConsent(val consent: TrackingConsent, val isPending: Boolean)

    /**
     * Runs the client once [previousTeardown], the last client's [destroy], is done with storage, and
     * starts observing the app's lifecycle, whose events wait for start-up like any call.
     */
    fun start(previousTeardown: Job?) {
        val startTime = clock.nowMillis()
        consentDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(consentWaitMs)
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
     * longer touches storage. Identity, consent and unsent events stay stored (PLAN.md D11).
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
        val occurTime = factory.occurTime(kind, trackOptions?.timestamp, callTime)
        // Mapped now, because the host may change the map or its values once track() returns.
        val custom = if (properties == null) emptyMap() else propertyMapper.mapCustom(kind, properties)
        WellKnownEvents.byKind[kind]?.let { reportMissing(it, custom) }
        val immediate = trackOptions?.immediate == true
        submit("track") { capture(kind, custom, callTime, occurTime, immediate) }
    }

    /** [values] are [event]'s properties in schema order, null where the caller left them out. */
    fun trackTyped(
        event: EventSchema,
        values: Array<out Any?>,
        extras: Map<String, Any?>?,
        trackOptions: TrackOptions?,
    ) {
        val callTime = clock.nowMillis()
        val typed = LinkedHashMap<String, Any?>()
        event.fields.forEachIndexed { index, field ->
            val value = values[index]
            when {
                value is Long && field.type == FieldType.DURATION -> typed[field.name] = durationText(value)

                value != null -> typed[field.name] = value

                // Only Java code can pass null for a required argument (PLAN.md D20).
                field.required -> {
                    logger.warn("Pug.${event.label}() was dropped: ${field.name} is null")
                    return
                }
            }
        }
        val occurTime = factory.occurTime(event.kind, trackOptions?.timestamp, callTime)
        val custom = LinkedHashMap<String, PropertyValue>()
        if (extras != null) custom.putAll(propertyMapper.mapCustom(event.kind, extras))
        for ((name, value) in propertyMapper.mapCustom(event.kind, typed)) {
            if (custom.put(name, value) != null) {
                logger.warn("Extra \"$name\" of Pug.${event.label}() was dropped: an argument sets it")
            }
        }
        val immediate = trackOptions?.immediate == true
        submit(event.label) { capture(event.kind, custom, callTime, occurTime, immediate) }
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
        get() = chosenConsent ?: restored()?.consent

    val isTrackingEnabled: Boolean
        get() = trackingConsent == TrackingConsent.GRANTED

    val isConsentPending: Boolean
        get() {
            if (chosenConsent != null) return false
            return restored()?.isPending ?: true
        }

    private fun setConsent(name: String, value: TrackingConsent) {
        val now = clock.nowMillis()
        // Choices made at once on two threads: the one the getters report must be the one queued
        // last, which the worker applies last.
        synchronized(consentLock) {
            chosenConsent = value
            submit(name) {
                val wasGranted = consent.isGranted
                consent.set(value)
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
        }
    }

    private fun reportMissing(event: EventSchema, properties: Map<String, PropertyValue>) {
        if (!logger.isDebugEnabled) return
        val missing = event.fields.filter { it.required && it.name !in properties }
        if (missing.isEmpty()) return
        val names = missing.joinToString { "\"${it.name}\"" }
        logger.debug("Event \"${event.kind}\" is missing $names, which Pug.${event.label}() requires")
    }

    private fun restored(): RestoredConsent? {
        restoredConsent?.let { return it }
        val started =
            try {
                consentRestored.await(maxOf(consentDeadlineNanos - System.nanoTime(), 0L), TimeUnit.NANOSECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        if (!started) {
            val message = "Tracking consent was asked for before Pug finished starting; it is reported as unknown"
            if (consentUnknownReported) {
                logger.debug(message)
            } else {
                consentUnknownReported = true
                logger.warn(message)
            }
        }
        return restoredConsent
    }

    private fun submit(name: String, block: () -> Unit) {
        enqueue("Pug.$name()", block)
    }

    private fun enqueue(label: String, block: () -> Unit) {
        if (ops.trySend(Op(label, block)).isFailure) logger.debug("$label was ignored: Pug was destroyed")
    }

    private suspend fun run(previousTeardown: Job?, startTime: Long) {
        previousTeardown?.join()
        try {
            restore(startTime)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Pug failed to start; calls are ignored", t)
            ops.cancel()
            // A storage sync or a send started before the failure would otherwise write to storage
            // that the next client, which no longer waits for this one, is using.
            root.cancel()
            return
        } finally {
            consentRestored.countDown()
        }
        for (op in ops) execute(op)
        tearDown()
    }

    private fun restore(startTime: Long) {
        val storage = SafePugStorage(options.storage ?: defaultStorage(), logger)
        val keys = PugStorageKeys(projectId)
        consent =
            TrackingConsentController(
                storage,
                keys.consent,
                logger,
                options.defaultTrackingConsent,
                options.persistTrackingConsent,
                ConsentPurge(),
            )
        restoredConsent = RestoredConsent(consent.consent, consent.isPending)
        consentRestored.countDown()
        deviceProperties = readAutoProperties()
        // Before anything this launch writes, so the probe for earlier launches sees only theirs.
        installs = InstallTracker(storage, keys, logger)
        if (options.autoInstallEvents) installs.check(deviceProperties.appVersion)
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

    private fun defaultStorage(): PugStorage {
        val directory =
            try {
                platform.storageDirectory()
            } catch (t: Throwable) {
                logger.warn("The storage directory could not be resolved", t)
                null
            }
        if (directory == null) {
            logger.warn("No storage directory is available; state is kept in memory until the app restarts")
            return MemoryPugStorage()
        }
        return FilePugStorage(directory, logger, scope).also { fileStorage = it }
    }

    private fun readAutoProperties(): AutoProperties = try {
        platform.autoProperties(logger)
    } catch (t: Throwable) {
        logger.warn("Device and app auto-properties could not be read", t)
        NoAutoProperties
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
        occurTime: Long,
        immediate: Boolean,
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
        logger.debug("Tracked \"$kind\"")
        return true
    }

    private fun autoProperties(): Map<String, Any?> {
        val properties = LinkedHashMap(deviceProperties.current())
        screen?.let { properties["\$url"] = it }
        previousScreen?.let { properties["\$referrer"] = it }
        return properties
    }

    private fun captureInstallEvent(kind: String, properties: Map<String, Any?>, time: Long): Boolean =
        capture(kind, propertyMapper.mapCustom(kind, properties), time, time, immediate = false)

    private fun enterForeground(now: Long) {
        visible = true
        open(now)
    }

    private fun open(now: Long) {
        if (!options.autoTrack || opened) return
        opened = capture(PugEvents.APP_OPEN, emptyMap(), now, now, immediate = false)
    }

    private fun enterBackground(now: Long) {
        visible = false
        if (options.autoTrack && opened && capture(PugEvents.APP_CLOSE, emptyMap(), now, now, immediate = true)) {
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
            PugEvents.SCREEN_VIEW,
            mapOf("screenName" to name, "screenClass" to screenClass),
        )
        capture(PugEvents.SCREEN_VIEW, properties, callTime, callTime, immediate = false)
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
            } catch (e: PugTransportException) {
                logger.error("identify() failed (${e.code.wireName}: ${e.message})", e)
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
