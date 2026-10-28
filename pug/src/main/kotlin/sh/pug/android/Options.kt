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
 * Configuration for `Pug.init`. In Kotlin: `Options("pub_…") { debug = true }`; in Java:
 * `new Options.Builder("pub_…").debug(true).build()`.
 *
 * Nothing here throws: an out-of-range number is logged and replaced by its default (a
 * `batchMaxSize` above 1000 is capped instead). The API key and
 * endpoint are checked by `Pug.init`.
 */
public class Options private constructor(builder: Builder) {
    internal val apiKey: String = builder.apiKey
    internal val endpoint: String = builder.endpoint
    internal val debug: Boolean = builder.debug
    internal val dryRun: Boolean = builder.dryRun
    internal val autoOpenCloseEvents: Boolean = builder.autoOpenCloseEvents
    internal val autoScreenViews: Boolean = builder.autoScreenViews
    internal val autoInstallEvents: Boolean = builder.autoInstallEvents
    internal val logger: Logger? = builder.logger
    internal val storage: Storage? = builder.storage
    internal val defaultTrackingConsent: TrackingConsent
    internal val persistTrackingConsent: Boolean = builder.persistTrackingConsent
    internal val batchMaxSize: Int
    internal val batchMaxWaitMs: Long
    internal val batchMaxQueueSize: Int
    internal val sessionIdleTimeoutMs: Long
    internal val sessionMaxDurationMs: Long

    /** A fresh logger per caller, since each reports a throwing host logger once. */
    internal fun newLogger(): SafeLogger = SafeLogger(logger ?: LogcatLogger, debug)

    init {
        val log = newLogger()
        batchMaxSize =
            if (builder.batchMaxSize > MAX_BATCH_SIZE) {
                log.warn("batchMaxSize ${builder.batchMaxSize} is above the server's limit; using $MAX_BATCH_SIZE")
                MAX_BATCH_SIZE
            } else {
                atLeast("batchMaxSize", builder.batchMaxSize, 1, DEFAULT_BATCH_MAX_SIZE, log)
            }
        batchMaxWaitMs = atLeast("batchMaxWaitMs", builder.batchMaxWaitMs, 0, DEFAULT_BATCH_MAX_WAIT_MS, log)
        batchMaxQueueSize =
            atLeast("batchMaxQueueSize", builder.batchMaxQueueSize, 1, DEFAULT_BATCH_MAX_QUEUE_SIZE, log)
        sessionIdleTimeoutMs =
            atLeast("sessionIdleTimeoutMs", builder.sessionIdleTimeoutMs, 1, DEFAULT_SESSION_IDLE_TIMEOUT_MS, log)
        sessionMaxDurationMs =
            atLeast("sessionMaxDurationMs", builder.sessionMaxDurationMs, 1, DEFAULT_SESSION_MAX_DURATION_MS, log)
        defaultTrackingConsent =
            if (isNull(builder.defaultTrackingConsent)) {
                log.warn("defaultTrackingConsent is null; using ${TrackingConsent.GRANTED}")
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
         * Tracks `app_open` and `app_close` as the app moves between foreground and background, and
         * nothing else: screen views and install events have their own switches. Queued events are
         * sent when the app leaves the foreground either way.
         */
        @set:JvmSynthetic
        public var autoOpenCloseEvents: Boolean = true

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
        public var logger: Logger? = null

        /** Replaces the default file storage. [MemoryStorage] keeps nothing on the device. */
        @set:JvmSynthetic
        public var storage: Storage? = null

        /**
         * Events are sent in requests of up to this many (at most 1000): as soon as that many are
         * queued, otherwise [batchMaxWaitMs] after the first of them.
         */
        @set:JvmSynthetic
        public var batchMaxSize: Int = DEFAULT_BATCH_MAX_SIZE

        /** How long the first queued event waits for a full batch. */
        @set:JvmSynthetic
        public var batchMaxWaitMs: Long = DEFAULT_BATCH_MAX_WAIT_MS

        /** Beyond this many undelivered events the oldest is dropped. */
        @set:JvmSynthetic
        public var batchMaxQueueSize: Int = DEFAULT_BATCH_MAX_QUEUE_SIZE

        /** A session ends after this long without events. */
        @set:JvmSynthetic
        public var sessionIdleTimeoutMs: Long = DEFAULT_SESSION_IDLE_TIMEOUT_MS

        /** A session ends this long after it began. */
        @set:JvmSynthetic
        public var sessionMaxDurationMs: Long = DEFAULT_SESSION_MAX_DURATION_MS

        /** The consent in effect until the user makes a choice. */
        @set:JvmSynthetic
        public var defaultTrackingConsent: TrackingConsent = TrackingConsent.GRANTED

        /**
         * Stores opt-in and opt-out so the next `Pug.init` restores them. Turned off, they last only
         * until `Pug.destroy()` or the process ends: for an app that keeps the user's choice itself
         * and calls `Pug.optInTracking()` or `Pug.optOutTracking()` on every launch.
         */
        @set:JvmSynthetic
        public var persistTrackingConsent: Boolean = true

        public fun endpoint(endpoint: String): Builder = apply { this.endpoint = endpoint }

        public fun debug(debug: Boolean): Builder = apply { this.debug = debug }

        public fun dryRun(dryRun: Boolean): Builder = apply { this.dryRun = dryRun }

        public fun autoOpenCloseEvents(autoOpenCloseEvents: Boolean): Builder =
            apply { this.autoOpenCloseEvents = autoOpenCloseEvents }

        public fun autoScreenViews(autoScreenViews: Boolean): Builder = apply { this.autoScreenViews = autoScreenViews }

        public fun autoInstallEvents(autoInstallEvents: Boolean): Builder =
            apply { this.autoInstallEvents = autoInstallEvents }

        public fun logger(logger: Logger?): Builder = apply { this.logger = logger }

        public fun storage(storage: Storage?): Builder = apply { this.storage = storage }

        public fun batchMaxSize(batchMaxSize: Int): Builder = apply { this.batchMaxSize = batchMaxSize }

        public fun batchMaxWaitMs(batchMaxWaitMs: Long): Builder = apply { this.batchMaxWaitMs = batchMaxWaitMs }

        public fun batchMaxQueueSize(batchMaxQueueSize: Int): Builder =
            apply { this.batchMaxQueueSize = batchMaxQueueSize }

        public fun sessionIdleTimeoutMs(sessionIdleTimeoutMs: Long): Builder =
            apply { this.sessionIdleTimeoutMs = sessionIdleTimeoutMs }

        public fun sessionMaxDurationMs(sessionMaxDurationMs: Long): Builder =
            apply { this.sessionMaxDurationMs = sessionMaxDurationMs }

        public fun defaultTrackingConsent(defaultTrackingConsent: TrackingConsent): Builder =
            apply { this.defaultTrackingConsent = defaultTrackingConsent }

        public fun persistTrackingConsent(persistTrackingConsent: Boolean): Builder =
            apply { this.persistTrackingConsent = persistTrackingConsent }

        public fun build(): Options = Options(this)
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
public fun Options(apiKey: String, configure: Options.Builder.() -> Unit = {}): Options =
    Options.Builder(apiKey).apply(configure).build()
