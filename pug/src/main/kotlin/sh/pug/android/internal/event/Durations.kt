package sh.pug.android.internal.event

import android.annotation.SuppressLint
import kotlin.time.Duration
import sh.pug.android.internal.wire.PropertyValue

private const val NANOS_PER_SECOND = 1_000_000_000L

/**
 * The duration [value] holds, to the nanosecond, or null when it is not one. `java.time.Duration`
 * counts only from API 26, where it exists.
 */
internal fun durationOf(value: Any, sdkInt: Int): PropertyValue.DurationValue? = when {
    value is Duration -> value.toComponents { seconds, nanos -> inRange(seconds, nanos.toLong()) }
    sdkInt >= 26 -> JavaDurations.of(value)
    else -> null
}

/**
 * [seconds] and [nanos] of any sign as google.protobuf.Duration holds them: nanos within a second and,
 * unless seconds is 0, of the sign of seconds. Null beyond ±10,000 years.
 */
internal fun normalizedDuration(seconds: Long, nanos: Long): PropertyValue.DurationValue? {
    // Checked first, so carrying the nanos into the seconds cannot overflow. The second of slack lets in
    // the most negative durations from java.time, which floors their seconds.
    if (seconds !in -MAX_DURATION_SECONDS - 1..MAX_DURATION_SECONDS + 1) return null
    var wholeSeconds = seconds + nanos / NANOS_PER_SECOND
    var nanosOfSecond = nanos % NANOS_PER_SECOND
    if (wholeSeconds > 0 && nanosOfSecond < 0) {
        wholeSeconds--
        nanosOfSecond += NANOS_PER_SECOND
    } else if (wholeSeconds < 0 && nanosOfSecond > 0) {
        wholeSeconds++
        nanosOfSecond -= NANOS_PER_SECOND
    }
    if (wholeSeconds !in DURATION_SECONDS) return null
    return PropertyValue.DurationValue(wholeSeconds, nanosOfSecond.toInt())
}

private fun inRange(seconds: Long, nanos: Long): PropertyValue.DurationValue =
    normalizedDuration(seconds, nanos) ?: throw UnsupportedValueException("the duration is beyond ±10,000 years")

// Only called once the SDK level is known to be at least 26; lint cannot follow that check through
// the injected level, and AndroidX's @RequiresApi is not a dependency here.
@SuppressLint("NewApi")
private object JavaDurations {
    // java.time floors the seconds and keeps the nanos positive, so -1.5 s arrives as -2 s and 0.5e9 ns.
    fun of(value: Any): PropertyValue.DurationValue? =
        if (value is java.time.Duration) inRange(value.seconds, value.nano.toLong()) else null
}
