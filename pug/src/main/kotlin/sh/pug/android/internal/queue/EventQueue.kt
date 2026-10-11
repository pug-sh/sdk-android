package sh.pug.android.internal.queue

import org.json.JSONArray
import org.json.JSONObject
import sh.pug.android.PugStorage
import sh.pug.android.internal.identity.RESERVED_ID_PREFIX
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PugWire

/**
 * Undelivered events, persisted as one JSON array. A batch is sent under a lock: [lock] takes events
 * off the front, then [commit] drops them once delivered or [rollback] puts them back. The stored
 * array always holds the locked batch followed by the pending events, so a process that dies
 * mid-send resends the batch on its next launch.
 */
internal class EventQueue(
    private val storage: PugStorage,
    private val key: String,
    private val logger: SafeLogger,
    private val maxQueueSize: Int,
) {
    class Batch internal constructor(val events: List<Event>)

    // Each event's JSON is kept so persisting a change re-joins strings instead of re-encoding
    // every event, which matters when a long offline spell fills the queue.
    private class Entry(val event: Event, val json: String, val size: Int)

    private val pending = ArrayDeque<Entry>()
    private var locked: List<Entry> = emptyList()
    private var lockedBatch: Batch? = null
    private var overflowWarned = false

    init {
        restore()
    }

    /** Events waiting to be sent, not counting a locked batch. */
    val size: Int get() = pending.size

    val totalSize: Int get() = pending.size + locked.size

    /** At capacity the oldest pending event is dropped; with every event locked, the new one is. */
    fun push(event: Event) {
        if (totalSize >= maxQueueSize) {
            if (pending.isEmpty()) {
                overflow("the new event was dropped")
                return
            }
            pending.removeFirst()
            overflow("the oldest event was dropped")
        }
        pending.addLast(entry(event))
        persist()
    }

    /**
     * Locks up to [maxCount] events and [maxBytes] of encoded events from the front, or returns null
     * while another batch is locked or nothing is pending. A repeated event id is dropped rather than
     * sent twice in one request, as is an event that alone exceeds [maxBytes].
     */
    fun lock(maxCount: Int, maxBytes: Int): Batch? {
        if (lockedBatch != null) return null
        val taken = ArrayList<Entry>(minOf(maxCount, pending.size))
        val takenIds = HashSet<String>()
        var bytes = 0
        var dropped = 0
        while (taken.size < maxCount) {
            val next = pending.firstOrNull() ?: break
            when {
                next.event.eventId in takenIds -> {
                    pending.removeFirst()
                    dropped++
                    logger.warn("A queued event repeating the id of another in its batch was dropped")
                }

                next.size > maxBytes -> {
                    pending.removeFirst()
                    dropped++
                    logger.error(
                        "A queued \"${next.event.kind}\" event of ${next.size} bytes was dropped: too large to send",
                    )
                }

                bytes + next.size > maxBytes -> break

                else -> {
                    pending.removeFirst()
                    taken.add(next)
                    takenIds.add(next.event.eventId)
                    bytes += next.size
                }
            }
        }
        val batch = if (taken.isEmpty()) null else Batch(taken.map { it.event })
        if (batch != null) {
            locked = taken
            lockedBatch = batch
        }
        if (dropped > 0) persist()
        return batch
    }

    /** Drops the delivered (or permanently rejected) [batch]; a batch from before [clear] is ignored. */
    fun commit(batch: Batch) {
        if (batch !== lockedBatch) return
        locked = emptyList()
        lockedBatch = null
        overflowWarned = false
        persist()
    }

    /** Returns [batch] to the front of the queue; a batch from before [clear] is ignored. */
    fun rollback(batch: Batch) {
        if (batch !== lockedBatch) return
        for (entry in locked.asReversed()) pending.addFirst(entry)
        locked = emptyList()
        lockedBatch = null
        // Storage already holds the locked batch ahead of the pending events.
    }

    /** Drops every event, a locked batch included, and returns how many there were. */
    fun clear(): Int {
        val dropped = totalSize
        pending.clear()
        locked = emptyList()
        lockedBatch = null
        overflowWarned = false
        storage.remove(key)
        return dropped
    }

    private fun entry(event: Event) = Entry(event, EventJson.encode(event), PugWire.eventSize(event))

    private fun overflow(outcome: String) {
        if (overflowWarned) {
            logger.debug("The event queue is full; $outcome")
            return
        }
        overflowWarned = true
        logger.warn("The event queue is full ($maxQueueSize events); $outcome. Later drops are logged at debug level")
    }

    private fun persist() {
        if (totalSize == 0) {
            storage.remove(key)
            return
        }
        var length = 1
        for (entry in locked) length += entry.json.length + 1
        for (entry in pending) length += entry.json.length + 1
        val out = StringBuilder(length)
        for (entry in locked) out.append(if (out.isEmpty()) '[' else ',').append(entry.json)
        for (entry in pending) out.append(if (out.isEmpty()) '[' else ',').append(entry.json)
        storage.putString(key, out.append(']').toString())
    }

    private fun restore() {
        val raw = storage.getString(key) ?: return
        val array =
            try {
                JSONArray(raw)
            } catch (e: Exception) {
                logger.warn("The stored event queue could not be read and was discarded", e)
                storage.remove(key)
                return
            }
        var unreadable = 0
        var reserved = 0
        for (i in 0 until array.length()) {
            val event =
                try {
                    (array.opt(i) as? JSONObject)?.let { EventJson.decode(it) }
                } catch (e: Exception) {
                    null
                }
            when {
                event == null -> unreadable++
                event.distinctId.startsWith(RESERVED_ID_PREFIX) -> reserved++
                else -> pending.addLast(entry(event))
            }
        }
        val excess = maxOf(0, pending.size - maxQueueSize)
        repeat(excess) { pending.removeFirst() }
        if (unreadable > 0) logger.warn("$unreadable stored events could not be read and were dropped")
        if (reserved > 0) {
            logger.warn(
                "$reserved stored events were dropped: their distinct id uses the reserved " +
                    "\"$RESERVED_ID_PREFIX\" prefix, which the server rejects",
            )
        }
        if (excess >
            0
        ) {
            logger.warn("$excess of the oldest stored events were dropped to fit maxQueueSize $maxQueueSize")
        }
        if (unreadable + reserved + excess > 0) persist()
    }
}
