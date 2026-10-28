package sh.pug.android.internal.event

import com.google.protobuf.Descriptors.FieldDescriptor.Type
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import sh.pug.android.Events

class WellKnownEventsTest {
    private val events = EventProtos.android

    @Test
    fun `the protos are read with their options`() {
        val kinds = events.map { it.kind }
        assertTrue("screen_view" in kinds && "add_to_cart" in kinds)
        assertFalse("page_view" in kinds, "page_view is for the web only")
        assertEquals(
            listOf(true, false),
            events.single { it.kind == "screen_view" }.properties.map { it.required },
        )
    }

    @Test
    fun `the registry has every Android event with its properties`() {
        assertEquals(events.map { it.kind }, WellKnownEvents.byKind.keys.toList())
        for (event in events) {
            val schema = WellKnownEvents.byKind.getValue(event.kind)
            assertEquals("track.${event.method}", schema.label)
            assertEquals(
                event.properties.map { Triple(it.name, type(it), it.required) },
                schema.fields.map { Triple(it.name, it.type, it.required) },
                event.kind,
            )
        }
    }

    @Test
    fun `Events has the kind of every Android event`() {
        val constants =
            Events::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .associate { it.name to it.get(null) }

        assertEquals(events.associate { it.kind.uppercase() to it.kind }, constants)
    }

    private fun type(property: EventProtos.Property): FieldType = when (property.descriptor.type) {
        Type.STRING -> FieldType.STRING

        Type.INT32, Type.SINT32, Type.SFIXED32, Type.INT64, Type.SINT64, Type.SFIXED64, Type.UINT32, Type.FIXED32 ->
            FieldType.INT

        Type.DOUBLE, Type.FLOAT -> FieldType.DOUBLE

        Type.BOOL -> FieldType.BOOLEAN

        else -> if (property.isDuration) FieldType.DURATION else error("unexpected type of ${property.name}")
    }
}
