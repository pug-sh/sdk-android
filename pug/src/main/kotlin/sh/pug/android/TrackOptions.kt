package sh.pug.android

/**
 * @property immediate send the event on its own right away instead of with the next batch.
 * @property timestamp when the event happened, in epoch milliseconds; null means the time of the
 *   call. A value before 1970 or after the year 9999 is logged and replaced by the time of the call.
 */
public class TrackOptions
@JvmOverloads
constructor(
    public val immediate: Boolean = false,
    public val timestamp: Long? = null,
)
