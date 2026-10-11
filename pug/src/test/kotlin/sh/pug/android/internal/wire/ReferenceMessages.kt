package sh.pug.android.internal.wire

import com.google.protobuf.Descriptors.Descriptor
import com.google.protobuf.DynamicMessage

/**
 * Builds the protobuf-java equivalent of each domain model, field by field from the descriptors, so
 * the hand-rolled encoder can be compared against a real protobuf implementation. Map entries are
 * added in sorted key order, matching protobuf-java's deterministic serialization.
 */
internal object ReferenceMessages {
    val batchCreateRequest: Descriptor = ProtoDescriptors.message("sdk.events.v1.BatchCreateRequest")
    val batchCreateResponse: Descriptor = ProtoDescriptors.message("sdk.events.v1.BatchCreateResponse")
    val identifyRequest: Descriptor = ProtoDescriptors.message("sdk.profiles.v1.IdentifyRequest")
    private val event = ProtoDescriptors.message("sdk.events.v1.Event")
    private val propertyValue = ProtoDescriptors.message("common.v1.PropertyValue")
    private val timestamp = ProtoDescriptors.message("google.protobuf.Timestamp")
    private val struct = ProtoDescriptors.message("google.protobuf.Struct")
    private val value = ProtoDescriptors.message("google.protobuf.Value")
    private val listValue = ProtoDescriptors.message("google.protobuf.ListValue")

    fun batch(events: List<Event>): DynamicMessage = DynamicMessage
        .newBuilder(batchCreateRequest)
        .apply { events.forEach { addRepeatedField(batchCreateRequest.field("events"), event(it)) } }
        .build()

    fun identify(request: IdentifyRequest): DynamicMessage = DynamicMessage
        .newBuilder(identifyRequest)
        .apply {
            setField(identifyRequest.field("external_id"), request.externalId)
            if (request.traits.isNotEmpty()) setField(identifyRequest.field("traits"), struct(request.traits))
            request.anonymousId?.let { setField(identifyRequest.field("anonymous_id"), it) }
        }.build()

    fun batchResponse(accepted: Int, dropped: Int, droppedByReason: Map<String, Int>): DynamicMessage {
        val field = batchCreateResponse.field("dropped_by_reason")
        return DynamicMessage
            .newBuilder(batchCreateResponse)
            .setField(batchCreateResponse.field("accepted"), accepted)
            .setField(batchCreateResponse.field("dropped"), dropped)
            .apply {
                droppedByReason.toSortedMap().forEach { (reason, count) ->
                    addRepeatedField(field, mapEntry(field.messageType, reason, count))
                }
            }.build()
    }

    private fun event(source: Event): DynamicMessage = DynamicMessage
        .newBuilder(event)
        .apply {
            setField(event.field("event_id"), source.eventId)
            addProperties("auto_properties", source.autoProperties)
            addProperties("custom_properties", source.customProperties)
            setField(event.field("distinct_id"), source.distinctId)
            setField(event.field("kind"), source.kind)
            setField(event.field("occur_time"), timestamp(source.occurTimeMillis))
            setField(event.field("session_id"), source.sessionId)
        }.build()

    private fun DynamicMessage.Builder.addProperties(fieldName: String, properties: Map<String, PropertyValue>) {
        val field = event.field(fieldName)
        properties.toSortedMap().forEach { (key, value) ->
            addRepeatedField(field, mapEntry(field.messageType, key, propertyValue(value)))
        }
    }

    private fun propertyValue(source: PropertyValue): DynamicMessage {
        val builder = DynamicMessage.newBuilder(propertyValue)
        when (source) {
            is PropertyValue.StringValue -> builder.setField(propertyValue.field("string_value"), source.value)

            is PropertyValue.IntValue -> builder.setField(propertyValue.field("int_value"), source.value)

            is PropertyValue.DoubleValue -> builder.setField(propertyValue.field("double_value"), source.value)

            is PropertyValue.BoolValue -> builder.setField(propertyValue.field("bool_value"), source.value)

            is PropertyValue.TimestampValue -> builder.setField(
                propertyValue.field("timestamp_value"),
                timestamp(source.epochMillis),
            )
        }
        return builder.build()
    }

    private fun timestamp(epochMillis: Long): DynamicMessage = DynamicMessage
        .newBuilder(timestamp)
        .setField(timestamp.field("seconds"), Math.floorDiv(epochMillis, 1000L))
        .setField(timestamp.field("nanos"), (Math.floorMod(epochMillis, 1000L) * 1_000_000L).toInt())
        .build()

    private fun struct(fields: Map<String, JsonValue>): DynamicMessage {
        val field = struct.field("fields")
        return DynamicMessage
            .newBuilder(struct)
            .apply {
                fields.toSortedMap().forEach { (key, v) ->
                    addRepeatedField(field, mapEntry(field.messageType, key, value(v)))
                }
            }
            .build()
    }

    private fun value(source: JsonValue): DynamicMessage {
        val builder = DynamicMessage.newBuilder(value)
        when (source) {
            JsonValue.Null -> builder.setField(
                value.field("null_value"),
                value.field("null_value").enumType.findValueByNumber(0),
            )

            is JsonValue.NumberValue -> builder.setField(value.field("number_value"), source.value)

            is JsonValue.StringValue -> builder.setField(value.field("string_value"), source.value)

            is JsonValue.BoolValue -> builder.setField(value.field("bool_value"), source.value)

            is JsonValue.ObjectValue -> builder.setField(value.field("struct_value"), struct(source.fields))

            is JsonValue.ArrayValue -> {
                val list =
                    DynamicMessage
                        .newBuilder(listValue)
                        .apply { source.values.forEach { addRepeatedField(listValue.field("values"), value(it)) } }
                        .build()
                builder.setField(value.field("list_value"), list)
            }
        }
        return builder.build()
    }

    private fun mapEntry(entryType: Descriptor, key: String, value: Any): DynamicMessage = DynamicMessage
        .newBuilder(entryType)
        .setField(entryType.field("key"), key)
        .setField(entryType.field("value"), value)
        .build()

    private fun Descriptor.field(name: String) = checkNotNull(findFieldByName(name)) { "no field $name on $fullName" }
}
