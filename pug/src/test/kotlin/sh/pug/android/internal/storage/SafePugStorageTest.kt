package sh.pug.android.internal.storage

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import sh.pug.android.MemoryPugStorage
import sh.pug.android.PugStorage
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.ThrowingStorage

class SafePugStorageTest {
    private val logger = RecordingLogger()

    @Test
    fun `a healthy store is used directly`() {
        val primary = MemoryPugStorage()
        val storage = SafePugStorage(primary, logger.safe())

        storage.putString("a", "1")
        storage.putString("b", "2")
        storage.remove("b")

        assertEquals("1", primary.getString("a"))
        assertNull(primary.getString("b"))
        assertEquals("1", storage.getString("a"))
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `the first failure moves every later call to memory`() {
        val failure = IOException("disk full")
        val primary = ThrowingStorage(failure)
        val storage = SafePugStorage(primary, logger.safe())

        storage.putString("a", "1")
        storage.putString("b", "2")
        storage.remove("b")

        assertEquals("1", storage.getString("a"))
        assertNull(storage.getString("b"))
        assertEquals(1, primary.calls)
        val warning = logger.entries.single()
        assertEquals("Storage failed; state is kept in memory only until the app restarts", warning.message)
        assertSame(failure, warning.throwable)
    }

    @Test
    fun `a failed read reports nothing stored`() {
        val storage = SafePugStorage(ThrowingStorage(SecurityException()), logger.safe())

        assertNull(storage.getString("a"))
        storage.putString("a", "1")
        assertEquals("1", storage.getString("a"))
    }

    @Test
    fun `errors and cancellations from host storage are contained too`() {
        for (failure in listOf(StackOverflowError(), kotlinx.coroutines.CancellationException("host"))) {
            val storage = SafePugStorage(ThrowingStorage(failure), RecordingLogger().safe())

            storage.remove("a")
            storage.putString("a", "1")

            assertEquals("1", storage.getString("a"))
        }
    }

    @Test
    fun `a store that fails only on writes keeps serving what was read`() {
        val primary =
            object : PugStorage {
                override fun getString(key: String): String? = "stored"

                override fun putString(key: String, value: String) = throw IOException("read-only")

                override fun remove(key: String) = Unit
            }
        val storage = SafePugStorage(primary, logger.safe())

        assertEquals("stored", storage.getString("a"))
        storage.putString("a", "new")

        assertEquals("new", storage.getString("a"))
    }

    @Test
    fun `memory storage round-trips`() {
        val storage = MemoryPugStorage()

        storage.putString("a", "1")
        storage.putString("a", "2")
        storage.remove("missing")

        assertEquals("2", storage.getString("a"))
        storage.remove("a")
        assertNull(storage.getString("a"))
    }

    @Test
    fun `keys follow the family scheme`() {
        val keys = PugStorageKeys("proj_1")

        assertEquals(
            listOf(
                "__pug_proj_1_session__",
                "__pug_proj_1_profile__",
                "__pug_proj_1_external_id__",
                "__pug_proj_1_queue__",
                "__pug_proj_1_consent__",
                "__pug_proj_1_last_version__",
            ),
            keys.all,
        )
        assertEquals(
            listOf(keys.session, keys.profile, keys.externalId, keys.queue),
            keys.priorActivity,
        )
    }
}
