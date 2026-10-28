package sh.pug.android.internal.storage

/** The family-wide `__pug_<projectId>_<name>__` key scheme. */
internal class StorageKeys(projectId: String) {
    val session: String = key(projectId, "session")
    val profile: String = key(projectId, "profile")
    val externalId: String = key(projectId, "external_id")
    val queue: String = key(projectId, "queue")
    val consent: String = key(projectId, "consent")
    val lastVersion: String = key(projectId, "last_version")

    val all: List<String> = listOf(session, profile, externalId, queue, consent, lastVersion)

    /**
     * Keys whose presence shows the SDK has run on this device before, which tells an upgrade from a
     * fresh install. Consent is a preference rather than activity (and outlives an opt-out purge), and
     * the last version is what install detection is deciding.
     */
    val priorActivity: List<String> = all - consent - lastVersion

    private companion object {
        fun key(projectId: String, name: String): String = "__pug_${projectId}_${name}__"
    }
}
