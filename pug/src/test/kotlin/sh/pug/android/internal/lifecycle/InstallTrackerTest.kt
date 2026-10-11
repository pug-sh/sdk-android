package sh.pug.android.internal.lifecycle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import sh.pug.android.MemoryPugStorage
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.storage.PugStorageKeys

class InstallTrackerTest {
    private val storage = MemoryPugStorage()
    private val keys = PugStorageKeys("proj_1")
    private val logger = RecordingLogger()
    private val captured = mutableListOf<Pair<String, Map<String, Any?>>>()
    private val tracker = InstallTracker(storage, keys, logger.safe())

    @Test
    fun `the first launch is an install`() {
        tracker.check("1.0")
        tracker.emit(::capture)

        assertEquals(listOf(APP_INSTALL to mapOf<String, Any?>("appVersion" to "1.0")), captured)
        assertEquals("1.0", storage.getString(keys.lastVersion))
    }

    @Test
    fun `a new version is an update from the last one`() {
        storage.putString(keys.lastVersion, "1.0")

        tracker.check("1.1")
        tracker.emit(::capture)

        assertEquals(
            listOf(APP_UPDATE to mapOf<String, Any?>("appVersion" to "1.1", "previousVersion" to "1.0")),
            captured,
        )
        assertEquals("1.1", storage.getString(keys.lastVersion))
    }

    @Test
    fun `the same version tracks nothing`() {
        storage.putString(keys.lastVersion, "1.0")

        tracker.check("1.0")
        tracker.emit(::capture)

        assertEquals(emptyList(), captured)
    }

    @Test
    fun `an app that used Pug before recording versions is not a new install`() {
        storage.putString(keys.profile, """{"anonymousId":"anon-1"}""")

        tracker.check("1.0")
        tracker.emit(::capture)

        assertEquals(emptyList(), captured)
        assertEquals("1.0", storage.getString(keys.lastVersion))
    }

    @Test
    fun `a stored consent choice is not earlier activity`() {
        storage.putString(keys.consent, "granted")
        storage.putString(keys.lastVersion, "")

        tracker.check("1.0")
        tracker.emit(::capture)

        assertEquals(listOf(APP_INSTALL), captured.map { it.first })
    }

    @Test
    fun `an event consent kept back waits for the next emit`() {
        tracker.check("1.0")

        tracker.emit { _, _ -> false }
        assertNull(storage.getString(keys.lastVersion))
        tracker.emit(::capture)
        tracker.emit(::capture)

        assertEquals(listOf(APP_INSTALL), captured.map { it.first })
        assertEquals("1.0", storage.getString(keys.lastVersion))
    }

    @Test
    fun `without an app version nothing is tracked`() {
        tracker.check(null)
        tracker.check("")
        tracker.emit(::capture)

        assertEquals(emptyList(), captured)
        assertNull(storage.getString(keys.lastVersion))
        assertEquals(
            List(2) { "app_install and app_update are not tracked: the app version could not be read" },
            logger.debugs,
        )
    }

    private fun capture(kind: String, properties: Map<String, Any?>): Boolean {
        captured += kind to properties
        return true
    }
}
