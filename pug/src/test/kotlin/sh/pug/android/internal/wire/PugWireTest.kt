package sh.pug.android.internal.wire

import com.google.protobuf.ByteString
import com.google.protobuf.DynamicMessage
import com.google.protobuf.UnknownFieldSet
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PugWireTest {
    @Test
    fun `batch with every property value type matches protobuf-java byte for byte`() {
        val events = listOf(fullEvent(), minimalEvent())

        val encoded = PugWire.encodeBatchCreateRequest(events)

        val reference = ReferenceMessages.batch(events)
        assertContentEquals(reference.toByteArray(), encoded)
        assertEquals(reference, DynamicMessage.parseFrom(ReferenceMessages.batchCreateRequest, encoded))
    }

    @Test
    fun `oneof members holding default values are still written`() {
        val defaults =
            mapOf(
                "zero" to PropertyValue.IntValue(0),
                "zeroDouble" to PropertyValue.DoubleValue(0.0),
                "false" to PropertyValue.BoolValue(false),
                "empty" to PropertyValue.StringValue(""),
                "epoch" to PropertyValue.TimestampValue(0),
            )
        val event = minimalEvent().copy(customProperties = defaults)

        val parsed = DynamicMessage.parseFrom(
            ReferenceMessages.batchCreateRequest,
            PugWire.encodeBatchCreateRequest(listOf(event)),
        )

        val customProperties = parsed.repeated("events").single().repeated("custom_properties")
        assertEquals(defaults.size, customProperties.size)
        for (entry in customProperties) {
            val value = entry.getField(entry.descriptorForType.findFieldByName("value")) as DynamicMessage
            assertEquals(
                1,
                value.allFields.size,
                "PropertyValue for ${entry.getField(
                    entry.descriptorForType.findFieldByName("key"),
                )} has no oneof member set",
            )
        }
    }

    @Test
    fun `empty batch encodes to an empty message`() {
        assertContentEquals(ByteArray(0), PugWire.encodeBatchCreateRequest(emptyList()))
    }

    @Test
    fun `event sizes add up to the encoded batch`() {
        val events =
            listOf(
                fullEvent(),
                minimalEvent(),
                minimalEvent().copy(eventId = "", distinctId = "", sessionId = "", kind = ""),
                minimalEvent().copy(
                    occurTimeMillis = -1L,
                    customProperties =
                        mapOf(
                            "lone \uD800" to PropertyValue.StringValue("\uDC00 ends high \uD83C"),
                            "long" to PropertyValue.StringValue("x".repeat(200)),
                            "longer" to PropertyValue.StringValue("y".repeat(20_000)),
                            "wholeSecond" to PropertyValue.TimestampValue(-1_000L),
                            "epoch" to PropertyValue.TimestampValue(0L),
                            "negative" to PropertyValue.IntValue(-1),
                        ),
                ),
            )

        for (event in events) {
            assertEquals(PugWire.encodeBatchCreateRequest(listOf(event)).size, PugWire.eventSize(event), event.kind)
        }
        assertEquals(PugWire.encodeBatchCreateRequest(events).size, events.sumOf { PugWire.eventSize(it) })
    }

    @Test
    fun `identify with nested traits matches protobuf-java byte for byte`() {
        val request =
            IdentifyRequest(
                externalId = "user-42",
                anonymousId = "anon-0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0f",
                traits =
                    mapOf(
                        "plan" to JsonValue.StringValue("pro"),
                        "seats" to JsonValue.NumberValue(3.0),
                        "score" to JsonValue.NumberValue(-0.5),
                        "active" to JsonValue.BoolValue(true),
                        "trial" to JsonValue.BoolValue(false),
                        "deleted" to JsonValue.Null,
                        "address" to
                            JsonValue.ObjectValue(
                                mapOf(
                                    "city" to JsonValue.StringValue("Zürich"),
                                    "zip" to JsonValue.NumberValue(8001.0),
                                ),
                            ),
                        "tags" to
                            JsonValue.ArrayValue(
                                listOf(
                                    JsonValue.StringValue("a"),
                                    JsonValue.NumberValue(0.0),
                                    JsonValue.Null,
                                    JsonValue.BoolValue(false),
                                    JsonValue.ArrayValue(emptyList()),
                                    JsonValue.ObjectValue(emptyMap()),
                                ),
                            ),
                    ),
            )

        val encoded = PugWire.encodeIdentifyRequest(request)

        val reference = ReferenceMessages.identify(request)
        assertContentEquals(reference.toByteArray(), encoded)
        assertEquals(reference, DynamicMessage.parseFrom(ReferenceMessages.identifyRequest, encoded))
    }

    @Test
    fun `identify omits traits and anonymous id when absent`() {
        val request = IdentifyRequest(externalId = "user-42", traits = emptyMap(), anonymousId = null)

        val encoded = PugWire.encodeIdentifyRequest(request)

        assertContentEquals(ReferenceMessages.identify(request).toByteArray(), encoded)
        val parsed = DynamicMessage.parseFrom(ReferenceMessages.identifyRequest, encoded)
        assertEquals(setOf("external_id"), parsed.allFields.keys.map { it.name }.toSet())
    }

    @Test
    fun `decodes a batch response and skips unknown fields`() {
        val unknown =
            UnknownFieldSet
                .newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(5).build())
                .addField(
                    100,
                    UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.copyFromUtf8("future")).build(),
                )
                .addField(101, UnknownFieldSet.Field.newBuilder().addFixed32(7).addFixed64(9).build())
                .build()
        val response =
            ReferenceMessages
                .batchResponse(
                    accepted = 7,
                    dropped = 3,
                    droppedByReason = mapOf(
                        "salt_unavailable" to 2,
                        "day_out_of_range" to 1,
                    ),
                )
                .toBuilder()
                .setUnknownFields(unknown)
                .build()

        val decoded = PugWire.decodeBatchCreateResponse(response.toByteArray())

        assertEquals(BatchCreateResponse(7, 3, mapOf("salt_unavailable" to 2L, "day_out_of_range" to 1L)), decoded)
    }

    @Test
    fun `decodes uint32 counts above Int MAX_VALUE`() {
        val response = ReferenceMessages.batchResponse(accepted = -1, dropped = 0, droppedByReason = emptyMap())

        assertEquals(4_294_967_295L, PugWire.decodeBatchCreateResponse(response.toByteArray()).accepted)
    }

    @Test
    fun `an empty body decodes as nothing accepted`() {
        assertEquals(BatchCreateResponse(0, 0, emptyMap()), PugWire.decodeBatchCreateResponse(ByteArray(0)))
    }

    @Test
    fun `malformed responses fail to decode`() {
        val truncated = ReferenceMessages.batchResponse(1, 1, mapOf("salt_unavailable" to 1)).toByteArray().let {
            it.copyOf(it.size - 1)
        }

        assertFailsWith<ProtoDecodeException> { PugWire.decodeBatchCreateResponse(truncated) }
        assertFailsWith<ProtoDecodeException> { PugWire.decodeBatchCreateResponse(byteArrayOf(0x08)) }
        assertFailsWith<ProtoDecodeException> { PugWire.decodeBatchCreateResponse(byteArrayOf(0x1A, 0x7F)) }
        assertFailsWith<ProtoDecodeException> { PugWire.decodeBatchCreateResponse(byteArrayOf(0x0B)) }
    }

    private fun fullEvent() = Event(
        eventId = "0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0f",
        kind = "add_to_cart",
        distinctId = "anon-0192f3a4-0000-7000-8000-000000000001",
        sessionId = "0192f3a4-0000-7000-8000-000000000002",
        occurTimeMillis = 1_760_000_000_123L,
        autoProperties =
            mapOf(
                "\$platform" to PropertyValue.StringValue("android"),
                "\$screenWidth" to PropertyValue.IntValue(411),
                "\$screenScale" to PropertyValue.DoubleValue(2.625),
                "\$mobile" to PropertyValue.BoolValue(true),
            ),
        customProperties =
            mapOf(
                "productId" to PropertyValue.StringValue("sku-1"),
                "name" to PropertyValue.StringValue("héllo wörld 🌍 日本"),
                "quantity" to PropertyValue.IntValue(3),
                "negative" to PropertyValue.IntValue(-42),
                "min" to PropertyValue.IntValue(Long.MIN_VALUE),
                "max" to PropertyValue.IntValue(Long.MAX_VALUE),
                "price" to PropertyValue.DoubleValue(19.99),
                "negativeZero" to PropertyValue.DoubleValue(-0.0),
                "tiny" to PropertyValue.DoubleValue(Double.MIN_VALUE),
                "gift" to PropertyValue.BoolValue(false),
                "addedAt" to PropertyValue.TimestampValue(1_760_000_000_999L),
                "before1970" to PropertyValue.TimestampValue(-1L),
                "wholeSecond" to PropertyValue.TimestampValue(1_000L),
                "ü-key" to PropertyValue.StringValue("unicode key"),
                "Z-upper" to PropertyValue.StringValue("sorts before lowercase"),
            ),
    )

    private fun minimalEvent() = Event(
        eventId = "0192f3a4-1b2c-7d3e-8f40-000000000000",
        kind = "app_open",
        distinctId = "user-42",
        sessionId = "0192f3a4-0000-7000-8000-000000000003",
        occurTimeMillis = 0L,
        autoProperties = emptyMap(),
        customProperties = emptyMap(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun DynamicMessage.repeated(name: String): List<DynamicMessage> =
        getField(descriptorForType.findFieldByName(name)) as List<DynamicMessage>
}
