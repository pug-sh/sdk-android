package sh.pug.android.internal.wire

internal const val WIRE_VARINT = 0
internal const val WIRE_I64 = 1
internal const val WIRE_LEN = 2
internal const val WIRE_I32 = 5

internal fun varintSize(value: Long): Int {
    var size = 1
    var remaining = value
    while (remaining and 0x7FL.inv() != 0L) {
        size++
        remaining = remaining ushr 7
    }
    return size
}

internal class ProtoWriter(initialCapacity: Int = 64) {
    private var buffer = ByteArray(initialCapacity.coerceAtLeast(16))

    var size: Int = 0
        private set

    fun toByteArray(): ByteArray = buffer.copyOf(size)

    fun writeString(field: Int, value: String) {
        val bytes = value.encodeToByteArray()
        writeTag(field, WIRE_LEN)
        writeVarint(bytes.size.toLong())
        writeRaw(bytes, bytes.size)
    }

    fun writeInt64(field: Int, value: Long) {
        writeTag(field, WIRE_VARINT)
        writeVarint(value)
    }

    /** Negative values sign-extend to a 10-byte varint, as protobuf requires for int32 and enums. */
    fun writeInt32(field: Int, value: Int) {
        writeTag(field, WIRE_VARINT)
        writeVarint(value.toLong())
    }

    fun writeBool(field: Int, value: Boolean) {
        writeTag(field, WIRE_VARINT)
        writeVarint(if (value) 1L else 0L)
    }

    fun writeDouble(field: Int, value: Double) {
        writeTag(field, WIRE_I64)
        writeFixed64(value.toRawBits())
    }

    fun writeMessage(field: Int, message: ProtoWriter) {
        writeTag(field, WIRE_LEN)
        writeVarint(message.size.toLong())
        writeRaw(message.buffer, message.size)
    }

    private fun writeTag(field: Int, wireType: Int) {
        writeVarint(((field shl 3) or wireType).toLong())
    }

    private fun writeVarint(value: Long) {
        ensureCapacity(MAX_VARINT_BYTES)
        var remaining = value
        while (remaining and 0x7FL.inv() != 0L) {
            buffer[size++] = ((remaining and 0x7F) or 0x80).toByte()
            remaining = remaining ushr 7
        }
        buffer[size++] = remaining.toByte()
    }

    private fun writeFixed64(value: Long) {
        ensureCapacity(8)
        for (i in 0 until 8) {
            buffer[size++] = (value ushr (8 * i)).toByte()
        }
    }

    private fun writeRaw(bytes: ByteArray, length: Int) {
        ensureCapacity(length)
        System.arraycopy(bytes, 0, buffer, size, length)
        size += length
    }

    private fun ensureCapacity(extra: Int) {
        val required = size + extra
        if (required <= buffer.size) return
        buffer = buffer.copyOf(maxOf(buffer.size * 2, required))
    }

    private companion object {
        const val MAX_VARINT_BYTES = 10
    }
}
