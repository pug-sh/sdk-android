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
import sh.pug.android.PugOptions
import sh.pug.android.TrackOptions
import sh.pug.android.TrackingConsent
import sh.pug.android.internal.IdGenerator
import sh.pug.android.internal.PugClock
import sh.pug.android.internal.UuidV7Generator
import sh.pug.android.internal.consent.TrackingConsentController
import sh.pug.android.internal.event.EventFactory
import sh.pug.android.internal.event.PropertyMapper
import sh.pug.android.internal.event.TraitsMapper
import sh.pug.android.internal.identity.Identity
import sh.pug.android.internal.identity.RESERVED_ID_PREFIX
import sh.pug.android.internal.logging.LogcatLogger
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.queue.EventQueue
import sh.pug.android.internal.storage.PugStorageKeys
import sh.pug.android.internal.storage.SafePugStorage
import sh.pug.android.internal.transport.PugTransport
import sh.pug.android.internal.transport.PugTransportException
import sh.pug.android.internal.wire.IdentifyRequest
import sh.pug.android.internal.wire.JsonValue
import sh.pug.android.internal.wire.PropertyValue

// Bounds destroy()'s final flush, which the next init() waits for.
internal const val TEARDOWN_TIMEOUT_MS: Long = 10_000L

// How long a consent getter waits for start-up to read a stored choice before answering "unknown".
internal const val CONSENT_WAIT_MS: Long = 1_000L

/**
 * One configured SDK instance. Calls arrive on any thread: they read the clock and map their
 * arguments there, then queue an op that the SDK worker runs in call order. The components holding
 * state are built on the worker once the previous client's teardown is over, and live there.
 */
internal class PugClient(
    private val projectId: String,
    private val options: PugOptions,
    private val transport: PugTransport,
    worker: CoroutineDispatcher,
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

    private val propertyMapper = PropertyMapper(logger, sdkInt)
    private val traitsMapper = TraitsMapper(logger, sdkInt)
    private val factory = EventFactory(projectId, logger, propertyMapper, ids)

    // The consent getters answer on the caller's thread: the latest opt-in or opt-out wins, else
    // what start-up restored.
    @Volatile private var chosenConsent: TrackingConsent? = null

    @Volatile private var restoredConsent: RestoredConsent? = null
    private val consentRestored = CountDownLatch(1)

    // Worker-confined, from restore() on.
    private lateinit var consent: TrackingConsentController
    private lateinit var identity: Identity
    private lateinit var dispatcher: EventDispatcher
    private var disposed = false
    private var identifyCalls = 0
    private var linkedIdentifyCall = 0

    private class Op(val name: String, val block: () -> Unit)

    private class RestoredConsent(val consent: TrackingConsent, val isPending: Boolean)

    /** Runs the client once [previousTeardown], the last client's [destroy], is done with storage. */
    fun start(previousTeardown: Job?) {
        lifetime = scope.launch { run(previousTeardown) }
    }

    /**
     * Runs the calls made so far, then a bounded final flush. The job completes once this client no
     * longer touches storage. Identity, consent and unsent events stay stored (PLAN.md D11).
     */
    fun destroy(): Job {
        ops.close()
        return lifetime ?: CompletableDeferred(Unit)
    }

    fun track(kind: String, properties: Map<String, Any?>?, trackOptions: TrackOptions?) {
        val callTime = clock.nowMillis()
        if (!factory.acceptsKind(kind)) return
        val occurTime = factory.occurTime(kind, trackOptions?.timestamp, callTime)
        // Mapped now, because the host may change the map or its values once track() returns.
        val custom = if (properties == null) emptyMap() else propertyMapper.mapCustom(kind, properties)
        val immediate = trackOptions?.immediate == true
        submit("track") { capture(kind, custom, callTime, occurTime, immediate) }
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

    fun flush() {
        submit("flush") { dispatcher.drain() }
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
        chosenConsent = value
        submit(name) {
            consent.set(value)
            logger.debug("Tracking consent is now ${value.name.lowercase()}")
        }
    }

    private fun restored(): RestoredConsent? {
        restoredConsent?.let { return it }
        try {
            if (!consentRestored.await(consentWaitMs, TimeUnit.MILLISECONDS)) {
                logger.warn("Tracking consent was asked for before Pug finished starting; it is reported as unknown")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return restoredConsent
    }

    private fun submit(name: String, block: () -> Unit) {
        if (ops.trySend(Op(name, block)).isFailure) logger.debug("Pug.$name() was ignored: Pug was destroyed")
    }

    private suspend fun run(previousTeardown: Job?) {
        previousTeardown?.join()
        try {
            restore()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            logger.error("Pug failed to start; calls are ignored", t)
            ops.cancel()
            return
        } finally {
            consentRestored.countDown()
        }
        for (op in ops) execute(op)
        tearDown()
    }

    private fun restore() {
        val storage = SafePugStorage(options.storage ?: MemoryPugStorage(), logger)
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
        dispatcher.start()
        if (options.dryRun) logger.warn("dryRun is on: events and identify() are logged, never sent")
        logger.debug("Pug started for project \"$projectId\"")
    }

    // Ops never suspend, so whatever one throws is that op's failure, never a cancellation of the
    // worker; catching it whole keeps the ops behind it running.
    private fun execute(op: Op) {
        try {
            op.block()
        } catch (t: Throwable) {
            logger.error("Pug.${op.name}() failed", t)
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
            val undelivered = dispatcher.undelivered
            if (undelivered > 0) logger.debug("Keeping ${eventCount(undelivered)} for the next Pug.init()")
            logger.debug("Pug was destroyed")
            root.cancel()
        }
    }

    private fun capture(
        kind: String,
        properties: Map<String, PropertyValue>,
        callTime: Long,
        occurTime: Long,
        immediate: Boolean,
    ) {
        if (!consent.isGranted) {
            logger.debug("Event \"$kind\" was dropped: tracking consent is denied")
            return
        }
        val sessionId = identity.sessionId(callTime)
        val event = factory.create(kind, properties, emptyMap(), identity.distinctId, sessionId, occurTime) ?: return
        if (options.dryRun) {
            logger.debug("dryRun: event \"$kind\" was not sent")
            return
        }
        if (immediate) dispatcher.sendNow(event) else dispatcher.enqueue(event)
        logger.debug("Tracked \"$kind\"")
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
