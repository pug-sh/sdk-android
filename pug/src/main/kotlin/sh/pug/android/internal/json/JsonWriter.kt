package sh.pug.android.internal.json

import sh.pug.android.internal.appendHex

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

    fun value(value: String): JsonWriter = scalar { string(value) }

    fun value(value: Long): JsonWriter = scalar { out.append(value) }

    fun value(value: Double): JsonWriter = scalar { out.append(value) }

    fun value(value: Boolean): JsonWriter = scalar { out.append(value) }

    fun nullValue(): JsonWriter = scalar { out.append("null") }

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

    private inline fun scalar(write: () -> Unit): JsonWriter {
        separate()
        write()
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
                else -> out.append("\\u00").appendHex(c.code)
            }
            start = i + 1
        }
        out.append(value, start, value.length)
        out.append('"')
    }
}
