package sh.pug.android.internal

/** Bytes of `encodeToByteArray()`, which writes an unpaired surrogate as a one-byte '?'. */
internal fun String.utf8Length(): Int {
    var bytes = 0
    var i = 0
    while (i < length) {
        val width = utf8Width(i)
        bytes += width.bytes
        i += width.chars
    }
    return bytes
}

/** The longest prefix that fits in [maxBytes] of UTF-8 without splitting a code point. */
internal fun String.truncateUtf8(maxBytes: Int): String {
    // Three bytes per UTF-16 unit is the worst case (a surrogate pair is four bytes for two units).
    if (length.toLong() * 3 <= maxBytes) return this
    var bytes = 0
    var i = 0
    while (i < length) {
        val width = utf8Width(i)
        if (bytes + width.bytes > maxBytes) return substring(0, i)
        bytes += width.bytes
        i += width.chars
    }
    return this
}

private enum class Utf8Width(val chars: Int, val bytes: Int) {
    ONE(1, 1),
    TWO(1, 2),
    THREE(1, 3),
    PAIR(2, 4),
}

private fun String.utf8Width(index: Int): Utf8Width {
    val c = this[index]
    return when {
        c.code < 0x80 -> Utf8Width.ONE
        c.code < 0x800 -> Utf8Width.TWO
        c.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> Utf8Width.PAIR
        c.isSurrogate() -> Utf8Width.ONE
        else -> Utf8Width.THREE
    }
}
