package sh.pug.android.internal

import java.security.SecureRandom
import java.util.Random

internal fun interface IdGenerator {
    fun nextId(): String
}

/** RFC 9562 version 7: a 48-bit millisecond timestamp followed by 74 random bits. */
internal class UuidV7Generator(private val clock: Clock = Clock.SYSTEM, private val random: Random = SecureRandom()) :
    IdGenerator {
    override fun nextId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val millis = clock.nowMillis()
        for (i in 0 until 6) {
            bytes[i] = (millis ushr (8 * (5 - i))).toByte()
        }
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        val id = StringBuilder(36)
        for (i in bytes.indices) {
            if (i == 4 || i == 6 || i == 8 || i == 10) id.append('-')
            id.appendHex(bytes[i].toInt())
        }
        return id.toString()
    }
}

/** The server's `string.uuid` rule: 8-4-4-4-12 hex digits, either case. */
internal fun String.isUuid(): Boolean {
    if (length != 36) return false
    for (i in indices) {
        val c = this[i]
        val valid =
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                c == '-'
            } else {
                c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
            }
        if (!valid) return false
    }
    return true
}
