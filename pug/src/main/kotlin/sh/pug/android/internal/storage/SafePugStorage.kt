package sh.pug.android.internal.storage

import sh.pug.android.MemoryPugStorage
import sh.pug.android.PugStorage
import sh.pug.android.internal.logging.SafeLogger

/**
 * Never throws. After the first failure of the primary store every call goes to memory for the rest
 * of the client's life; components hold their state in memory and only write through, so nothing is
 * lost until the client is destroyed. Throwables are caught whole: storage is plain host code, not a
 * suspension point, so nothing it throws is a cancellation of ours.
 */
internal class SafePugStorage(primary: PugStorage, private val logger: SafeLogger) : PugStorage {
    private var active: PugStorage = primary
    private var failedOver = false

    // A fresh memory store holds nothing, so a failed read or remove has nothing to retry.
    override fun getString(key: String): String? = try {
        active.getString(key)
    } catch (t: Throwable) {
        failOver(t)
        null
    }

    override fun putString(key: String, value: String) {
        try {
            active.putString(key, value)
        } catch (t: Throwable) {
            if (failOver(t)) putString(key, value)
        }
    }

    override fun remove(key: String) {
        try {
            active.remove(key)
        } catch (t: Throwable) {
            failOver(t)
        }
    }

    private fun failOver(failure: Throwable): Boolean {
        if (failedOver) return false
        failedOver = true
        active = MemoryPugStorage()
        logger.warn("Storage failed; state is kept in memory only until the app restarts", failure)
        return true
    }
}
