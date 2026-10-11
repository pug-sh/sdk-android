package sh.pug.android.internal.json

/** Appends JSON text; callers pass finite doubles only. */
internal class JsonWriter(capacity: Int = 64) {
    private val out = StringBuilder(capacity)
    private var needsComma = false

    fun beginObject(): JsonWriter = open('{')

    fun endObject(): JsonWriter = close('}')

    fun beginArray(): JsonWriter = open('[')

    fun endArray(): JsonWriter = close(']')

    fun name(name: String): JsonWriter {
        separate()
        string(name)
        out.append(':')
        needsComma = false
        return this
    }

    fun value(value: String): JsonWriter {
        separate()
        string(value)
        needsComma = true
        return this
    }

    fun value(value: Long): JsonWriter {
        separate()
        out.append(value)
        needsComma = true
        return this
    }

    fun value(value: Double): JsonWriter {
        separate()
        out.append(value)
        needsComma = true
        return this
    }

    fun value(value: Boolean): JsonWriter {
        separate()
        out.append(value)
        needsComma = true
        return this
    }

    fun nullValue(): JsonWriter {
        separate()
        out.append("null")
        needsComma = true
        return this
    }

    override fun toString(): String = out.toString()

    private fun open(bracket: Char): JsonWriter {
        separate()
        out.append(bracket)
        needsComma = false
        return this
    }

    private fun close(bracket: Char): JsonWriter {
        out.append(bracket)
        needsComma = true
        return this
    }

    private fun separate() {
        if (needsComma) out.append(',')
    }

    private fun string(value: String) {
        out.append('"')
        var start = 0
        for (i in value.indices) {
            val c = value[i]
            if (c != '"' && c != '\\' && c >= ' ') continue
            out.append(value, start, i)
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0x0F])
            }
            start = i + 1
        }
        out.append(value, start, value.length)
        out.append('"')
    }

    private companion object {
        const val HEX = "0123456789abcdef"
    }
}
