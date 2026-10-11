package sh.pug.android.internal.event

import android.os.Build
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.truncateUtf8
import sh.pug.android.internal.wire.PropertyValue

/**
 * Maps host values onto `PropertyValue`, dropping what the server would reject: every property is
 * validated with its batch, so one bad value would fail the events sent alongside it.
 */
internal class PropertyMapper(private val logger: SafeLogger, private val sdkInt: Int = Build.VERSION.SDK_INT) {
    /** Custom properties may not start with `$`, which is reserved for auto-properties. */
    fun mapCustom(kind: String, properties: Map<String, Any?>): Map<String, PropertyValue> =
        map(kind, properties, auto = false)

    /** Auto-property keys must start with `$`. */
    fun mapAuto(kind: String, properties: Map<String, Any?>): Map<String, PropertyValue> =
        map(kind, properties, auto = true)

    private fun map(kind: String, properties: Map<String, Any?>, auto: Boolean): Map<String, PropertyValue> {
        val mapped = LinkedHashMap<String, PropertyValue>()
        val schema = if (auto) null else WellKnownEvents.byKind[kind]
        try {
            if (properties.isEmpty()) return emptyMap()
            for (entry in properties.entries) {
                val key: String? = entry.key
                val value = entry.value ?: continue
                when {
                    key == null -> logger.warn("A property with a null key on event \"$kind\" was dropped")

                    key.startsWith('$') != auto -> logger.warn(
                        if (auto) {
                            "Auto-property \"$key\" was dropped: auto-property keys start with '$'"
                        } else {
                            "Property \"$key\" on event \"$kind\" was dropped: keys starting with '$' are reserved"
                        },
                    )

                    else -> convert(kind, key, value)?.let { mapped[key] = coerce(schema, key, it) }
                }
            }
        } catch (t: Throwable) {
            logger.warn("The properties of event \"$kind\" could not be read; ${mapped.size} were kept", t)
        }
        return mapped
    }

    // PLAN.md D19: a double-typed property of a well-known event gets one column type, even from `price: 10`.
    private fun coerce(schema: EventSchema?, key: String, value: PropertyValue): PropertyValue {
        if (value !is PropertyValue.IntValue || schema?.field(key)?.type != FieldType.DOUBLE) return value
        return PropertyValue.DoubleValue(value.value.toDouble())
    }

    private fun convert(kind: String, key: String, value: Any): PropertyValue? = try {
        truncate(kind, key, toPropertyValue(value))
    } catch (e: UnsupportedValueException) {
        logger.warn("Property \"$key\" on event \"$kind\" was dropped: ${e.message}")
        null
    } catch (t: Throwable) {
        logger.warn("Property \"$key\" on event \"$kind\" was dropped: reading it threw", t)
        null
    }

    private fun toPropertyValue(value: Any): PropertyValue = when (value) {
        is String -> PropertyValue.StringValue(value)
        is Boolean -> PropertyValue.BoolValue(value)
        is Int, is Long, is Short, is Byte, is AtomicInteger, is AtomicLong -> integer(value as Number)
        is Double -> finite(value)
        is Float -> finite(value.toDecimalDouble())
        is CharSequence, is Char -> PropertyValue.StringValue(value.toString())
        is Enum<*> -> PropertyValue.StringValue(value.name)
        is BigInteger -> if (value.bitLength() < 64) PropertyValue.IntValue(value.toLong()) else digits(value)
        is Number -> finite(value.toDouble())
        is UByte -> PropertyValue.IntValue(value.toLong())
        is UShort -> PropertyValue.IntValue(value.toLong())
        is UInt -> PropertyValue.IntValue(value.toLong())
        is ULong -> if (value <= Long.MAX_VALUE.toULong()) PropertyValue.IntValue(value.toLong()) else digits(value)
        else -> other(value)
    }

    private fun integer(value: Number): PropertyValue = PropertyValue.IntValue(value.toLong())

    // An integer too wide for int64 keeps its exact digits rather than rounding through a double.
    private fun digits(value: Any): PropertyValue = PropertyValue.StringValue(value.toString())

    private fun other(value: Any): PropertyValue {
        if (HostJson.isCollection(value)) {
            return PropertyValue.StringValue(HostJson.toJsonText(HostJson.toJsonValue(value, sdkInt)))
        }
        val millis = epochMillisOf(value, sdkInt) ?: throw unsupported(value)
        return PropertyValue.TimestampValue(millis)
    }

    private fun finite(value: Double): PropertyValue {
        if (!value.isFinite()) throw UnsupportedValueException("$value is not a finite number")
        return PropertyValue.DoubleValue(value)
    }

    private fun truncate(kind: String, key: String, value: PropertyValue): PropertyValue {
        if (value !is PropertyValue.StringValue) return value
        val truncated = value.value.truncateUtf8(MAX_STRING_BYTES)
        if (truncated.length == value.value.length) return value
        logger.warn("Property \"$key\" on event \"$kind\" was truncated to $MAX_STRING_BYTES bytes")
        return PropertyValue.StringValue(truncated)
    }
}
