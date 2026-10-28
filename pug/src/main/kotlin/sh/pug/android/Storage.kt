package sh.pug.android

import java.util.concurrent.ConcurrentHashMap
import sh.pug.android.internal.isNull

/**
 * Persists identity, consent and queued events. Calls are synchronous and come from the SDK's own
 * worker thread, one at a time. If an implementation throws, the SDK keeps its state in memory
 * until `Pug.destroy()` or the process ends.
 */
public interface Storage {
    public fun getString(key: String): String?

    public fun putString(key: String, value: String)

    public fun remove(key: String)
}

/** Keeps everything in memory, so nothing outlives the process. A null from Java is ignored. */
public class MemoryStorage : Storage {
    private val values = ConcurrentHashMap<String, String>()

    override fun getString(key: String): String? = if (isNull(key)) null else values[key]

    override fun putString(key: String, value: String) {
        if (isNull(key) || isNull(value)) return
        values[key] = value
    }

    override fun remove(key: String) {
        if (isNull(key)) return
        values.remove(key)
    }
}
