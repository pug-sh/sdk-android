package sh.pug.android.internal.lifecycle

import sh.pug.android.PugEvents
import sh.pug.android.PugStorage
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.storage.PugStorageKeys

/**
 * `app_install` and `app_update`, once per app version (Flutter's InstallTracker). [check] decides
 * at start, before this launch writes anything, so the prior-activity probe sees earlier launches
 * only. The stored version advances only once the event is captured: an install that consent kept
 * from being tracked is tracked by [emit] once the user opts in.
 */
internal class InstallTracker(
    private val storage: PugStorage,
    private val keys: PugStorageKeys,
    private val logger: SafeLogger,
) {
    private class Pending(val kind: String, val properties: Map<String, Any?>, val version: String)

    private var pending: Pending? = null

    fun check(appVersion: String?, installSource: () -> String?) {
        if (appVersion.isNullOrEmpty()) {
            logger.debug("app_install and app_update are not tracked: the app version could not be read")
            return
        }
        val lastVersion = storage.getString(keys.lastVersion)
        pending =
            when {
                lastVersion == appVersion -> null

                !lastVersion.isNullOrEmpty() -> {
                    val properties = mapOf("appVersion" to appVersion, "previousVersion" to lastVersion)
                    Pending(PugEvents.APP_UPDATE, properties, appVersion)
                }

                // Pug ran here before it recorded a version: this app already used it, so this is
                // an upgrade of a known install rather than a new one.
                keys.priorActivity.any { storage.getString(it) != null } -> {
                    storage.putString(keys.lastVersion, appVersion)
                    null
                }

                else -> {
                    val properties = mapOf("appVersion" to appVersion, "installSource" to installSource())
                    Pending(PugEvents.APP_INSTALL, properties, appVersion)
                }
            }
    }

    /** Tracks the pending event, if any, through [capture], which reports whether it was captured. */
    fun emit(capture: (kind: String, properties: Map<String, Any?>) -> Boolean) {
        val event = pending ?: return
        if (!capture(event.kind, event.properties)) return
        pending = null
        storage.putString(keys.lastVersion, event.version)
    }
}
