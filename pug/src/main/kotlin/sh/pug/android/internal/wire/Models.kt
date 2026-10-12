package sh.pug.android.internal.wire

internal sealed interface PropertyValue {
    data class StringValue(val value: String) : PropertyValue

    data class IntValue(val value: Long) : PropertyValue

    data class DoubleValue(val value: Double) : PropertyValue

    data class BoolValue(val value: Boolean) : PropertyValue

    data class TimestampValue(val epochMillis: Long) : PropertyValue
}

internal data class Event(
    val eventId: String,
    val kind: String,
    val distinctId: String,
    val sessionId: String,
    val occurTimeMillis: Long,
    val autoProperties: Map<String, PropertyValue>,
    val customProperties: Map<String, PropertyValue>,
)

internal sealed interface JsonValue {
    data object Null : JsonValue

    data class NumberValue(val value: Double) : JsonValue

    data class StringValue(val value: String) : JsonValue

    data class BoolValue(val value: Boolean) : JsonValue

    data class ObjectValue(val fields: Map<String, JsonValue>) : JsonValue

    data class ArrayValue(val values: List<JsonValue>) : JsonValue
}

internal data class IdentifyRequest(
    val externalId: String,
    val traits: Map<String, JsonValue>,
    val anonymousId: String?,
)

internal data class BatchCreateResponse(val accepted: Long, val droppedByReason: Map<String, Long>)
