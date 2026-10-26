package sh.pug.android.internal.event

import java.math.BigInteger
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import sh.pug.android.internal.json.JsonWriter
import sh.pug.android.internal.wire.JsonValue

internal class UnsupportedValueException(message: String) : Exception(message)

/**
 * Host values as JSON, for identify traits and for properties holding maps, collections or arrays.
 * Follows `JSON.stringify` where it can: non-finite numbers become null and dates ISO 8601 strings.
 * Integers beyond ±(2^53 - 1) become strings, since a JSON number would round them. Any other object
 * becomes its `toString()`, where `JSON.stringify` would list its fields.
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
        private val dates by lazy(LazyThreadSafetyMode.NONE, ::iso8601Format)

        fun convert(value: Any?, depth: Int): JsonValue {
            if (++values > MAX_VALUES) throw UnsupportedValueException("it holds more than $MAX_VALUES values")
            return when (val scalar = value?.let(::scalarOf)) {
                is HostScalar.Text -> JsonValue.StringValue(scalar.value)

                is HostScalar.Bool -> JsonValue.BoolValue(scalar.value)

                is HostScalar.Integer -> integer(scalar.value)

                is HostScalar.Decimal -> decimal(scalar.value)

                is HostScalar.Digits -> JsonValue.StringValue(scalar.value)

                null -> when (value) {
                    null -> JsonValue.Null
                    is Map<*, *> -> nested(depth) { obj(value, depth + 1) }
                    else -> other(value, depth)
                }
            }
        }

        private fun other(value: Any, depth: Int): JsonValue {
            val elements = elementsOf(value)
            if (elements != null) return nested(depth) { array(elements, depth + 1) }
            val millis = epochMillisOf(value, sdkInt) ?: return JsonValue.StringValue(value.toString())
            return JsonValue.StringValue(dates.format(Date(millis)))
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

        private fun integer(value: Long): JsonValue = if (value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) {
            JsonValue.NumberValue(value.toDouble())
        } else {
            JsonValue.StringValue(value.toString())
        }

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

/** The host types properties and traits both accept as a single value, in the order they are tested. */
internal sealed interface HostScalar {
    class Text(val value: String) : HostScalar

    class Bool(val value: Boolean) : HostScalar

    class Integer(val value: Long) : HostScalar

    class Decimal(val value: Double) : HostScalar

    // An integer too wide for int64 keeps its exact digits rather than rounding through a double.
    class Digits(val value: String) : HostScalar
}

internal fun scalarOf(value: Any): HostScalar? = when (value) {
    is String -> HostScalar.Text(value)
    is Boolean -> HostScalar.Bool(value)
    is Int, is Long, is Short, is Byte, is AtomicInteger, is AtomicLong -> integer((value as Number).toLong())
    is Double -> HostScalar.Decimal(value)
    is Float -> HostScalar.Decimal(value.toDecimalDouble())
    is CharSequence, is Char -> HostScalar.Text(value.toString())
    is Enum<*> -> HostScalar.Text(value.name)
    is BigInteger -> if (value.bitLength() < 64) integer(value.toLong()) else digits(value)
    is Number -> HostScalar.Decimal(value.toDouble())
    is UByte -> integer(value.toLong())
    is UShort -> integer(value.toLong())
    is UInt -> integer(value.toLong())
    is ULong -> if (value <= Long.MAX_VALUE.toULong()) integer(value.toLong()) else digits(value)
    else -> null
}

private fun integer(value: Long): HostScalar = HostScalar.Integer(value)

private fun digits(value: Any): HostScalar = HostScalar.Digits(value.toString())

// Float.toString is the shortest decimal that identifies the float, so 0.1f stays 0.1 rather than
// widening to 0.10000000149011612.
internal fun Float.toDecimalDouble(): Double = if (isFinite()) toString().toDouble() else toDouble()
