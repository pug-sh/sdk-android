package sh.pug.android.internal.wire

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProtoWriterTest {
    @Test
    fun `varints use the protobuf encoding`() {
        assertContentEquals(bytes(0x08, 0x00), write { writeInt64(1, 0) })
        assertContentEquals(bytes(0x08, 0x01), write { writeInt64(1, 1) })
        assertContentEquals(bytes(0x08, 0x7F), write { writeInt64(1, 127) })
        assertContentEquals(bytes(0x08, 0x80, 0x01), write { writeInt64(1, 128) })
        assertContentEquals(bytes(0x08, 0xAC, 0x02), write { writeInt64(1, 300) })
        assertContentEquals(
            bytes(0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
            write {
                writeInt64(1, -1)
            },
        )
    }

    @Test
    fun `negative int32 sign-extends to ten bytes`() {
        assertContentEquals(
            bytes(0x10, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
            write {
                writeInt32(2, -1)
            },
        )
    }

    @Test
    fun `tags carry field number and wire type`() {
        assertContentEquals(bytes(0xF8, 0x07, 0x01), write { writeBool(127, true) })
        assertContentEquals(bytes(0x80, 0x08, 0x00), write { writeBool(128, false) })
    }

    @Test
    fun `doubles are little-endian fixed64`() {
        assertContentEquals(bytes(0x19, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF8, 0x3F), write { writeDouble(3, 1.5) })
    }

    @Test
    fun `strings are length-prefixed utf-8`() {
        assertContentEquals(bytes(0x0A, 0x03, 0xE2, 0x82, 0xAC), write { writeString(1, "€") })
        assertContentEquals(bytes(0x0A, 0x00), write { writeString(1, "") })
    }

    @Test
    fun `unpaired surrogates encode as valid utf-8`() {
        val encoded = write { writeString(1, "a\uD800b") }

        val decoded = ProtoReader(encoded).apply { readTag() }.readString()
        assertEquals("a?b", decoded)
    }

    @Test
    fun `nested messages are length-prefixed`() {
        val inner = ProtoWriter().apply { writeInt64(1, 150) }

        assertContentEquals(bytes(0x1A, 0x03, 0x08, 0x96, 0x01), write { writeMessage(3, inner) })
    }

    @Test
    fun `buffer grows past its initial capacity`() {
        val long = "x".repeat(10_000)

        val encoded = write { writeString(1, long) }

        assertEquals(10_003, encoded.size)
        assertTrue(encoded.drop(3).all { it == 'x'.code.toByte() })
    }

    @Test
    fun `reader round-trips what the writer wrote`() {
        val encoded =
            write {
                writeInt64(1, Long.MIN_VALUE)
                writeString(2, "pug")
                writeDouble(3, 2.5)
            }

        val reader = ProtoReader(encoded)
        assertEquals((1 shl 3) or WIRE_VARINT, reader.readTag())
        assertEquals(Long.MIN_VALUE, reader.readVarint())
        assertEquals((2 shl 3) or WIRE_LEN, reader.readTag())
        assertEquals("pug", reader.readString())
        assertEquals((3 shl 3) or WIRE_I64, reader.readTag())
        reader.skip(WIRE_I64)
        assertTrue(reader.isAtEnd)
    }

    @Test
    fun `reader rejects malformed input`() {
        assertFailsWith<ProtoDecodeException> { ProtoReader(bytes(0x80)).readVarint() }
        assertFailsWith<ProtoDecodeException> { ProtoReader(ByteArray(11) { 0x80.toByte() }).readVarint() }
        assertFailsWith<ProtoDecodeException> { ProtoReader(bytes(0x05, 0x61)).readString() }
        assertFailsWith<ProtoDecodeException> { ProtoReader(bytes(0x00)).readTag() }
        assertFailsWith<ProtoDecodeException> { ProtoReader(bytes(0x01, 0x02)).skip(WIRE_I64) }
        assertFailsWith<ProtoDecodeException> { ProtoReader(bytes(0x01)).skip(3) }
    }

    private fun write(block: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(block).toByteArray()

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
