package sh.pug.android.internal.event

import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import sh.pug.android.internal.json.JsonWriter
import sh.pug.android.internal.wire.JsonValue

internal class UnsupportedValueException(message: String) : Exception(message)

/**
 * Host values as JSON, for identify traits and for properties holding maps, collections or arrays.
 * Follows `JSON.stringify` where it can: non-finite numbers become null and dates ISO 8601 strings.
 * Integers beyond ±(2^53 - 1) become strings, since a JSON number would round them.
 */
internal object HostJson {
    private const val MAX_DEPTH = 32

    // Bounds the work for a structure that references itself.
    private const val MAX_VALUES = 10_000

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun toJsonValue(value: Any?, sdkInt: Int): JsonValue = Conversion(sdkInt).convert(value, depth = 0)

    fun toJsonText(value: JsonValue): String = JsonWriter().also { it.write(value) }.toString()

    fun isCollection(value: Any): Boolean = value is Map<*, *> || elementsOf(value) != null

    private fun elementsOf(value: Any): Iterable<*>? = when (value) {
        is Iterable<*> -> value
        is Array<*> -> value.asIterable()
        is IntArray -> value.asIterable()
        is LongArray -> value.asIterable()
        is ShortArray -> value.asIterable()
        is ByteArray -> value.asIterable()
        is DoubleArray -> value.asIterable()
        is FloatArray -> value.asIterable()
        is BooleanArray -> value.asIterable()
        is CharArray -> value.asIterable()
        else -> null
    }

    private class Conversion(private val sdkInt: Int) {
        private var values = 0

        fun convert(value: Any?, depth: Int): JsonValue {
            if (++values > MAX_VALUES) throw UnsupportedValueException("it holds more than $MAX_VALUES values")
            return when (value) {
                null -> JsonValue.Null
                is String -> JsonValue.StringValue(value)
                is Boolean -> JsonValue.BoolValue(value)
                is Int, is Long, is Short, is Byte, is AtomicInteger, is AtomicLong -> integer(value as Number)
                is Double -> decimal(value)
                is Float -> decimal(value.toDecimalDouble())
                is CharSequence, is Char -> JsonValue.StringValue(value.toString())
                is Enum<*> -> JsonValue.StringValue(value.name)
                is BigInteger -> bigInteger(value)
                is Number -> decimal(value.toDouble())
                is UByte -> integer(value.toLong())
                is UShort -> integer(value.toLong())
                is UInt -> integer(value.toLong())
                is ULong -> unsignedLong(value)
                is Map<*, *> -> nested(depth) { obj(value, depth + 1) }
                else -> other(value, depth)
            }
        }

        private fun other(value: Any, depth: Int): JsonValue {
            val elements = elementsOf(value)
            if (elements != null) return nested(depth) { array(elements, depth + 1) }
            val millis = epochMillisOf(value, sdkInt) ?: throw unsupported(value)
            return JsonValue.StringValue(iso8601(millis))
        }

        private inline fun nested(depth: Int, convert: () -> JsonValue): JsonValue {
            if (depth >= MAX_DEPTH) throw UnsupportedValueException("it is nested more than $MAX_DEPTH levels deep")
            return convert()
        }

        private fun obj(map: Map<*, *>, depth: Int): JsonValue {
            val fields = LinkedHashMap<String, JsonValue>()
            for ((key, value) in map) {
                fields[key.toString()] = convert(value, depth)
            }
            return JsonValue.ObjectValue(fields)
        }

        private fun array(values: Iterable<*>, depth: Int): JsonValue =
            JsonValue.ArrayValue(values.map { convert(it, depth) })

        private fun integer(value: Number): JsonValue = integer(value.toLong())

        private fun integer(value: Long): JsonValue = if (value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
            JsonValue.NumberValue(value.toDouble())
        } else {
            JsonValue.StringValue(value.toString())
        }

        private fun bigInteger(value: BigInteger): JsonValue =
            if (value.bitLength() < 64) integer(value.toLong()) else JsonValue.StringValue(value.toString())

        private fun unsignedLong(value: ULong): JsonValue =
            if (value <= Long.MAX_VALUE.toULong()) integer(value.toLong()) else JsonValue.StringValue(value.toString())

        private fun decimal(value: Double): JsonValue =
            if (value.isFinite()) JsonValue.NumberValue(value) else JsonValue.Null
    }

    private fun JsonWriter.write(node: JsonValue) {
        when (node) {
            JsonValue.Null -> nullValue()

            is JsonValue.BoolValue -> value(node.value)

            is JsonValue.StringValue -> value(node.value)

            is JsonValue.NumberValue -> {
                val whole = node.value.toLong()
                if (whole.toDouble() == node.value && whole in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
                    value(whole)
                } else {
                    value(node.value)
                }
            }

            is JsonValue.ObjectValue -> {
                beginObject()
                for ((key, value) in node.fields) {
                    name(key)
                    write(value)
                }
                endObject()
            }

            is JsonValue.ArrayValue -> {
                beginArray()
                for (value in node.values) write(value)
                endArray()
            }
        }
    }
}

internal fun unsupported(value: Any): UnsupportedValueException =
    UnsupportedValueException("${value.javaClass.name} is not a supported type")

// Float.toString is the shortest decimal that identifies the float, so 0.1f stays 0.1 rather than
// widening to 0.10000000149011612.
internal fun Float.toDecimalDouble(): Double = if (isFinite()) toString().toDouble() else toDouble()
