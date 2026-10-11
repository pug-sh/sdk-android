package sh.pug.android.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class Utf8Test {
    private val samples =
        listOf(
            "",
            "ascii",
            "héllo wörld",
            "€ 日本",
            "🌍 emoji",
            "lone \uD800 high",
            "lone \uDC00 low",
            "reversed \uDC00\uD800 pair",
            "ends high \uD83C",
        )

    @Test
    fun `utf8Length matches the encoder byte for byte`() {
        for (sample in samples) {
            assertEquals(sample.encodeToByteArray().size, sample.utf8Length(), sample)
        }
    }

    @Test
    fun `strings within the limit are returned as they are`() {
        val ascii = "a".repeat(1024)
        val twoByte = "é".repeat(512)

        assertSame(ascii, ascii.truncateUtf8(1024))
        assertSame(twoByte, twoByte.truncateUtf8(1024))
    }

    @Test
    fun `truncation stops at the byte limit`() {
        assertEquals("a".repeat(1024), "a".repeat(1025).truncateUtf8(1024))
    }

    @Test
    fun `truncation never splits a code point`() {
        // é is two bytes: 512 of them fill 1024 bytes exactly, and a 513th would not fit.
        assertEquals("é".repeat(512), "é".repeat(513).truncateUtf8(1024))
        // € is three bytes: 341 fill 1023, so the 342nd is cut whole.
        assertEquals("€".repeat(341), "€".repeat(400).truncateUtf8(1024))
        // A surrogate pair is four bytes and two chars; half a pair is never kept.
        assertEquals("🌍".repeat(256), "🌍".repeat(300).truncateUtf8(1024))
        assertEquals("a" + "🌍".repeat(255), ("a" + "🌍".repeat(300)).truncateUtf8(1024))
    }

    @Test
    fun `truncated strings fit the limit once encoded`() {
        val long = samples.joinToString("|").repeat(100)

        for (limit in listOf(1, 2, 3, 4, 5, 100, 1024)) {
            val truncated = long.truncateUtf8(limit)
            assertTrue(truncated.encodeToByteArray().size <= limit, "limit $limit")
            assertEquals(truncated.utf8Length(), truncated.encodeToByteArray().size)
        }
    }
}
