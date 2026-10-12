package sh.pug.android.internal.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.json.JSONObject
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue

class EventJsonTest {
    @Test
    fun `events round-trip with every value type`() {
        val event =
            event(
                custom =
                    mapOf(
                        "string" to PropertyValue.StringValue("quote\" slash\\ newline\n 🌍"),
                        "empty" to PropertyValue.StringValue(""),
                        "min" to PropertyValue.IntValue(Long.MIN_VALUE),
                        "max" to PropertyValue.IntValue(Long.MAX_VALUE),
                        "zero" to PropertyValue.IntValue(0),
                        "double" to PropertyValue.DoubleValue(19.99),
                        "tiny" to PropertyValue.DoubleValue(Double.MIN_VALUE),
                        "huge" to PropertyValue.DoubleValue(-1.5e300),
                        "whole" to PropertyValue.DoubleValue(2.0),
                        "false" to PropertyValue.BoolValue(false),
                        "before1970" to PropertyValue.TimestampValue(-1L),
                        "ü-key \"quoted\"" to PropertyValue.IntValue(1),
                    ),
                auto = mapOf("\$platform" to PropertyValue.StringValue("android")),
            )

        val decoded = EventJson.decode(JSONObject(EventJson.encode(event)))

        assertEquals(event, decoded)
    }

    @Test
    fun `the stored shape is the family's domain shape`() {
        val event =
            event(
                custom = mapOf("n" to PropertyValue.IntValue(1)),
                auto = mapOf("\$at" to PropertyValue.TimestampValue(5)),
            )

        assertEquals(
            """{"eventId":"$EVENT_ID","kind":"add_to_cart","sessionId":"$SESSION_ID","distinctId":"anon-1",""" +
                """"occurTime":1760000000123,"customProperties":{"n":{"intValue":1}},""" +
                """"autoProperties":{"${'$'}at":{"timestampValue":5}}}""",
            EventJson.encode(event),
        )
    }

    @Test
    fun `missing property maps fail to decode`() {
        val json = JSONObject(EventJson.encode(event()))
        json.remove("autoProperties")

        assertNull(EventJson.decode(json))
    }

    @Test
    fun `events the server would reject fail to decode`() {
        val valid = JSONObject(EventJson.encode(event(custom = mapOf("a" to PropertyValue.IntValue(1)))))
        val broken =
            listOf<(JSONObject) -> Unit>(
                { it.remove("eventId") },
                { it.put("eventId", "not-a-uuid") },
                { it.put("sessionId", "") },
                { it.put("distinctId", "") },
                { it.put("kind", "has space") },
                { it.put("occurTime", "soon") },
                { it.put("occurTime", -1) },
                { it.put("occurTime", 253_402_300_800_000L) },
                { it.getJSONObject("customProperties").put("a", JSONObject().put("stringValue", "é".repeat(513))) },
                {
                    it.getJSONObject("customProperties")
                        .put("a", JSONObject().put("timestampValue", 253_402_300_800_000L))
                },
                { it.getJSONObject("customProperties").put("\$reserved", JSONObject("""{"intValue":1}""")) },
                { it.getJSONObject("autoProperties").put("plain", JSONObject("""{"intValue":1}""")) },
                { it.getJSONObject("customProperties").put("a", JSONObject("""{"floatValue":1}""")) },
                { it.getJSONObject("customProperties").put("a", JSONObject("""{"intValue":1,"boolValue":true}""")) },
                { it.getJSONObject("customProperties").put("a", JSONObject("""{"intValue":"1"}""")) },
                { it.getJSONObject("customProperties").put("a", JSONObject("{}")) },
                { it.getJSONObject("customProperties").put("a", 1) },
                { it.put("customProperties", JSONObject.NULL) },
            )

        for ((index, breakIt) in broken.withIndex()) {
            val json = JSONObject(valid.toString()).also(breakIt)
            assertNull(EventJson.decode(json), "case $index: $json")
        }
    }

    private fun event(custom: Map<String, PropertyValue> = emptyMap(), auto: Map<String, PropertyValue> = emptyMap()) =
        Event(
            eventId = EVENT_ID,
            kind = "add_to_cart",
            distinctId = "anon-1",
            sessionId = SESSION_ID,
            occurTimeMillis = 1_760_000_000_123L,
            autoProperties = auto,
            customProperties = custom,
        )

    private companion object {
        const val EVENT_ID = "0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0f"
        const val SESSION_ID = "0192f3a4-0000-7000-8000-000000000002"
    }
}
