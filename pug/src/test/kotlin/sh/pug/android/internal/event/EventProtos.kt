package sh.pug.android.internal.event

import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.UnknownFieldSet
import sh.pug.android.internal.wire.ProtoDescriptors

/**
 * The well-known events for Android, read from pug.binpb with protobuf-java rather than tools/gen-track, so
 * the generated sources can be checked against the protos. The custom options arrive as unknown fields.
 */
internal object EventProtos {
    private const val KIND = 50000
    private const val PLATFORMS = 50002
    private const val PLATFORM_ANDROID = 3L
    private const val FIELD_RULES = 1159
    private const val REQUIRED = 25

    class Property(val name: String, val descriptor: FieldDescriptor, val required: Boolean) {
        val isDuration: Boolean get() = descriptor.messageTypeOrNull()?.fullName == "google.protobuf.Duration"
    }

    class Event(val kind: String, val properties: List<Property>) {
        val method: String =
            kind.split('_').mapIndexed { i, part -> if (i == 0) part else part.replaceFirstChar(Char::uppercaseChar) }
                .joinToString("")

        /** The order of the typed method's parameters: required properties first. */
        val parameters: List<Property> = properties.filter { it.required } + properties.filterNot { it.required }
    }

    val android: List<Event> by lazy { read() }

    private fun read(): List<Event> = ProtoDescriptors.messages("common.events.v1").mapNotNull { message ->
        val options = message.toProto().options.unknownFields
        val kind = options.getField(KIND).lengthDelimitedList.singleOrNull()?.toStringUtf8() ?: return@mapNotNull null
        val platforms = options.getField(PLATFORMS).let { it.varintList + it.lengthDelimitedList.flatMap(::varints) }
        if (platforms.isNotEmpty() && PLATFORM_ANDROID !in platforms) return@mapNotNull null
        Event(kind, message.fields.map { Property(it.jsonName, it, isRequired(it)) })
    }.sortedBy { it.kind }

    private fun isRequired(field: FieldDescriptor): Boolean {
        val rules = field.toProto().options.unknownFields.getField(FIELD_RULES).lengthDelimitedList.singleOrNull()
        return rules != null && UnknownFieldSet.parseFrom(rules).getField(REQUIRED).varintList == listOf(1L)
    }

    // Editions pack repeated enums, options included.
    private fun varints(packed: ByteString): List<Long> {
        val input = packed.newCodedInput()
        return buildList { while (!input.isAtEnd) add(input.readInt64()) }
    }

    private fun FieldDescriptor.messageTypeOrNull() = if (type == FieldDescriptor.Type.MESSAGE) messageType else null
}
