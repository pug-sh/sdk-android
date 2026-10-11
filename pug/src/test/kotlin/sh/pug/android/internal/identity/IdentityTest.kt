package sh.pug.android.internal.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject
import sh.pug.android.MemoryPugStorage
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.storage.PugStorageKeys

class IdentityTest {
    private val storage = MemoryPugStorage()
    private val keys = PugStorageKeys("proj_1")
    private val logger = RecordingLogger()
    private val ids = SequentialIds()

    @Test
    fun `nothing is written until an id is needed`() {
        identity()

        assertTrue(keys.all.all { storage.getString(it) == null })
    }

    @Test
    fun `the anonymous id is minted once and stored`() {
        val identity = identity()

        val anonymousId = identity.anonymousId

        assertEquals("anon-00000000-0000-7000-8000-000000000001", anonymousId)
        assertEquals(anonymousId, identity.anonymousId)
        assertEquals(anonymousId, identity.distinctId)
        assertEquals("""{"anonymousId":"$anonymousId"}""", storage.getString(keys.profile))
    }

    @Test
    fun `a session continues while events keep coming`() {
        val identity = identity()

        val first = identity.sessionId(nowMillis = 1_000L)
        val second = identity.sessionId(nowMillis = 1_000L + IDLE)

        assertEquals(first, second)
        assertEquals(
            """{"sessionId":"$first","startTime":1000,"lastActivityTime":${1_000L + IDLE}}""",
            storage.getString(keys.session),
        )
    }

    @Test
    fun `a session idle for longer than the timeout is replaced`() {
        val identity = identity()

        val first = identity.sessionId(nowMillis = 1_000L)
        val second = identity.sessionId(nowMillis = 1_000L + IDLE + 1)

        assertNotEquals(first, second)
        assertEquals(1_000L + IDLE + 1, storedSession().getLong("startTime"))
    }

    @Test
    fun `a session running past its maximum duration is replaced`() {
        val identity = identity()
        val first = identity.sessionId(nowMillis = 0L)

        var now = 0L
        while (now + IDLE <= MAX_DURATION) {
            now += IDLE
            assertEquals(first, identity.sessionId(now))
        }
        val next = identity.sessionId(MAX_DURATION + 1)

        assertNotEquals(first, next)
    }

    @Test
    fun `state survives a restart`() {
        val before = identity()
        val sessionId = before.sessionId(nowMillis = 1_000L)
        before.link("user-42")
        val anonymousId = before.anonymousId

        val after = identity()

        assertEquals(sessionId, after.sessionId(nowMillis = 2_000L))
        assertEquals(anonymousId, after.anonymousId)
        assertEquals("user-42", after.externalId)
        assertEquals("user-42", after.distinctId)
    }

    @Test
    fun `linking stores the external id in the profile and under its own key`() {
        val identity = identity()

        identity.link("user-42")

        val anonymousId = identity.anonymousId
        assertEquals(
            """{"anonymousId":"$anonymousId","externalId":"user-42"}""",
            storage.getString(keys.profile),
        )
        assertEquals("user-42", storage.getString(keys.externalId))
        assertEquals("user-42", identity.distinctId)
    }

    @Test
    fun `an external id stored without a profile is kept`() {
        storage.putString(keys.externalId, "user-42")

        val identity = identity()

        assertEquals("user-42", identity.distinctId)
        assertTrue(identity.anonymousId.startsWith("anon-"))
    }

    @Test
    fun `reset starts a new anonymous user and session`() {
        val identity = identity()
        val oldSession = identity.sessionId(nowMillis = 1_000L)
        identity.link("user-42")
        val oldAnonymousId = identity.anonymousId

        identity.reset(nowMillis = 2_000L)

        assertNull(identity.externalId)
        assertNotEquals(oldAnonymousId, identity.anonymousId)
        assertEquals(identity.anonymousId, identity.distinctId)
        assertNotEquals(oldSession, identity.sessionId(nowMillis = 2_001L))
        assertNull(storage.getString(keys.externalId))
        assertEquals("""{"anonymousId":"${identity.anonymousId}"}""", storage.getString(keys.profile))
        assertFalse(identity.isCurrentAnonymousId(oldAnonymousId))
    }

    @Test
    fun `rotating keeps the user and replaces the session`() {
        val identity = identity()
        val session = identity.sessionId(nowMillis = 1_000L)
        val anonymousId = identity.anonymousId

        identity.rotateSession(nowMillis = 1_500L)

        assertNotEquals(session, identity.sessionId(nowMillis = 1_600L))
        assertEquals(anonymousId, identity.anonymousId)
        assertEquals(1_500L, storedSession().getLong("startTime"))
    }

    @Test
    fun `clearing forgets everything until the next use`() {
        val identity = identity()
        identity.sessionId(nowMillis = 1_000L)
        identity.link("user-42")
        val anonymousId = identity.anonymousId

        identity.clear()

        assertTrue(listOf(keys.session, keys.profile, keys.externalId).all { storage.getString(it) == null })
        assertNull(identity.externalId)
        assertFalse(identity.isCurrentAnonymousId(anonymousId))
        assertNotEquals(anonymousId, identity.anonymousId)
    }

    @Test
    fun `isCurrentAnonymousId never mints`() {
        val identity = identity()

        assertFalse(identity.isCurrentAnonymousId("anon-x"))
        assertNull(storage.getString(keys.profile))
        assertTrue(identity.isCurrentAnonymousId(identity.anonymousId))
    }

    @Test
    fun `unreadable state is replaced`() {
        storage.putString(keys.session, "{not json")
        storage.putString(keys.profile, "[]")

        val identity = identity()

        assertEquals("00000000-0000-7000-8000-000000000001", identity.sessionId(nowMillis = 1L))
        assertEquals("anon-00000000-0000-7000-8000-000000000002", identity.anonymousId)
        assertEquals(2, logger.warnings.size)
        assertTrue(logger.warnings.all { it == "Stored identity state could not be read and was ignored" })
    }

    @Test
    fun `a session the server would reject is replaced`() {
        for (session in listOf(
            """{"sessionId":"not-a-uuid","startTime":1,"lastActivityTime":1}""",
            """{"sessionId":"00000000-0000-7000-8000-000000000099","startTime":"1","lastActivityTime":1}""",
            """{"startTime":1,"lastActivityTime":1}""",
        )) {
            storage.putString(keys.session, session)

            val sessionId = identity().sessionId(nowMillis = 2L)

            assertNotEquals("00000000-0000-7000-8000-000000000099", sessionId)
        }
        assertEquals(
            List(3) { "The stored session is incomplete; the next event starts a new one" },
            logger.warnings,
        )
    }

    @Test
    fun `an anonymous id without its prefix is replaced`() {
        storage.putString(keys.profile, """{"anonymousId":"0192f3a4"}""")

        val identity = identity()

        assertEquals("anon-00000000-0000-7000-8000-000000000001", identity.anonymousId)
        assertEquals(
            listOf("The stored anonymous id lacks the \"anon-\" prefix; a new one will be used"),
            logger.warnings,
        )
    }

    @Test
    fun `an external id with the reserved prefix is discarded and the device healed`() {
        storage.putString(keys.profile, """{"anonymousId":"anon-1","externalId":"cookieless-abc"}""")
        storage.putString(keys.externalId, "cookieless-abc")

        val identity = identity()

        assertNull(identity.externalId)
        assertEquals("anon-1", identity.distinctId)
        assertNull(storage.getString(keys.externalId))
        assertEquals("""{"anonymousId":"anon-1"}""", storage.getString(keys.profile))
        assertEquals(1, logger.warnings.size)
        assertTrue(
            logger.warnings.single().startsWith("The stored external id uses the reserved \"cookieless-\" prefix"),
        )
    }

    private fun storedSession() = JSONObject(assertNotNull(storage.getString(keys.session)))

    private fun identity() =
        Identity(storage, keys, logger.safe(), ids, idleTimeoutMs = IDLE, maxDurationMs = MAX_DURATION)

    private companion object {
        const val IDLE = 30 * 60_000L
        const val MAX_DURATION = 24 * 60 * 60_000L
    }
}
