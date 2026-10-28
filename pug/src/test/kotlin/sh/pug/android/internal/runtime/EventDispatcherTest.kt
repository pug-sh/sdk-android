@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android.internal.runtime

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import sh.pug.android.MemoryStorage
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.queue.EventJson
import sh.pug.android.internal.queue.EventQueue
import sh.pug.android.internal.transport.RpcCode
import sh.pug.android.internal.wire.BatchCreateResponse
import sh.pug.android.internal.wire.Event

class EventDispatcherTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val storage = MemoryStorage()
    private val logger = RecordingLogger()
    private val transport = FakeTransport { scheduler.currentTime }

    @AfterTest
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun `a full batch is sent at once`() {
        val dispatcher = dispatcher()
        val events = events(0 until 3)

        events.forEach(dispatcher::enqueue)
        scheduler.runCurrent()

        assertEquals(listOf(events), transport.batches)
        assertEquals(listOf(0L), transport.batchTimes)
        assertNull(storage.getString(KEY))
    }

    @Test
    fun `fewer events go out maxWaitMs after the first of them`() {
        val dispatcher = dispatcher(maxWaitMs = 5_000)

        dispatcher.enqueue(event(0))
        scheduler.advanceTimeBy(4_000)
        dispatcher.enqueue(event(1))
        scheduler.advanceTimeBy(999)
        assertEquals(emptyList(), transport.batches)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()

        assertEquals(listOf(events(0 until 2)), transport.batches)
        assertEquals(listOf(5_000L), transport.batchTimes)
    }

    @Test
    fun `one batch is in flight at a time, and a full queue keeps sending`() {
        val dispatcher = dispatcher()
        transport.hold()

        events(0 until 7).forEach(dispatcher::enqueue)
        scheduler.runCurrent()
        assertEquals(1, transport.batches.size)
        transport.releaseAll()
        scheduler.runCurrent()

        assertEquals(listOf(events(0 until 3), events(3 until 6)), transport.batches)
        assertEquals(listOf(event(6)), stored())
    }

    @Test
    fun `a transient failure keeps the batch and retries with exponential backoff`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val dispatcher = dispatcher(maxWaitMs = 5_000)
        val events = events(0 until 3)

        events.forEach(dispatcher::enqueue)
        scheduler.advanceTimeBy(20 * MINUTE)

        // 5 s doubling to the 5-minute cap.
        assertEquals(
            listOf(0L, 5_000, 15_000, 35_000, 75_000, 155_000, 315_000, 615_000, 915_000),
            transport.batchTimes,
        )
        assertEquals(events, stored())
        assertEquals(
            listOf(
                "Sending 3 events failed (unavailable: scripted); retrying in 5000 ms. Until a send succeeds, " +
                    "failures are logged at debug level",
            ),
            logger.warnings,
        )
        assertTrue("Sending 3 events failed (unavailable: scripted); retrying in 300000 ms" in logger.debugs)
    }

    @Test
    fun `retries start from a one-second floor`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val dispatcher = dispatcher(maxWaitMs = 0)

        dispatcher.enqueue(event(0))
        scheduler.advanceTimeBy(8_000)

        assertEquals(listOf(0L, 1_000, 3_000, 7_000), transport.batchTimes)
    }

    @Test
    fun `a retry can come up to a fifth early`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val dispatcher = dispatcher(maxWaitMs = 10_000, random = FixedRandom(0.0))

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.advanceUntilIdle()

        assertEquals(listOf(0L, 8_000), transport.batchTimes)
    }

    @Test
    fun `a retry can come up to a fifth late`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val dispatcher = dispatcher(maxWaitMs = 10_000, random = FixedRandom(1.0))

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.advanceUntilIdle()

        assertEquals(listOf(0L, 12_000), transport.batchTimes)
    }

    @Test
    fun `size triggers wait out a retry`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val dispatcher = dispatcher(maxWaitMs = 1_000)

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.runCurrent()
        events(3 until 6).forEach(dispatcher::enqueue)
        scheduler.advanceTimeBy(999)
        assertEquals(1, transport.batches.size)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()

        assertEquals(listOf(events(0 until 3), events(0 until 3), events(3 until 6)), transport.batches)
        assertEquals(listOf(0L, 1_000, 1_000), transport.batchTimes)
    }

    @Test
    fun `a success resets the backoff`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val dispatcher = dispatcher(maxWaitMs = 1_000)

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        events(3 until 6).forEach(dispatcher::enqueue)
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()

        assertEquals(listOf(0L, 1_000, 3_000, 3_000, 4_000), transport.batchTimes)
        assertNull(storage.getString(KEY))
        assertEquals(2, logger.warnings.size)
    }

    @Test
    fun `an answer from the server ends the wait for a retry`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val dispatcher = dispatcher(maxWaitMs = 1_000)

        dispatcher.sendNow(event(0))
        scheduler.advanceTimeBy(3_000)
        scheduler.runCurrent()
        transport.batchFailure = null
        dispatcher.sendNow(event(1))
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()

        // The next retry was due at 7 s; event 0 goes out maxWaitMs after the server answered instead.
        assertEquals(listOf(0L, 1_000, 3_000, 3_000, 4_000), transport.batchTimes)
        assertEquals(listOf(event(0)), transport.batches.last())
        assertNull(storage.getString(KEY))
    }

    @Test
    fun `a permanent failure drops the batch`() {
        transport.failNextBatch(RpcCode.INVALID_ARGUMENT)
        val dispatcher = dispatcher()

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.advanceUntilIdle()

        assertEquals(1, transport.batches.size)
        assertNull(storage.getString(KEY))
        assertEquals(
            listOf("3 events dropped: rejected by the server (invalid_argument: scripted)"),
            logger.errors,
        )
    }

    @Test
    fun `a short acceptance is reported`() {
        transport.answerNextBatch(BatchCreateResponse(accepted = 1, mapOf("salt_unavailable" to 2L)))
        val dispatcher = dispatcher()

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.runCurrent()

        assertEquals(listOf("The server accepted 1 of 3 events (dropped: salt_unavailable: 2)"), logger.warnings)
        assertNull(storage.getString(KEY))
    }

    @Test
    fun `an acknowledgement that cannot be read still delivers the batch`() {
        transport.answerNextBatch(null)
        val dispatcher = dispatcher()

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.runCurrent()

        assertNull(storage.getString(KEY))
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `flush sends what is pending now, but not during a retry wait`() {
        val dispatcher = dispatcher(maxWaitMs = 60_000)

        dispatcher.enqueue(event(0))
        dispatcher.flush()
        scheduler.runCurrent()
        assertEquals(listOf(listOf(event(0))), transport.batches)

        transport.failNextBatch(RpcCode.UNAVAILABLE)
        dispatcher.enqueue(event(1))
        dispatcher.flush()
        scheduler.runCurrent()
        dispatcher.flush()
        scheduler.runCurrent()

        assertEquals(2, transport.batches.size)
        assertEquals(listOf(event(1)), stored())
    }

    @Test
    fun `drain sends everything, backoff or not`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val dispatcher = dispatcher(maxWaitMs = 60_000)

        events(0 until 7).forEach(dispatcher::enqueue)
        scheduler.runCurrent()
        val drain = dispatcher.drain()
        scheduler.runCurrent()

        assertTrue(drain.isCompleted)
        assertEquals(
            listOf(events(0 until 3), events(0 until 3), events(3 until 6), events(6 until 7)),
            transport.batches,
        )
        assertNull(storage.getString(KEY))
        scheduler.advanceUntilIdle()
        assertEquals(4, transport.batches.size)
    }

    @Test
    fun `a drain ends at the first failure`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val dispatcher = dispatcher()

        events(0 until 2).forEach(dispatcher::enqueue)
        val drain = dispatcher.drain()
        scheduler.runCurrent()

        assertTrue(drain.isCompleted)
        assertEquals(1, transport.batches.size)
        assertEquals(events(0 until 2), stored())
    }

    @Test
    fun `an immediate event is sent alone right away`() {
        val dispatcher = dispatcher()

        dispatcher.enqueue(event(0))
        dispatcher.sendNow(event(1))
        scheduler.runCurrent()

        assertEquals(listOf(listOf(event(1))), transport.batches)
        assertEquals(listOf(event(0)), stored())
    }

    @Test
    fun `an immediate event that fails transiently joins the queue`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val dispatcher = dispatcher(maxWaitMs = 1_000)

        dispatcher.sendNow(event(0))
        scheduler.runCurrent()
        assertEquals(listOf(event(0)), stored())
        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()

        assertEquals(listOf(listOf(event(0)), listOf(event(0))), transport.batches)
        assertNull(storage.getString(KEY))
    }

    @Test
    fun `an immediate event that fails permanently is dropped`() {
        transport.failNextBatch(RpcCode.PERMISSION_DENIED)
        val dispatcher = dispatcher()

        dispatcher.sendNow(event(0))
        scheduler.advanceUntilIdle()

        assertEquals(1, transport.batches.size)
        assertNull(storage.getString(KEY))
        assertEquals(listOf("1 event dropped: rejected by the server (permission_denied: scripted)"), logger.errors)
    }

    @Test
    fun `an immediate event that fails after the queue was cleared is not queued again`() {
        val dispatcher = dispatcher()
        transport.hold()

        dispatcher.sendNow(event(0))
        scheduler.runCurrent()
        dispatcher.clear()
        transport.batchFailure = RpcCode.UNAVAILABLE
        transport.releaseAll()
        scheduler.advanceUntilIdle()

        assertNull(storage.getString(KEY))
        assertEquals(1, transport.batches.size)
    }

    @Test
    fun `a batch in flight when the queue is cleared is not put back`() {
        val dispatcher = dispatcher()
        transport.hold()

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.runCurrent()
        assertEquals(3, dispatcher.clear())
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        transport.releaseAll()
        scheduler.runCurrent()

        assertNull(storage.getString(KEY))
        assertEquals(0, dispatcher.undelivered)
    }

    @Test
    fun `a batch in flight when the queue is cleared does not back off`() {
        val dispatcher = dispatcher()
        transport.hold()

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.runCurrent()
        dispatcher.clear()
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        transport.releaseAll()
        scheduler.runCurrent()
        events(3 until 6).forEach(dispatcher::enqueue)
        scheduler.runCurrent()

        assertEquals(listOf(events(0 until 3), events(3 until 6)), transport.batches)
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `an immediate event in flight at dispose stays stored`() {
        val dispatcher = dispatcher()
        transport.hold()

        dispatcher.sendNow(event(0))
        scheduler.runCurrent()
        dispatcher.dispose()

        assertEquals(listOf(event(0)), stored())
        assertEquals(1, dispatcher.undelivered)
    }

    @Test
    fun `an immediate event in flight when the queue is cleared is not stored at dispose`() {
        val dispatcher = dispatcher()
        transport.hold()

        dispatcher.sendNow(event(0))
        scheduler.runCurrent()
        dispatcher.clear()
        dispatcher.dispose()

        assertNull(storage.getString(KEY))
    }

    @Test
    fun `restored events go out maxWaitMs after start`() {
        EventQueue(storage, KEY, logger.safe(), maxQueueSize = 100).push(event(0))
        val dispatcher = dispatcher(maxWaitMs = 5_000)

        dispatcher.start()
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()

        assertEquals(listOf(listOf(event(0))), transport.batches)
        assertEquals(listOf(5_000L), transport.batchTimes)
    }

    @Test
    fun `close stops timed sends, and queued events stay stored`() {
        val dispatcher = dispatcher()

        dispatcher.enqueue(event(0))
        dispatcher.close()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertEquals(listOf(event(0)), stored())
    }

    @Test
    fun `a failure after close is not retried`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val dispatcher = dispatcher()

        dispatcher.enqueue(event(0))
        dispatcher.close()
        dispatcher.drain()
        scheduler.advanceUntilIdle()

        assertEquals(1, transport.batches.size)
        assertEquals(listOf(event(0)), stored())
        assertEquals(listOf("Sending 1 event failed (unavailable: scripted); kept in the queue"), logger.warnings)
    }

    @Test
    fun `a send that finishes after dispose leaves the queue alone`() {
        val dispatcher = dispatcher()
        transport.hold()

        events(0 until 3).forEach(dispatcher::enqueue)
        scheduler.runCurrent()
        dispatcher.dispose()
        val stored = assertNotNull(storage.getString(KEY))
        transport.releaseAll()
        scheduler.advanceUntilIdle()

        assertEquals(stored, storage.getString(KEY))
        assertEquals(3, dispatcher.undelivered)
    }

    private fun dispatcher(maxSize: Int = 3, maxWaitMs: Long = 5_000, random: Random = FixedRandom()) = EventDispatcher(
        EventQueue(storage, KEY, logger.safe(), maxQueueSize = 100),
        transport,
        logger.safe(),
        scope,
        maxSize,
        maxWaitMs,
        random,
    )

    private fun stored(): List<Event> {
        val array = JSONArray(assertNotNull(storage.getString(KEY)))
        return List(array.length()) { assertNotNull(EventJson.decode(array.getJSONObject(it))) }
    }

    private fun events(range: IntRange) = range.map(::event)

    private fun event(n: Int) = Event(
        eventId = "00000000-0000-7000-8000-%012x".format(n + 1),
        kind = "event_$n",
        distinctId = "anon-1",
        sessionId = "00000000-0000-7000-8000-0000000000aa",
        occurTimeMillis = 1_760_000_000_000L + n,
        autoProperties = emptyMap(),
        customProperties = emptyMap(),
    )

    private companion object {
        const val KEY = "__pug_proj_1_queue__"
        const val MINUTE = 60_000L
    }
}
