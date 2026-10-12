package sh.pug.android.internal.identity

import org.json.JSONObject
import sh.pug.android.PugStorage
import sh.pug.android.internal.IdGenerator
import sh.pug.android.internal.isUuid
import sh.pug.android.internal.json.JsonWriter
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.storage.PugStorageKeys

/** The server mints cookieless ids with this prefix and rejects any batch whose distinct id has it. */
internal const val RESERVED_ID_PREFIX: String = "cookieless-"

internal const val ANONYMOUS_ID_PREFIX: String = "anon-"

/**
 * Who is tracking: the session, the anonymous id and the external id from `identify`. State lives in
 * memory and is written through to storage. Ids are minted on first use rather than on load, so a
 * client whose consent is denied never writes one.
 */
internal class Identity(
    private val storage: PugStorage,
    private val keys: PugStorageKeys,
    private val logger: SafeLogger,
    private val ids: IdGenerator,
    private val idleTimeoutMs: Long,
    private val maxDurationMs: Long,
) {
    private class Session(val id: String, val startTime: Long, val lastActivityTime: Long)

    private var session: Session? = null
    private var currentAnonymousId: String? = null

    var externalId: String? = null
        private set

    init {
        session = restoreSession()
        restoreProfile()
    }

    val anonymousId: String
        get() = currentAnonymousId ?: newAnonymousId().also {
            currentAnonymousId = it
            storeProfile()
        }

    val distinctId: String get() = externalId ?: anonymousId

    /** Whether [anonymousId] is still the current one, without minting one if there is none. */
    fun isCurrentAnonymousId(anonymousId: String): Boolean = currentAnonymousId == anonymousId

    /** The session for an event at [nowMillis]: the current one, or a new one if it idled out or ran too long. */
    fun sessionId(nowMillis: Long): String {
        val current = session
        val next =
            if (current == null ||
                nowMillis - current.lastActivityTime > idleTimeoutMs ||
                nowMillis - current.startTime > maxDurationMs
            ) {
                Session(ids.nextId(), nowMillis, nowMillis)
            } else {
                Session(current.id, current.startTime, nowMillis)
            }
        storeSession(next)
        return next.id
    }

    fun rotateSession(nowMillis: Long) {
        storeSession(Session(ids.nextId(), nowMillis, nowMillis))
    }

    fun link(externalId: String) {
        this.externalId = externalId
        if (currentAnonymousId == null) currentAnonymousId = newAnonymousId()
        storeProfile()
        storage.putString(keys.externalId, externalId)
    }

    fun reset(nowMillis: Long) {
        externalId = null
        currentAnonymousId = newAnonymousId()
        storeProfile()
        storage.remove(keys.externalId)
        rotateSession(nowMillis)
    }

    /** Forgets everything, on the device too; the next event mints a new identity. */
    fun clear() {
        session = null
        currentAnonymousId = null
        externalId = null
        storage.remove(keys.session)
        storage.remove(keys.profile)
        storage.remove(keys.externalId)
    }

    private fun newAnonymousId(): String = ANONYMOUS_ID_PREFIX + ids.nextId()

    private fun restoreSession(): Session? {
        val json = readJson(keys.session) ?: return null
        val id = json.opt("sessionId") as? String
        val startTime = json.opt("startTime") as? Number
        val lastActivityTime = json.opt("lastActivityTime") as? Number
        if (id == null || !id.isUuid() || startTime == null || lastActivityTime == null) {
            logger.warn("The stored session is incomplete; the next event starts a new one")
            return null
        }
        return Session(id, startTime.toLong(), lastActivityTime.toLong())
    }

    private fun restoreProfile() {
        val profile = readJson(keys.profile)
        var anonymousId = profile?.opt("anonymousId") as? String
        if (anonymousId != null && !anonymousId.startsWith(ANONYMOUS_ID_PREFIX)) {
            logger.warn("The stored anonymous id lacks the \"$ANONYMOUS_ID_PREFIX\" prefix; a new one will be used")
            anonymousId = null
        }
        var externalId = (profile?.opt("externalId") as? String) ?: storage.getString(keys.externalId)
        // Written by an identify the server accepted before it reserved the prefix. As the distinct
        // id it fails every batch it is in, healthy events included.
        val reserved = externalId?.startsWith(RESERVED_ID_PREFIX) == true
        if (reserved) {
            logger.warn(
                "The stored external id uses the reserved \"$RESERVED_ID_PREFIX\" prefix and was discarded; " +
                    "the user is anonymous until identify() is called again",
            )
            externalId = null
        }
        currentAnonymousId = anonymousId
        this.externalId = externalId?.takeIf { it.isNotEmpty() }
        if (reserved) {
            storage.remove(keys.externalId)
            storeProfile()
        }
    }

    private fun readJson(key: String): JSONObject? {
        val raw = storage.getString(key) ?: return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            logger.warn("Stored identity state could not be read and was ignored", e)
            null
        }
    }

    private fun storeSession(session: Session) {
        this.session = session
        val json =
            JsonWriter()
                .beginObject()
                .name("sessionId")
                .value(session.id)
                .name("startTime")
                .value(session.startTime)
                .name("lastActivityTime")
                .value(session.lastActivityTime)
                .endObject()
        storage.putString(keys.session, json.toString())
    }

    private fun storeProfile() {
        val anonymousId = currentAnonymousId
        if (anonymousId == null) {
            storage.remove(keys.profile)
            return
        }
        val json = JsonWriter().beginObject().name("anonymousId").value(anonymousId)
        externalId?.let { json.name("externalId").value(it) }
        storage.putString(keys.profile, json.endObject().toString())
    }
}
