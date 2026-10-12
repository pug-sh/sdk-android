package sh.pug.android.internal.consent

import sh.pug.android.PugStorage
import sh.pug.android.TrackingConsent
import sh.pug.android.internal.logging.SafeLogger

/**
 * The consent state and its side effects (sdk-web semantics). Denying consent drops the queue unsent
 * and forgets the identity; granting it again mints a fresh identity on the next event.
 */
internal class TrackingConsentController(
    private val storage: PugStorage,
    private val key: String,
    private val logger: SafeLogger,
    defaultConsent: TrackingConsent,
    private val persist: Boolean,
    private val purge: Purge,
) {
    interface Purge {
        val isIdentified: Boolean

        fun dropQueue()

        fun forgetIdentity()
    }

    var consent: TrackingConsent = defaultConsent
        private set

    // Whether consent was restored from storage. Only a recorded denial may purge identity at init:
    // purging on a default would mint a new user on every launch.
    private var restored = false

    // Whether consent is a user's choice, restored or made in this process, rather than the default.
    private var decided = false

    init {
        if (persist) restore()
    }

    val isGranted: Boolean get() = consent == TrackingConsent.GRANTED

    /** The user has not chosen yet, so [consent] is still the configured default. */
    val isPending: Boolean get() = !decided

    /** Brings the device in line with a denied consent at init, once the queue and identity exist. */
    fun enforceAtInit() {
        if (isGranted) return
        purge.dropQueue()
        if (restored) {
            purge.forgetIdentity()
            logger.debug("Tracking consent is denied by a stored choice; events and identify() are dropped")
        } else {
            logger.debug("Tracking consent is denied by default; events and identify() are dropped until opt-in")
            if (purge.isIdentified) {
                logger.warn(
                    "Tracking consent is denied by default, but an external id from an earlier identify() " +
                        "is kept: a default is not the user's choice. Persist consent, or call optOutTracking() " +
                        "once the user declines",
                )
            }
        }
    }

    fun set(value: TrackingConsent) {
        decided = true
        consent = value
        if (persist) storage.putString(key, value.storedName)
        if (value == TrackingConsent.DENIED) {
            purge.dropQueue()
            purge.forgetIdentity()
        }
    }

    private fun restore() {
        val stored = storage.getString(key) ?: return
        val value = TrackingConsent.entries.firstOrNull { it.storedName == stored }
        if (value == null) {
            logger.warn("The stored tracking consent \"$stored\" is not valid and was ignored")
            return
        }
        consent = value
        restored = true
        decided = true
    }

    private companion object {
        // The family's persisted form, shared with sdk-web and sdk-flutter.
        val TrackingConsent.storedName: String
            get() = when (this) {
                TrackingConsent.GRANTED -> "granted"
                TrackingConsent.DENIED -> "denied"
            }
    }
}
