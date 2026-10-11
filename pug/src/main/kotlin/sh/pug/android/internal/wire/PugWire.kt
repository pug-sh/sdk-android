package sh.pug.android.internal.wire

import sh.pug.android.internal.utf8Length

// Field numbers mirror proto/sdk/events/v1/events.proto, proto/sdk/profiles/v1/profiles.proto,
// proto/common/v1/property_value.proto and google/protobuf/{timestamp,struct}.proto. Map entries
// are written key-sorted, which is what protobuf-java's deterministic serialization produces.
internal object PugWire {
    fun encodeBatchCreateRequest(events: List<Event>): ByteArray {
        val request = ProtoWriter(initialCapacity = 512 * events.size)
        for (event in events) {
            request.writeMessage(1, event(event))
        }
        return request.toByteArray()
    }

    fun encodeIdentifyRequest(request: IdentifyRequest): ByteArray {
        val message = ProtoWriter()
        message.writeString(1, request.externalId)
        if (request.traits.isNotEmpty()) {
            message.writeMessage(2, struct(request.traits))
        }
        if (!request.anonymousId.isNullOrEmpty()) {
            message.writeString(3, request.anonymousId)
        }
        return message.toByteArray()
    }

    /** Bytes [event] adds to an encoded `BatchCreateRequest`: its tag, length prefix and message. */
    fun eventSize(event: Event): Int = lengthDelimitedSize(1, eventMessageSize(event))

    fun decodeBatchCreateResponse(bytes: ByteArray): BatchCreateResponse {
        val reader = ProtoReader(bytes)
        var accepted = 0L
        var dropped = 0L
        val droppedByReason = LinkedHashMap<String, Long>()
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when (tag) {
                fieldKey(1, WIRE_VARINT) -> accepted = reader.readVarint() and UINT32_MASK

                fieldKey(2, WIRE_VARINT) -> dropped = reader.readVarint() and UINT32_MASK

                fieldKey(3, WIRE_LEN) -> {
                    val entry = reader.readMessage()
                    var reason = ""
                    var count = 0L
                    while (!entry.isAtEnd) {
                        when (val entryTag = entry.readTag()) {
                            fieldKey(1, WIRE_LEN) -> reason = entry.readString()
                            fieldKey(2, WIRE_VARINT) -> count = entry.readVarint() and UINT32_MASK
                            else -> entry.skip(entryTag and 7)
                        }
                    }
                    droppedByReason[reason] = count
                }

                else -> reader.skip(tag and 7)
            }
        }
        return BatchCreateResponse(accepted, dropped, droppedByReason)
    }

    // edition 2023 gives singular fields explicit presence, so an empty id would be sent as a set
    // field and fail the server's uuid rule; empty means "absent" in the domain model.
    private fun event(event: Event): ProtoWriter {
        val message = ProtoWriter(initialCapacity = 256)
        message.writeStringIfNotEmpty(1, event.eventId)
        message.writePropertyMap(2, event.autoProperties)
        message.writePropertyMap(3, event.customProperties)
        message.writeStringIfNotEmpty(4, event.distinctId)
        message.writeStringIfNotEmpty(5, event.kind)
        message.writeMessage(6, timestamp(event.occurTimeMillis))
        message.writeStringIfNotEmpty(7, event.sessionId)
        return message
    }

    private fun ProtoWriter.writePropertyMap(field: Int, properties: Map<String, PropertyValue>) {
        for (key in properties.keys.sorted()) {
            val entry = ProtoWriter()
            entry.writeString(1, key)
            entry.writeMessage(2, propertyValue(properties.getValue(key)))
            writeMessage(field, entry)
        }
    }

    // A selected oneof member is written even when it holds the default value: without it the
    // PropertyValue is empty, which the server's oneof.required rule rejects for the whole batch.
    private fun propertyValue(value: PropertyValue): ProtoWriter {
        val message = ProtoWriter(initialCapacity = 16)
        when (value) {
            is PropertyValue.StringValue -> message.writeString(1, value.value)
            is PropertyValue.IntValue -> message.writeInt64(2, value.value)
            is PropertyValue.DoubleValue -> message.writeDouble(3, value.value)
            is PropertyValue.BoolValue -> message.writeBool(4, value.value)
            is PropertyValue.TimestampValue -> message.writeMessage(5, timestamp(value.epochMillis))
        }
        return message
    }

    private fun timestamp(epochMillis: Long): ProtoWriter {
        val message = ProtoWriter(initialCapacity = 16)
        val seconds = epochMillis.floorDiv(MILLIS_PER_SECOND)
        val nanos = (epochMillis.mod(MILLIS_PER_SECOND) * NANOS_PER_MILLI).toInt()
        if (seconds != 0L) message.writeInt64(1, seconds)
        if (nanos != 0) message.writeInt32(2, nanos)
        return message
    }

    private fun struct(fields: Map<String, JsonValue>): ProtoWriter {
        val message = ProtoWriter()
        for (key in fields.keys.sorted()) {
            val entry = ProtoWriter()
            entry.writeString(1, key)
            entry.writeMessage(2, value(fields.getValue(key)))
            message.writeMessage(1, entry)
        }
        return message
    }

    private fun value(value: JsonValue): ProtoWriter {
        val message = ProtoWriter(initialCapacity = 16)
        when (value) {
            JsonValue.Null -> message.writeInt32(1, 0)

            is JsonValue.NumberValue -> message.writeDouble(2, value.value)

            is JsonValue.StringValue -> message.writeString(3, value.value)

            is JsonValue.BoolValue -> message.writeBool(4, value.value)

            is JsonValue.ObjectValue -> message.writeMessage(5, struct(value.fields))

            is JsonValue.ArrayValue -> {
                val list = ProtoWriter()
                for (element in value.values) {
                    list.writeMessage(1, value(element))
                }
                message.writeMessage(6, list)
            }
        }
        return message
    }

    private fun ProtoWriter.writeStringIfNotEmpty(field: Int, value: String) {
        if (value.isNotEmpty()) writeString(field, value)
    }

    // Mirrors event() field by field without allocating; PugWireTest pins it to the encoder.
    private fun eventMessageSize(event: Event): Int {
        var size = 0
        if (event.eventId.isNotEmpty()) size += stringFieldSize(1, event.eventId)
        size += propertyMapSize(2, event.autoProperties)
        size += propertyMapSize(3, event.customProperties)
        if (event.distinctId.isNotEmpty()) size += stringFieldSize(4, event.distinctId)
        if (event.kind.isNotEmpty()) size += stringFieldSize(5, event.kind)
        size += lengthDelimitedSize(6, timestampSize(event.occurTimeMillis))
        if (event.sessionId.isNotEmpty()) size += stringFieldSize(7, event.sessionId)
        return size
    }

    private fun propertyMapSize(field: Int, properties: Map<String, PropertyValue>): Int {
        var size = 0
        for ((key, value) in properties) {
            size +=
                lengthDelimitedSize(field, stringFieldSize(1, key) + lengthDelimitedSize(2, propertyValueSize(value)))
        }
        return size
    }

    private fun propertyValueSize(value: PropertyValue): Int = when (value) {
        is PropertyValue.StringValue -> stringFieldSize(1, value.value)
        is PropertyValue.IntValue -> tagSize(2) + varintSize(value.value)
        is PropertyValue.DoubleValue -> tagSize(3) + 8
        is PropertyValue.BoolValue -> tagSize(4) + 1
        is PropertyValue.TimestampValue -> lengthDelimitedSize(5, timestampSize(value.epochMillis))
    }

    private fun timestampSize(epochMillis: Long): Int {
        val seconds = epochMillis.floorDiv(MILLIS_PER_SECOND)
        val nanos = epochMillis.mod(MILLIS_PER_SECOND) * NANOS_PER_MILLI
        var size = 0
        if (seconds != 0L) size += tagSize(1) + varintSize(seconds)
        if (nanos != 0L) size += tagSize(2) + varintSize(nanos)
        return size
    }

    private fun stringFieldSize(field: Int, value: String): Int = lengthDelimitedSize(field, value.utf8Length())

    private fun lengthDelimitedSize(field: Int, length: Int): Int =
        tagSize(field) + varintSize(length.toLong()) + length

    private fun tagSize(field: Int): Int = varintSize((field shl 3).toLong())

    private fun fieldKey(field: Int, wireType: Int): Int = (field shl 3) or wireType

    private const val UINT32_MASK = 0xFFFF_FFFFL
    private const val MILLIS_PER_SECOND = 1000L
    private const val NANOS_PER_MILLI = 1_000_000L
}
