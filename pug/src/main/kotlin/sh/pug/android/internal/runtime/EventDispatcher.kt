package sh.pug.android.internal.runtime

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.pug.android.internal.event.MAX_BATCH_BYTES
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.queue.EventQueue
import sh.pug.android.internal.transport.Transport
import sh.pug.android.internal.transport.TransportException
import sh.pug.android.internal.wire.BatchCreateResponse
import sh.pug.android.internal.wire.Event

internal const val RETRY_FLOOR_MS: Long = 1_000L
internal const val RETRY_CAP_MS: Long = 5 * 60 * 1_000L
private const val RETRY_JITTER = 0.2

/**
 * Delivers queued events: a batch goes out once [maxSize] are pending, [maxWaitMs] after the first of
 * them, or on request, and only one batch is in flight at a time. A transient failure returns the
 * batch to the queue and retries with exponential backoff, during which size triggers
 * wait for the retry; a permanent one drops it. Confined to the SDK worker, like the queue it drives.
 */
internal class EventDispatcher(
    private val queue: EventQueue,
    private val transport: Transport,
    private val logger: SafeLogger,
    private val scope: CoroutineScope,
    private val maxSize: Int,
    private val maxWaitMs: Long,
    private val random: Random,
) {
    private var sender: Job? = null
    private var draining = false
    private var timer: Job? = null
    private var failures = 0

    // Immediate events awaiting their send, which teardown puts back in the queue should it stop waiting.
    private val sendingNow = ArrayList<Event>()

    // Bumped by clear(), so an immediate send that fails after a purge does not refill the queue.
    private var generation = 0
    private var closed = false
    private var disposed = false

    /** Events not yet delivered, a batch in flight included. */
    val undelivered: Int get() = queue.totalSize

    /** Events restored from storage go out [maxWaitMs] after start, like new ones. */
    fun start() {
        if (queue.size > 0) schedule(maxWaitMs)
    }

    fun enqueue(event: Event) {
        queue.push(event)
        if (queue.size >= maxSize && failures == 0) send() else schedule(maxWaitMs)
    }

    /** Sends [event] on its own right away. It joins the queue only if that fails transiently. */
    fun sendNow(event: Event) {
        val generationAtSend = generation
        sendingNow.add(event)
        scope.launch {
            val failure =
                try {
                    deliver(listOf(event))
                } finally {
                    sendingNow.remove(event)
                }
            if (failure == null || disposed || generation != generationAtSend) return@launch
            queue.push(event)
            retryLater(failure, events = 1)
        }
    }

    /** Sends the pending events now, unless a retry is being waited out. */
    fun flush() {
        if (failures == 0) send()
    }

    /** Sends every pending event, backoff or not. The job ends once the queue is empty or a send fails. */
    fun drain(): Job {
        draining = true
        return send()
    }

    /** Drops every event, a batch in flight included, and returns how many there were. */
    fun clear(): Int {
        generation++
        cancelTimer()
        sendingNow.clear()
        return queue.clear()
    }

    /** Stops timed sends and retries. Sends in flight still settle, and what stays queued stays stored. */
    fun close() {
        closed = true
        cancelTimer()
    }

    /**
     * From here on nothing touches the queue, whose storage may then belong to a successor client.
     * Immediate events still in flight are queued first, so they are kept for the next launch.
     */
    fun dispose() {
        close()
        for (event in sendingNow) queue.push(event)
        sendingNow.clear()
        disposed = true
    }

    private fun send(): Job {
        sender?.takeIf { it.isActive }?.let { return it }
        cancelTimer()
        return scope.launch { sendBatches() }.also { sender = it }
    }

    private suspend fun sendBatches() {
        try {
            while (true) {
                val batch = queue.lock(maxSize, MAX_BATCH_BYTES) ?: break
                val generationAtSend = generation
                val failure = deliver(batch.events)
                if (disposed) return
                // Purged while in flight: there is nothing to put back or to back off for.
                if (generation != generationAtSend) continue
                if (failure != null) {
                    queue.rollback(batch)
                    retryLater(failure, batch.events.size)
                    return
                }
                queue.commit(batch)
                if (!draining && queue.size < maxSize) break
            }
            if (queue.size > 0) schedule(maxWaitMs)
        } finally {
            draining = false
        }
    }

    /**
     * Sends [events] and logs the outcome. Returns the failure when they should be sent again, or null
     * once they were delivered or rejected for good.
     */
    private suspend fun deliver(events: List<Event>): TransportException? {
        val response =
            try {
                transport.sendBatch(events)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                if (!e.isPermanent) return e
                recovered()
                logger.error(
                    "${eventCount(events.size)} dropped: rejected by the server (${e.detail})",
                    e,
                )
                return null
            } catch (t: Throwable) {
                // A bug rather than the network, so a retry would fail the same way.
                logger.error("${eventCount(events.size)} dropped: the send failed unexpectedly", t)
                return null
            }
        recovered()
        if (response != null && response.accepted < events.size) warnPartiallyAccepted(response, events.size)
        if (logger.isDebugEnabled) logger.debug("Sent ${eventCount(events.size)}")
        return null
    }

    private fun warnPartiallyAccepted(response: BatchCreateResponse, sent: Int) {
        val reasons = response.droppedByReason.toSortedMap().entries.joinToString { "${it.key}: ${it.value}" }
        val detail = if (reasons.isEmpty()) "" else " (dropped: $reasons)"
        logger.warn("The server accepted ${response.accepted} of ${eventCount(sent)}$detail")
    }

    private fun retryLater(failure: TransportException, events: Int) {
        failures++
        cancelTimer()
        val reason = "Sending ${eventCount(events)} failed (${failure.detail})"
        if (closed) {
            logger.warn("$reason; kept in the queue", failure)
            return
        }
        val delayMs = retryDelayMs()
        if (failures == 1) {
            logger.warn(
                "$reason; retrying in $delayMs ms. Until a send succeeds, failures are logged at debug level",
                failure,
            )
        } else {
            logger.debug("$reason; retrying in $delayMs ms")
        }
        schedule(delayMs)
    }

    // The server answered, so a retry timer waiting out a failing server has nothing left to wait for.
    private fun recovered() {
        if (failures == 0) return
        failures = 0
        if (timer?.isActive == true) {
            cancelTimer()
            schedule(maxWaitMs)
        }
    }

    private fun retryDelayMs(): Long {
        val base = maxOf(maxWaitMs, RETRY_FLOOR_MS)
        val cap = maxOf(base, RETRY_CAP_MS)
        var delayMs = base
        repeat(minOf(failures - 1, 32)) {
            if (delayMs < cap) delayMs = minOf(delayMs * 2, cap)
        }
        return (delayMs * (1 + RETRY_JITTER * (2 * random.nextDouble() - 1))).toLong()
    }

    private fun schedule(delayMs: Long) {
        if (closed || timer?.isActive == true) return
        timer =
            scope.launch {
                delay(delayMs)
                timer = null
                send()
            }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }
}

internal fun eventCount(events: Int): String = if (events == 1) "1 event" else "$events events"
