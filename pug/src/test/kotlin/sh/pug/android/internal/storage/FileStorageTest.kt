@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android.internal.storage

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import sh.pug.android.internal.RecordingLogger

class FileStorageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val logger = RecordingLogger()
    private val directory by lazy { File(folder.root, "pug") }

    @AfterTest
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun `a change is read back at once and reaches the disk after the sync delay`() {
        val storage = storage()

        storage.putString(KEY, "v1")
        assertEquals("v1", storage.getString(KEY))
        scheduler.advanceTimeBy(STORAGE_SYNC_DELAY_MS - 1)
        scheduler.runCurrent()
        assertFalse(file(KEY).exists())
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()

        assertEquals("v1", file(KEY).readText())
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `changes made within the delay reach the disk together`() {
        val storage = storage()
        storage.putString(OTHER_KEY, "old")
        storage.sync()

        storage.putString(KEY, "v1")
        scheduler.advanceTimeBy(500)
        storage.putString(KEY, "v2")
        storage.remove(OTHER_KEY)
        scheduler.advanceTimeBy(STORAGE_SYNC_DELAY_MS - 500)
        scheduler.runCurrent()

        assertEquals("v2", file(KEY).readText())
        assertFalse(file(OTHER_KEY).exists())
    }

    @Test
    fun `sync writes at once`() {
        val storage = storage()

        storage.putString(KEY, "v1")
        storage.sync()

        assertEquals("v1", file(KEY).readText())
        assertEquals(0, scheduler.currentTime)
    }

    @Test
    fun `what one instance wrote, the next one reads`() {
        val first = storage()
        first.putString(KEY, "ünïcödé ✓")
        first.putString(OTHER_KEY, "gone")
        first.remove(OTHER_KEY)
        first.sync()

        val second = storage()

        assertEquals("ünïcödé ✓", second.getString(KEY))
        assertNull(second.getString(OTHER_KEY))
    }

    @Test
    fun `a key is read from disk at most once`() {
        val storage = storage()
        assertNull(storage.getString(KEY))

        directory.mkdirs()
        file(KEY).writeText("written behind its back")

        assertNull(storage.getString(KEY))
    }

    @Test
    fun `a leftover temporary file is ignored and replaced`() {
        directory.mkdirs()
        File(directory, FileStorage.fileName(KEY) + ".tmp").writeText("half-written")
        val storage = storage()

        assertNull(storage.getString(KEY))
        storage.putString(KEY, "v1")
        storage.sync()

        assertEquals("v1", file(KEY).readText())
        assertEquals(listOf(FileStorage.fileName(KEY)), directory.list()?.toList())
    }

    @Test
    fun `a project id cannot lead outside the directory`() {
        val storage = storage()
        val key = StorageKeys("../../etc/x y").queue

        storage.putString(key, "v1")
        storage.sync()

        assertEquals(listOf("__pug_..%2f..%2fetc%2fx%20y_queue__"), directory.list()?.toList())
        assertEquals("v1", storage().getString(key))
    }

    @Test
    fun `a key too long for a file name is stored under its hash`() {
        val storage = storage()
        val key = StorageKeys("p".repeat(300)).session

        storage.putString(key, "v1")
        storage.sync()

        val name = directory.list()?.single().orEmpty()
        assertTrue(name.startsWith("sha256-") && name.length == 71, name)
        assertEquals("v1", storage().getString(key))
    }

    @Test
    fun `an unreadable file reads as absent, is logged, and leaves the files in use`() {
        // A directory where the file should be cannot be read.
        file(KEY).mkdirs()
        val files = storage()
        val storage = SafeStorage(files, logger.safe())

        assertNull(storage.getString(KEY))
        storage.putString(OTHER_KEY, "v1")
        files.sync()

        assertTrue(logger.warnings.single().startsWith("Storage file "))
        assertEquals("v1", file(OTHER_KEY).readText())
    }

    @Test
    fun `a failed write is logged once and retried with the next change`() {
        // A file where the directory should be makes every write fail.
        directory.writeText("in the way")
        val storage = storage()

        storage.putString(KEY, "v1")
        storage.sync()
        storage.putString(KEY, "v2")
        storage.sync()
        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().startsWith("Storage could not be written"))
        assertEquals(listOf("Storage still cannot be written; state is kept in memory meanwhile"), logger.debugs)
        assertEquals("v2", storage.getString(KEY))

        directory.delete()
        storage.putString(OTHER_KEY, "v3")
        scheduler.advanceTimeBy(STORAGE_SYNC_DELAY_MS)
        scheduler.runCurrent()

        assertEquals("v2", file(KEY).readText())
        assertEquals("v3", file(OTHER_KEY).readText())
    }

    private fun storage() = FileStorage(directory, logger.safe(), scope)

    private fun file(key: String) = File(directory, FileStorage.fileName(key))

    private companion object {
        val KEY = StorageKeys("proj_1").queue
        val OTHER_KEY = StorageKeys("proj_1").session
    }
}
