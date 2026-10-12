package sh.pug.android.internal

private const val HEX_DIGITS = "0123456789abcdef"

/** Appends the low 8 bits of [byte] as two lowercase hex digits. */
internal fun StringBuilder.appendHex(byte: Int): StringBuilder =
    append(HEX_DIGITS[(byte ushr 4) and 0x0F]).append(HEX_DIGITS[byte and 0x0F])
