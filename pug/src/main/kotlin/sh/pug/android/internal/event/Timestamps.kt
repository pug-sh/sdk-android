package sh.pug.android.internal.event

import android.annotation.SuppressLint
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * The epoch milliseconds of a date-like value, or null when [value] is not one. `java.time.Instant`
 * counts only from API 26, where it exists.
 */
internal fun epochMillisOf(value: Any, sdkInt: Int): Long? {
    val millis =
        when (value) {
            is Date -> value.time
            is Calendar -> value.timeInMillis
            else -> if (sdkInt >= 26) Instants.epochMillis(value) else null
        } ?: return null
    if (millis !in TIMESTAMP_MILLIS) throw outOfRange()
    return millis
}

/** ISO 8601 in UTC with milliseconds, as JavaScript's `Date.toJSON` writes it. Not thread-safe. */
internal fun iso8601Format(): SimpleDateFormat {
    val calendar = GregorianCalendar(TimeZone.getTimeZone("UTC"), Locale.US)
    calendar.gregorianChange = Date(Long.MIN_VALUE)
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.calendar = calendar
    return format
}

private fun outOfRange() = UnsupportedValueException("the time is outside the years 1 to 9999")

// Only called once the SDK level is known to be at least 26; lint cannot follow that check through
// the injected level, and AndroidX's @RequiresApi is not a dependency here.
@SuppressLint("NewApi")
private object Instants {
    fun epochMillis(value: Any): Long? {
        if (value !is Instant) return null
        return try {
            value.toEpochMilli()
        } catch (e: ArithmeticException) {
            throw outOfRange()
        }
    }
}
