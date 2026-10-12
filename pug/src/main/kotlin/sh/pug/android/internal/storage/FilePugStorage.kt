package sh.pug.android.internal.storage

import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.pug.android.PugStorage
import sh.pug.android.internal.appendHex
import sh.pug.android.internal.logging.SafeLogger

// Long enough to turn a burst of events into one write per key, short enough that a process killed
// without warning loses little.
internal const val STORAGE_SYNC_DELAY_MS: Long = 1_000L

// Well under the 255-byte name limit of Android's file systems.
private const val MAX_FILE_NAME_LENGTH = 128
private const val TEMP_SUFFIX = ".tmp"

/**
 * The default storage (PLAN.md D10): one file per key in [directory], each read at most once. A
 * change lands in memory at once and reaches the disk [syncDelayMs] later together with the changes
 * made meanwhile, or at [sync], so a burst of events rewrites the queue file once rather than once
 * per event. Confined to the SDK worker, like every storage.
 */
internal class FilePugStorage(
    private val directory: File,
    private val logger: SafeLogger,
    private val scope: CoroutineScope,
    private val syncDelayMs: Long = STORAGE_SYNC_DELAY_MS,
) : PugStorage {
    // A null value is a key known to be absent.
    private val values = HashMap<String, String?>()
    private val unsynced = LinkedHashSet<String>()
    private var scheduledSync: Job? = null
    private var failing = false

    override fun getString(key: String): String? {
        if (values.containsKey(key)) return values[key]
        return read(key).also { values[key] = it }
    }

    override fun putString(key: String, value: String) {
        change(key, value)
    }

    override fun remove(key: String) {
        change(key, null)
    }

    /**
     * Writes every change not yet on disk. A write that fails is logged and retried at the next
     * sync. Never throws: callers reach this directly, not through `SafePugStorage`.
     */
    fun sync() {
        scheduledSync?.cancel()
        scheduledSync = null
        if (unsynced.isEmpty()) return
        var failure: Throwable? = null
        for (key in unsynced.toList()) {
            try {
                write(key, values[key])
                unsynced.remove(key)
            } catch (t: Throwable) {
                failure = t
            }
        }
        report(failure)
    }

    private fun change(key: String, value: String?) {
        if (values.containsKey(key) && values[key] == value) return
        values[key] = value
        unsynced.add(key)
        if (scheduledSync != null) return
        scheduledSync =
            scope.launch {
                delay(syncDelayMs)
                scheduledSync = null
                sync()
            }
    }

    // An unreadable file reads as absent rather than throwing: a throw would move SafePugStorage to
    // memory while this store's earlier changes still sync, freezing the disk at a stale state.
    private fun read(key: String): String? {
        val file = file(key)
        return try {
            file.readText()
        } catch (e: IOException) {
            if (e !is FileNotFoundException || file.exists()) logger.warn("Storage file $file could not be read", e)
            null
        }
    }

    private fun write(key: String, value: String?) {
        val file = file(key)
        if (value == null) {
            if (!file.delete() && file.exists()) throw IOException("Could not delete $file")
            return
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        val temp = File(directory, file.name + TEMP_SUFFIX)
        FileOutputStream(temp).use { out ->
            out.bufferedWriter(Charsets.UTF_8).run {
                write(value)
                flush()
            }
            // The rename below must not publish a file whose content a power loss could still undo.
            out.fd.sync()
        }
        if (!temp.renameTo(file)) throw IOException("Could not replace $file")
    }

    private fun report(failure: Throwable?) {
        when {
            failure == null -> failing = false

            failing -> logger.debug("Storage still cannot be written; state is kept in memory meanwhile")

            else -> {
                failing = true
                logger.warn(
                    "Storage could not be written; state is kept in memory and written with the next change. " +
                        "Until a write succeeds, failures are logged at debug level",
                    failure,
                )
            }
        }
    }

    private fun file(key: String): File = File(directory, fileName(key))

    internal companion object {
        /**
         * Keys embed the host's project id, so anything outside `[A-Za-z0-9_.-]` is percent-encoded,
         * and a name too long for the file system is replaced by its hash.
         */
        fun fileName(key: String): String {
            val bytes = key.toByteArray(Charsets.UTF_8)
            val name = StringBuilder(bytes.size)
            for (byte in bytes) {
                val c = byte.toInt() and 0xFF
                if (c.toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_.-" }) {
                    name.append(c.toChar())
                } else {
                    name.append('%').appendHex(c)
                }
            }
            if (name.length <= MAX_FILE_NAME_LENGTH) return name.toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hashed = StringBuilder("sha256-")
            for (byte in digest) hashed.appendHex(byte.toInt())
            return hashed.toString()
        }
    }
}
