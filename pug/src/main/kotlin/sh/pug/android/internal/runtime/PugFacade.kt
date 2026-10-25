package sh.pug.android.internal.runtime

import android.content.Context
import java.net.MalformedURLException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import sh.pug.android.PugOptions
import sh.pug.android.TrackOptions
import sh.pug.android.TrackingConsent
import sh.pug.android.events.WellKnownEvent
import sh.pug.android.internal.event.EventSchema
import sh.pug.android.internal.isNull
import sh.pug.android.internal.logging.LogcatLogger
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.platform.AndroidPlatform
import sh.pug.android.internal.transport.HttpPugTransport

/**
 * The state behind `Pug`: the running client, and the last client's teardown, which the next one
 * waits for before it touches storage. Callable from any thread; only [init] throws, and only for
 * invalid arguments.
 */
internal class PugFacade(
    private val fallbackLogger: SafeLogger,
    private val newClient: (context: Context, projectId: String, options: PugOptions) -> PugClient,
) {
    private val lock = Any()

    @Volatile private var client: PugClient? = null

    // Guarded by lock.
    private var teardown: Job? = null

    fun init(context: Context, projectId: String, options: PugOptions) {
        require(!isNull(context)) { "Pug.init() needs a Context" }
        require(!isNull(projectId) && projectId.isNotBlank()) { "Pug.init() needs a projectId" }
        require(!isNull(options)) { "Pug.init() needs PugOptions" }
        require(!isNull(options.apiKey) && options.apiKey.isNotBlank()) { "Pug.init() needs an API key" }
        require(!isNull(options.endpoint) && isHttpUrl(options.endpoint)) {
            "The Pug endpoint must be an http(s) URL, not \"${options.endpoint}\""
        }
        try {
            val started =
                synchronized(lock) {
                    if (client != null) return@synchronized false
                    client = newClient(context, projectId, options).also { it.start(teardown) }
                    true
                }
            if (!started) {
                options.newLogger().warn("Pug.init() was called again; the first configuration stays")
            }
        } catch (t: Throwable) {
            fallbackLogger.error("Pug.init() failed; calls are ignored", t)
        }
    }

    fun track(kind: String, properties: Map<String, Any?>?, options: TrackOptions?) {
        call("track") {
            if (isNull(kind)) {
                it.logger.warn("An event with a null kind was dropped")
            } else {
                it.track(kind, properties, options)
            }
        }
    }

    fun track(event: WellKnownEvent, options: TrackOptions?) {
        call("track") {
            if (isNull(event)) {
                it.logger.warn("A null event was dropped")
            } else {
                it.trackTyped(event.schema, event.values, event.extras, options, event.caller)
            }
        }
    }

    /** The methods of `Pug.track`: [values] are the event's properties in schema order, null where unset. */
    fun trackTyped(event: EventSchema, extras: Map<String, Any?>?, options: TrackOptions?, vararg values: Any?) {
        call(event.label) { it.trackTyped(event, values, extras, options, "Pug.${event.label}()") }
    }

    fun identify(externalId: String, traits: Map<String, Any?>?) {
        call("identify") {
            if (isNull(externalId)) {
                it.logger.error("identify() was dropped: externalId is null")
            } else {
                it.identify(externalId, traits)
            }
        }
    }

    fun screen(name: String) {
        call("screen") {
            if (isNull(name)) {
                it.logger.warn("A screen with a null name was dropped")
            } else {
                it.screen(name)
            }
        }
    }

    fun reset() {
        call("reset") { it.reset() }
    }

    fun rotateSession() {
        call("rotateSession") { it.rotateSession() }
    }

    fun flush() {
        call("flush") { it.flush() }
    }

    fun optInTracking() {
        call("optInTracking") { it.optInTracking() }
    }

    fun optOutTracking() {
        call("optOutTracking") { it.optOutTracking() }
    }

    fun getTrackingConsent(): TrackingConsent? = query("getTrackingConsent", fallback = null) { it.trackingConsent }

    fun isTrackingEnabled(): Boolean = query("isTrackingEnabled", fallback = false) { it.isTrackingEnabled }

    fun isConsentPending(): Boolean = query("isConsentPending", fallback = true) { it.isConsentPending }

    fun whenConsentReady(callback: Runnable) {
        call("whenConsentReady") { owner ->
            if (isNull(callback)) {
                owner.logger.warn("Pug.whenConsentReady() was given a null callback; it was ignored")
            } else {
                // Destroyed before it ran, its client would release it while the getters already ask
                // the next client, which may not have read consent yet: that one releases it instead.
                owner.whenConsentReady { if (client === owner) callback.run() else whenConsentReady(callback) }
            }
        }
    }

    fun destroy() {
        try {
            val destroyed =
                synchronized(lock) {
                    val current = client ?: return@synchronized false
                    client = null
                    val job = current.destroy()
                    teardown = job
                    // A finished teardown has nothing left to wait for, and would keep its client alive.
                    job.invokeOnCompletion { synchronized(lock) { if (teardown === job) teardown = null } }
                    true
                }
            if (!destroyed) fallbackLogger.warn("Pug.destroy() was called before Pug.init(); it was ignored")
        } catch (t: Throwable) {
            fallbackLogger.error("Pug.destroy() failed", t)
        }
    }

    private inline fun call(name: String, block: (PugClient) -> Unit) {
        val current = client
        if (current == null) {
            fallbackLogger.warn("Pug.$name() was called before Pug.init(); it was ignored")
            return
        }
        try {
            block(current)
        } catch (t: Throwable) {
            current.logger.error("Pug.$name() failed", t)
        }
    }

    private inline fun <T> query(name: String, fallback: T, block: (PugClient) -> T): T {
        val current = client
        if (current == null) {
            fallbackLogger.warn("Pug.$name() was called before Pug.init(); returning $fallback")
            return fallback
        }
        return try {
            block(current)
        } catch (t: Throwable) {
            current.logger.error("Pug.$name() failed", t)
            fallback
        }
    }

    companion object {
        fun create(): PugFacade {
            // Shared by every client, so two never touch storage at once.
            val worker = Dispatchers.IO.limitedParallelism(1)
            return PugFacade(SafeLogger(LogcatLogger, isDebugEnabled = false)) { context, projectId, options ->
                val transport = HttpPugTransport(options.endpoint, options.apiKey)
                PugClient(projectId, options, transport, worker, AndroidPlatform(context))
            }
        }

        private fun isHttpUrl(endpoint: String): Boolean {
            val url =
                try {
                    URL(endpoint)
                } catch (e: MalformedURLException) {
                    return false
                }
            return (url.protocol == "http" || url.protocol == "https") && !url.host.isNullOrEmpty()
        }
    }
}
