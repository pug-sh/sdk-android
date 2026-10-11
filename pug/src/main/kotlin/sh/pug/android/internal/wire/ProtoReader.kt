package sh.pug.android.internal.wire

internal class ProtoDecodeException(message: String) : Exception(message)

internal class ProtoReader(
    private val bytes: ByteArray,
    private var position: Int = 0,
    private val limit: Int = bytes.size,
) {
    val isAtEnd: Boolean get() = position >= limit

    fun readTag(): Int {
        val tag = readVarint()
        if (tag ushr 32 != 0L || (tag ushr 3) == 0L) throw ProtoDecodeException("invalid tag $tag")
        return tag.toInt()
    }

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = readByte()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        throw ProtoDecodeException("varint longer than 10 bytes")
    }

    fun readString(): String {
        val length = readLength()
        val value = bytes.decodeToString(position, position + length)
        position += length
        return value
    }

    fun readMessage(): ProtoReader {
        val length = readLength()
        val message = ProtoReader(bytes, position, position + length)
        position += length
        return message
    }

    fun skip(wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint()
            WIRE_I64 -> advance(8)
            WIRE_LEN -> advance(readLength())
            WIRE_I32 -> advance(4)
            else -> throw ProtoDecodeException("unsupported wire type $wireType")
        }
    }

    private fun readLength(): Int {
        val length = readVarint()
        if (length < 0 || length > limit - position) {
            throw ProtoDecodeException("length $length exceeds remaining input")
        }
        return length.toInt()
    }

    private fun advance(count: Int) {
        if (count > limit - position) throw ProtoDecodeException("field runs past the end of input")
        position += count
    }

    private fun readByte(): Int {
        if (position >= limit) throw ProtoDecodeException("truncated input")
        return bytes[position++].toInt() and 0xFF
    }
}
