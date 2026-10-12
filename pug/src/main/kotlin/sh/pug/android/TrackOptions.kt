package sh.pug.android

/**
 * Options for one tracked event. In Kotlin: `TrackOptions { immediate = true }`; in Java:
 * `new TrackOptions.Builder().immediate(true).build()`.
 */
public class TrackOptions private constructor(builder: Builder) {
    public val immediate: Boolean = builder.immediate
    public val timestamp: Long? = builder.timestamp

    public class Builder {
        /** Sends the event on its own right away instead of with the next batch. */
        @set:JvmSynthetic
        public var immediate: Boolean = false

        /**
         * When the event happened, in epoch milliseconds; null means the time of the call. A value
         * before 1970 or after the year 9999 is logged and replaced by the time of the call.
         */
        @set:JvmSynthetic
        public var timestamp: Long? = null

        public fun immediate(immediate: Boolean): Builder = apply { this.immediate = immediate }

        public fun timestamp(timestamp: Long?): Builder = apply { this.timestamp = timestamp }

        public fun build(): TrackOptions = TrackOptions(this)
    }
}

@JvmSynthetic
public fun TrackOptions(configure: TrackOptions.Builder.() -> Unit): TrackOptions =
    TrackOptions.Builder().apply(configure).build()
