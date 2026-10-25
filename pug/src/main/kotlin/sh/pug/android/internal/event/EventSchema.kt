package sh.pug.android.internal.event

import kotlin.math.abs

internal enum class FieldType { STRING, INT, DOUBLE, BOOLEAN, DURATION }

internal class FieldSchema(val name: String, val type: FieldType, val required: Boolean = false)

/** A well-known event: its [kind], its method on `Pug.track` (if it has one), and its properties in schema order. */
internal class EventSchema(val kind: String, method: String, vararg fields: FieldSchema) {
    val fields: List<FieldSchema> = fields.asList()

    /** The method as calls name it in logs: `track.addToCart` for `Pug.track.addToCart()`. */
    val label: String = "track.$method"

    fun field(name: String): FieldSchema? = fields.firstOrNull { it.name == name }
}

/** A duration in options.proto's text form for event properties, seconds with a trailing `s`: `"1.5s"`. */
internal fun durationText(millis: Long): String {
    val sign = if (millis < 0) "-" else ""
    val seconds = abs(millis / 1_000)
    val fraction = abs(millis % 1_000)
    if (fraction == 0L) return "$sign${seconds}s"
    return "$sign$seconds.${fraction.toString().padStart(3, '0').trimEnd('0')}s"
}
