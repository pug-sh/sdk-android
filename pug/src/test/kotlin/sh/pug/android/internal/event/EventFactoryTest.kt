package sh.pug.android.internal.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sh.pug.android.SDK_VERSION
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue
import sh.pug.android.internal.wire.PugWire

class EventFactoryTest {
    private val logger = RecordingLogger()
    private val factory =
        EventFactory("proj_1", logger.safe(), PropertyMapper(logger.safe(), sdkInt = 26), SequentialIds())

    @Test
    fun `kinds follow the server's pattern`() {
        for (kind in listOf("app_open", "Checkout.Started-v2", "a", "0")) {
            assertTrue(factory.acceptsKind(kind), kind)
        }
        assertEquals(emptyList(), logger.entries)

        for (kind in listOf("add to cart", "a/b", "ümlaut", "emoji🌍", "tab\t")) {
            assertFalse(factory.acceptsKind(kind), kind)
        }
        assertEquals(
            "Event \"add to cart\" was dropped: a kind may only contain letters, digits, '_', '.' and '-'",
            logger.warnings.first(),
        )
        assertEquals(5, logger.warnings.size)
    }

    @Test
    fun `blank kinds are dropped`() {
        assertFalse(factory.acceptsKind(""))
        assertFalse(factory.acceptsKind("  "))

        assertEquals(List(2) { "An event with a blank kind was dropped" }, logger.warnings)
    }

    @Test
    fun `an event happens at its timestamp, or at the call when it has none`() {
        assertEquals(500L, factory.occurTime("kind", timestamp = null, callTimeMillis = 500L))
        assertEquals(0L, factory.occurTime("kind", timestamp = 0L, callTimeMillis = 500L))
        assertEquals(
            MAX_TIMESTAMP_MILLIS,
            factory.occurTime("kind", timestamp = MAX_TIMESTAMP_MILLIS, callTimeMillis = 500L),
        )
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `an unusable timestamp is replaced by the time of the call`() {
        assertEquals(500L, factory.occurTime("kind", timestamp = -1L, callTimeMillis = 500L))
        assertEquals(500L, factory.occurTime("kind", timestamp = MAX_TIMESTAMP_MILLIS + 1, callTimeMillis = 500L))

        assertEquals(
            listOf(
                "Event \"kind\" has a timestamp outside 1970 to 9999 (-1); using the time of the call",
                "Event \"kind\" has a timestamp outside 1970 to 9999 (253402300800000); using the time of the call",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `events carry identity, time, properties and the SDK's own auto-properties`() {
        val event =
            factory.create(
                kind = "add_to_cart",
                customProperties =
                    mapOf("productId" to PropertyValue.StringValue("p1"), "price" to PropertyValue.DoubleValue(9.99)),
                autoProperties = mapOf("\$os" to "Android", "\$platform" to "spoofed", "\$projectId" to "other"),
                distinctId = "anon-1",
                sessionId = "00000000-0000-7000-8000-0000000000aa",
                occurTimeMillis = 1_760_000_000_000L,
            )

        assertEquals(
            Event(
                eventId = "00000000-0000-7000-8000-000000000001",
                kind = "add_to_cart",
                distinctId = "anon-1",
                sessionId = "00000000-0000-7000-8000-0000000000aa",
                occurTimeMillis = 1_760_000_000_000L,
                autoProperties =
                    mapOf(
                        "\$os" to PropertyValue.StringValue("Android"),
                        "\$platform" to PropertyValue.StringValue("android"),
                        "\$projectId" to PropertyValue.StringValue("proj_1"),
                        "\$sdkVersion" to PropertyValue.StringValue(SDK_VERSION),
                    ),
                customProperties =
                    mapOf("productId" to PropertyValue.StringValue("p1"), "price" to PropertyValue.DoubleValue(9.99)),
            ),
            event,
        )
    }

    @Test
    fun `each event gets a new id`() {
        val first = assertNotNull(create())
        val second = assertNotNull(create())

        assertEquals("00000000-0000-7000-8000-000000000001", first.eventId)
        assertEquals("00000000-0000-7000-8000-000000000002", second.eventId)
    }

    @Test
    fun `an event too large for any request is dropped`() {
        val value = PropertyValue.StringValue("x".repeat(MAX_STRING_BYTES))
        val tooMany = (0 until 4_100).associate { "p$it" to value }

        assertNull(create(tooMany))

        val error = logger.errors.single()
        assertTrue(error.startsWith("Event \"kind\" was dropped: it encodes to "), error)
        assertTrue(error.endsWith(" bytes, over the 4194304-byte request limit"), error)
    }

    @Test
    fun `an event just under the request limit is kept`() {
        val value = PropertyValue.StringValue("x".repeat(MAX_STRING_BYTES))
        val many = (0 until 4_000).associate { "p$it" to value }

        val event = assertNotNull(create(many))

        assertTrue(PugWire.eventSize(event) <= MAX_BATCH_BYTES)
    }

    private fun create(properties: Map<String, PropertyValue> = emptyMap()) = factory.create(
        kind = "kind",
        customProperties = properties,
        autoProperties = emptyMap(),
        distinctId = "anon-1",
        sessionId = "00000000-0000-7000-8000-0000000000aa",
        occurTimeMillis = 0L,
    )
}
