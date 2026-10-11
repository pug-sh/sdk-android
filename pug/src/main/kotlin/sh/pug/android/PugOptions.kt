package sh.pug.android

import sh.pug.android.internal.isNull
import sh.pug.android.internal.logging.LogcatLogger
import sh.pug.android.internal.logging.SafeLogger

internal const val DEFAULT_ENDPOINT: String = "https://api.pugs.dev"
internal const val DEFAULT_BATCH_MAX_SIZE: Int = 10
internal const val DEFAULT_BATCH_MAX_WAIT_MS: Long = 5_000L
internal const val DEFAULT_BATCH_MAX_QUEUE_SIZE: Int = 1_000
internal const val DEFAULT_SESSION_IDLE_TIMEOUT_MS: Long = 30 * 60 * 1_000L
internal const val DEFAULT_SESSION_MAX_DURATION_MS: Long = 24 * 60 * 60 * 1_000L

// BatchCreateRequest.events is capped at 1000 by the server's validation rules.
internal const val MAX_BATCH_SIZE: Int = 1_000

/**
 * Configuration for `Pug.init`. In Kotlin: `PugOptions("pub_…") { debug = true }`; in Java:
 * `new PugOptions.Builder("pub_…").debug(true).build()`.
 *
 * Nothing here throws: an out-of-range number is logged and replaced by its default. The API key and
 * endpoint are checked by `Pug.init`.
 */
public class PugOptions private constructor(builder: Builder) {
    internal val apiKey: String = builder.apiKey
    internal val endpoint: String = builder.endpoint
    internal val debug: Boolean = builder.debug
    internal val dryRun: Boolean = builder.dryRun
    internal val autoTrack: Boolean = builder.autoTrack
    internal val autoScreenViews: Boolean = builder.autoScreenViews
    internal val autoInstallEvents: Boolean = builder.autoInstallEvents
    internal val logger: PugLogger? = builder.logger
    internal val storage: PugStorage? = builder.storage
    internal val defaultTrackingConsent: TrackingConsent
    internal val persistTrackingConsent: Boolean = builder.persistTrackingConsent
    internal val batchMaxSize: Int
    internal val batchMaxWaitMs: Long
    internal val batchMaxQueueSize: Int
    internal val sessionIdleTimeoutMs: Long
    internal val sessionMaxDurationMs: Long

    init {
        val log = SafeLogger(builder.logger ?: LogcatLogger, builder.debug)
        batchMaxSize =
            if (builder.batchMaxSize > MAX_BATCH_SIZE) {
                log.warn("batch maxSize ${builder.batchMaxSize} is above the server's limit; using $MAX_BATCH_SIZE")
                MAX_BATCH_SIZE
            } else {
                atLeast("batch maxSize", builder.batchMaxSize, 1, DEFAULT_BATCH_MAX_SIZE, log)
            }
        batchMaxWaitMs = atLeast("batch maxWaitMs", builder.batchMaxWaitMs, 0, DEFAULT_BATCH_MAX_WAIT_MS, log)
        batchMaxQueueSize =
            atLeast("batch maxQueueSize", builder.batchMaxQueueSize, 1, DEFAULT_BATCH_MAX_QUEUE_SIZE, log)
        sessionIdleTimeoutMs =
            atLeast("session idleTimeoutMs", builder.sessionIdleTimeoutMs, 1, DEFAULT_SESSION_IDLE_TIMEOUT_MS, log)
        sessionMaxDurationMs =
            atLeast("session maxDurationMs", builder.sessionMaxDurationMs, 1, DEFAULT_SESSION_MAX_DURATION_MS, log)
        defaultTrackingConsent =
            if (isNull(builder.defaultTrackingConsent)) {
                log.warn("trackingConsent default is null; using ${TrackingConsent.GRANTED}")
                TrackingConsent.GRANTED
            } else {
                builder.defaultTrackingConsent
            }
    }

    public class Builder(public val apiKey: String) {
        /** Base URL of the Pug API. */
        @set:JvmSynthetic
        public var endpoint: String = DEFAULT_ENDPOINT

        /** Logs debug messages, to [logger] or to Logcat. Warnings and errors are always logged. */
        @set:JvmSynthetic
        public var debug: Boolean = false

        /** Builds and logs events without sending anything, identify included. */
        @set:JvmSynthetic
        public var dryRun: Boolean = false

        /**
         * Tracks `app_open` and `app_close` as the app moves between foreground and background.
         * Queued events are sent when the app leaves the foreground either way.
         */
        @set:JvmSynthetic
        public var autoTrack: Boolean = true

        /**
         * Tracks a `screen_view` named after the activity's class each time an activity resumes on a
         * different screen. Turn it off to name every screen yourself with `Pug.screen`, which
         * activities then leave alone.
         */
        @set:JvmSynthetic
        public var autoScreenViews: Boolean = true

        /** Tracks `app_install` and `app_update` on the first launch of each app version. */
        @set:JvmSynthetic
        public var autoInstallEvents: Boolean = true

        /** Replaces the default Logcat logger (tag `Pug`). */
        @set:JvmSynthetic
        public var logger: PugLogger? = null

        /** Replaces the default file storage. [MemoryPugStorage] keeps nothing on the device. */
        @set:JvmSynthetic
        public var storage: PugStorage? = null

        internal var batchMaxSize: Int = DEFAULT_BATCH_MAX_SIZE
        internal var batchMaxWaitMs: Long = DEFAULT_BATCH_MAX_WAIT_MS
        internal var batchMaxQueueSize: Int = DEFAULT_BATCH_MAX_QUEUE_SIZE
        internal var sessionIdleTimeoutMs: Long = DEFAULT_SESSION_IDLE_TIMEOUT_MS
        internal var sessionMaxDurationMs: Long = DEFAULT_SESSION_MAX_DURATION_MS
        internal var defaultTrackingConsent: TrackingConsent = TrackingConsent.GRANTED
        internal var persistTrackingConsent: Boolean = false

        public fun endpoint(endpoint: String): Builder = apply { this.endpoint = endpoint }

        public fun debug(debug: Boolean): Builder = apply { this.debug = debug }

        public fun dryRun(dryRun: Boolean): Builder = apply { this.dryRun = dryRun }

        public fun autoTrack(autoTrack: Boolean): Builder = apply { this.autoTrack = autoTrack }

        public fun autoScreenViews(autoScreenViews: Boolean): Builder = apply { this.autoScreenViews = autoScreenViews }

        public fun autoInstallEvents(autoInstallEvents: Boolean): Builder =
            apply { this.autoInstallEvents = autoInstallEvents }

        public fun logger(logger: PugLogger?): Builder = apply { this.logger = logger }

        public fun storage(storage: PugStorage?): Builder = apply { this.storage = storage }

        /**
         * Events are sent in requests of up to [maxSize] (at most 1000): as soon as that many are
         * queued, otherwise [maxWaitMs] after the first of them. Beyond [maxQueueSize] undelivered
         * events the oldest is dropped.
         */
        @JvmOverloads
        public fun batch(
            maxSize: Int = DEFAULT_BATCH_MAX_SIZE,
            maxWaitMs: Long = DEFAULT_BATCH_MAX_WAIT_MS,
            maxQueueSize: Int = DEFAULT_BATCH_MAX_QUEUE_SIZE,
        ): Builder = apply {
            batchMaxSize = maxSize
            batchMaxWaitMs = maxWaitMs
            batchMaxQueueSize = maxQueueSize
        }

        /** A session ends after [idleTimeoutMs] without events, or [maxDurationMs] after it began. */
        @JvmOverloads
        public fun session(
            idleTimeoutMs: Long = DEFAULT_SESSION_IDLE_TIMEOUT_MS,
            maxDurationMs: Long = DEFAULT_SESSION_MAX_DURATION_MS,
        ): Builder = apply {
            sessionIdleTimeoutMs = idleTimeoutMs
            sessionMaxDurationMs = maxDurationMs
        }

        /**
         * [default] applies until the user makes a choice. With [persist], opt-in and opt-out are
         * stored and restored by the next `Pug.init`; without it they last for the process.
         */
        @JvmOverloads
        public fun trackingConsent(
            default: TrackingConsent = TrackingConsent.GRANTED,
            persist: Boolean = false,
        ): Builder = apply {
            defaultTrackingConsent = default
            persistTrackingConsent = persist
        }

        public fun build(): PugOptions = PugOptions(this)
    }

    private companion object {
        fun atLeast(name: String, value: Long, min: Long, default: Long, log: SafeLogger): Long {
            if (value >= min) return value
            log.warn("$name $value must be at least $min; using $default")
            return default
        }

        fun atLeast(name: String, value: Int, min: Int, default: Int, log: SafeLogger): Int =
            atLeast(name, value.toLong(), min.toLong(), default.toLong(), log).toInt()
    }
}

@JvmSynthetic
public fun PugOptions(apiKey: String, configure: PugOptions.Builder.() -> Unit = {}): PugOptions =
    PugOptions.Builder(apiKey).apply(configure).build()
