package sh.pug.android.internal.event

import kotlin.test.Test
import kotlin.test.assertEquals

class EventSchemaTest {
    @Test
    fun `a duration is written in seconds with the fraction it needs`() {
        assertEquals(
            listOf("0s", "0.001s", "0.01s", "1.5s", "1.234s", "61s", "-1.5s", "-9223372036854775.808s"),
            listOf(0L, 1L, 10L, 1_500L, 1_234L, 61_000L, -1_500L, Long.MIN_VALUE).map(::durationText),
        )
    }
}
