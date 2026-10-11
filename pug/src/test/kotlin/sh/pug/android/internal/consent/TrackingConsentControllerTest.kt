package sh.pug.android.internal.consent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sh.pug.android.MemoryPugStorage
import sh.pug.android.TrackingConsent
import sh.pug.android.TrackingConsent.DENIED
import sh.pug.android.TrackingConsent.GRANTED
import sh.pug.android.internal.RecordingLogger

class TrackingConsentControllerTest {
    private val storage = MemoryPugStorage()
    private val logger = RecordingLogger()
    private val purge = RecordingPurge()

    @Test
    fun `consent is granted and pending by default`() {
        val consent = controller()

        assertEquals(GRANTED, consent.consent)
        assertTrue(consent.isGranted)
        assertTrue(consent.isPending)
    }

    @Test
    fun `opting out drops the queue and forgets the identity`() {
        val consent = controller()

        consent.set(DENIED)

        assertEquals(DENIED, consent.consent)
        assertFalse(consent.isGranted)
        assertFalse(consent.isPending)
        assertEquals(listOf("dropQueue", "forgetIdentity"), purge.calls)
    }

    @Test
    fun `opting in purges nothing`() {
        val consent = controller(default = DENIED)

        consent.set(GRANTED)

        assertTrue(consent.isGranted)
        assertFalse(consent.isPending)
        assertEquals(emptyList(), purge.calls)
    }

    @Test
    fun `choices are only written when persisted`() {
        controller(persist = false).set(DENIED)
        assertNull(storage.getString(KEY))

        val persisted = controller(persist = true)
        persisted.set(DENIED)
        assertEquals("denied", storage.getString(KEY))
        persisted.set(GRANTED)
        assertEquals("granted", storage.getString(KEY))
    }

    @Test
    fun `a persisted choice outranks the default and is no longer pending`() {
        storage.putString(KEY, "granted")

        val consent = controller(default = DENIED, persist = true)

        assertEquals(GRANTED, consent.consent)
        assertFalse(consent.isPending)
    }

    @Test
    fun `a stored choice is ignored without persistence`() {
        storage.putString(KEY, "denied")

        val consent = controller(persist = false)

        assertEquals(GRANTED, consent.consent)
        assertTrue(consent.isPending)
    }

    @Test
    fun `an invalid stored choice is ignored with a warning`() {
        storage.putString(KEY, "Denied")

        val consent = controller(default = DENIED, persist = true)

        assertEquals(DENIED, consent.consent)
        assertTrue(consent.isPending)
        assertEquals(listOf("The stored tracking consent \"Denied\" is not valid and was ignored"), logger.warnings)
    }

    @Test
    fun `a stored denial purges identity and queue at init`() {
        storage.putString(KEY, "denied")
        val consent = controller(persist = true)

        consent.enforceAtInit()

        assertEquals(listOf("dropQueue", "forgetIdentity"), purge.calls)
    }

    @Test
    fun `a default denial drops only the queue at init`() {
        val consent = controller(default = DENIED, persist = true)

        consent.enforceAtInit()

        assertEquals(listOf("dropQueue"), purge.calls)
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `a default denial over an identified user warns that the id is kept`() {
        purge.isIdentified = true
        val consent = controller(default = DENIED)

        consent.enforceAtInit()

        assertEquals(listOf("dropQueue"), purge.calls)
        assertTrue(logger.warnings.single().startsWith("Tracking consent is denied by default, but an external id"))
    }

    @Test
    fun `granted consent changes nothing at init`() {
        storage.putString(KEY, "granted")

        controller(persist = true).enforceAtInit()
        controller(persist = false).enforceAtInit()

        assertEquals(emptyList(), purge.calls)
    }

    @Test
    fun `denying again purges again`() {
        val consent = controller()

        consent.set(DENIED)
        consent.set(DENIED)

        assertEquals(List(2) { listOf("dropQueue", "forgetIdentity") }.flatten(), purge.calls)
    }

    private fun controller(default: TrackingConsent = GRANTED, persist: Boolean = false) =
        TrackingConsentController(storage, KEY, logger.safe(), default, persist, purge)

    private class RecordingPurge : TrackingConsentController.Purge {
        val calls = mutableListOf<String>()

        override var isIdentified: Boolean = false

        override fun dropQueue() {
            calls += "dropQueue"
        }

        override fun forgetIdentity() {
            calls += "forgetIdentity"
        }
    }

    private companion object {
        const val KEY = "__pug_proj_1_consent__"
    }
}
