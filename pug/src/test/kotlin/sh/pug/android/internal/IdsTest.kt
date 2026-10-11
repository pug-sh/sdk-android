package sh.pug.android.internal

import java.util.Random
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdsTest {
    @Test
    fun `ids are version 7 uuids led by the clock's milliseconds`() {
        val clock = FakeClock(now = 0x0192_F3A4_1B2CL)
        val generator = UuidV7Generator(clock, Random(42))

        val id = generator.nextId()

        assertTrue(id.isUuid())
        assertTrue(id.startsWith("0192f3a4-1b2c-7"), id)
        val uuid = UUID.fromString(id)
        assertEquals(7, uuid.version())
        assertEquals(2, uuid.variant())
        assertEquals(id, id.lowercase())
    }

    @Test
    fun `ids differ within one millisecond`() {
        val generator = UuidV7Generator(FakeClock())

        val ids = List(1_000) { generator.nextId() }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `isUuid follows the server's string uuid rule`() {
        assertTrue("0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0f".isUuid())
        assertTrue("0192F3A4-1B2C-7D3E-8F40-5A6B7C8D9E0F".isUuid())
        assertFalse("".isUuid())
        assertFalse("0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0".isUuid())
        assertFalse("0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0ff".isUuid())
        assertFalse("0192f3a41b2c-7d3e-8f40-5a6b7c8d9e0f-".isUuid())
        assertFalse("0192f3a4-1b2c-7d3e-8f40-5a6b7c8d9e0g".isUuid())
        assertFalse("anon-0192f3a4-1b2c-7d3e-8f40-5a6b7c8".isUuid())
    }
}
