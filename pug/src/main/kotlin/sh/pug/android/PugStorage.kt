package sh.pug.android

import java.util.concurrent.ConcurrentHashMap

/**
 * Persists identity, consent and queued events. Calls are synchronous and come from the SDK's own
 * worker thread, one at a time. If an implementation throws, the SDK keeps its state in memory for
 * the rest of the process.
 */
public interface PugStorage {
    public fun getString(key: String): String?

    public fun putString(key: String, value: String)

    public fun remove(key: String)
}

/** Keeps everything in memory, so nothing outlives the process. */
public class MemoryPugStorage : PugStorage {
    private val values = ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
