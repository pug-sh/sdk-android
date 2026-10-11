package sh.pug.android.internal.queue

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import sh.pug.android.MemoryPugStorage
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.event.MAX_BATCH_BYTES
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue
import sh.pug.android.internal.wire.PugWire

class EventQueueTest {
    private val storage = MemoryPugStorage()
    private val logger = RecordingLogger()

    @Test
    fun `a locked batch is removed on commit`() {
        val queue = queue()
        val events = List(3) { event(it) }
        events.forEach(queue::push)

        val batch = assertNotNull(queue.lock(maxCount = 2, maxBytes = MAX_BATCH_BYTES))

        assertEquals(events.take(2), batch.events)
        assertEquals(1, queue.size)
        assertEquals(3, queue.totalSize)
        queue.commit(batch)
        assertEquals(1, queue.totalSize)
        assertEquals(listOf(events[2]), stored())
    }

    @Test
    fun `a rolled back batch returns to the front in order`() {
        val queue = queue()
        val events = List(3) { event(it) }
        events.forEach(queue::push)

        val batch = assertNotNull(queue.lock(maxCount = 2, maxBytes = MAX_BATCH_BYTES))
        queue.push(event(3))
        queue.rollback(batch)

        val next = assertNotNull(queue.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES))
        assertEquals(events + event(3), next.events)
    }

    @Test
    fun `only one batch is locked at a time`() {
        val queue = queue()
        queue.push(event(0))
        queue.push(event(1))

        val batch = assertNotNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES))

        assertNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES))
        queue.commit(batch)
        assertNotNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES))
    }

    @Test
    fun `nothing to lock in an empty queue`() {
        assertNull(queue().lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES))
    }

    @Test
    fun `a full queue drops its oldest pending event`() {
        val queue = queue(maxQueueSize = 3)
        val events = List(5) { event(it) }

        events.forEach(queue::push)

        assertEquals(events.takeLast(3), stored())
        assertEquals(
            listOf(
                "The event queue is full (3 events); the oldest event was dropped. Later drops are logged at debug level",
            ),
            logger.warnings,
        )
        assertEquals(listOf("The event queue is full; the oldest event was dropped"), logger.debugs)
    }

    @Test
    fun `a full queue with everything in flight drops the new event`() {
        val queue = queue(maxQueueSize = 2)
        queue.push(event(0))
        queue.push(event(1))
        val batch = assertNotNull(queue.lock(maxCount = 2, maxBytes = MAX_BATCH_BYTES))

        queue.push(event(2))

        assertEquals(2, queue.totalSize)
        queue.rollback(batch)
        assertEquals(listOf(event(0), event(1)), queue.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES)?.events)
        assertTrue(logger.warnings.single().contains("the new event was dropped"))
    }

    @Test
    fun `a full queue warns again after it drains`() {
        val queue = queue(maxQueueSize = 1)
        queue.push(event(0))
        queue.push(event(1))
        queue.commit(assertNotNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES)))

        queue.push(event(2))
        queue.push(event(3))

        assertEquals(2, logger.warnings.size)
    }

    @Test
    fun `the queue survives a restart, in-flight batch first`() {
        val queue = queue()
        val events = List(3) { event(it) }
        events.forEach(queue::push)
        queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES)
        queue.push(event(3))

        val restored = queue()

        assertEquals(events + event(3), restored.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES)?.events)
    }

    @Test
    fun `an empty queue leaves nothing in storage`() {
        val queue = queue()
        queue.push(event(0))

        queue.commit(assertNotNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES)))

        assertNull(storage.getString(KEY))
    }

    @Test
    fun `clearing drops everything, the locked batch included`() {
        val queue = queue()
        List(3) { event(it) }.forEach(queue::push)
        val batch = assertNotNull(queue.lock(maxCount = 2, maxBytes = MAX_BATCH_BYTES))

        assertEquals(3, queue.clear())

        assertEquals(0, queue.totalSize)
        assertNull(storage.getString(KEY))
        queue.rollback(batch)
        queue.commit(batch)
        assertEquals(0, queue.totalSize)
        assertNull(storage.getString(KEY))
    }

    @Test
    fun `a batch from before a clear cannot touch the queue after it`() {
        val queue = queue()
        queue.push(event(0))
        val stale = assertNotNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES))
        queue.clear()
        queue.push(event(1))
        val current = assertNotNull(queue.lock(maxCount = 1, maxBytes = MAX_BATCH_BYTES))

        queue.commit(stale)

        assertEquals(1, queue.totalSize)
        queue.rollback(current)
        assertEquals(listOf(event(1)), queue.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES)?.events)
    }

    @Test
    fun `a batch stops before exceeding the byte budget`() {
        val queue = queue()
        val events = List(4) { event(it) }
        events.forEach(queue::push)
        val size = PugWire.eventSize(events[0])

        val batch = assertNotNull(queue.lock(maxCount = 10, maxBytes = 3 * size - 1))

        assertEquals(events.take(2), batch.events)
        assertEquals(2, queue.size)
        assertEquals(
            PugWire.encodeBatchCreateRequest(batch.events).size,
            batch.events.sumOf { PugWire.eventSize(it) },
        )
    }

    @Test
    fun `an event that alone exceeds the byte budget is dropped`() {
        val queue = queue()
        val large = event(0, custom = mapOf("blob" to PropertyValue.StringValue("x".repeat(1_000))))
        queue.push(large)
        queue.push(event(1))

        val batch = assertNotNull(queue.lock(maxCount = 10, maxBytes = PugWire.eventSize(large) - 1))

        assertEquals(listOf(event(1)), batch.events)
        assertEquals(1, queue.totalSize)
        assertTrue(logger.errors.single().startsWith("A queued \"kind_0\" event of"))
        assertEquals(listOf(event(1)), stored())
    }

    @Test
    fun `a repeated event id is not sent twice in one batch`() {
        val queue = queue()
        queue.push(event(0))
        queue.push(event(0))
        queue.push(event(1))

        val batch = assertNotNull(queue.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES))

        assertEquals(listOf(event(0), event(1)), batch.events)
        assertEquals(listOf(event(0), event(1)), stored())
        assertEquals(1, logger.warnings.size)
    }

    @Test
    fun `unreadable stored queues are discarded`() {
        for (raw in listOf("not json", "{}", "\"string\"")) {
            storage.putString(KEY, raw)

            val queue = queue()

            assertEquals(0, queue.totalSize)
            assertNull(storage.getString(KEY))
        }
        assertEquals(List(3) { "The stored event queue could not be read and was discarded" }, logger.warnings)
    }

    @Test
    fun `unreadable stored events are skipped and the rest kept`() {
        val good = listOf(event(0), event(1))
        val array = JSONArray()
        array.put(org.json.JSONObject(EventJson.encode(good[0])))
        array.put("garbage")
        array.put(org.json.JSONObject("""{"eventId":"x"}"""))
        array.put(org.json.JSONObject(EventJson.encode(good[1])))
        storage.putString(KEY, array.toString())

        val queue = queue()

        assertEquals(good, queue.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES)?.events)
        assertEquals(listOf("2 stored events could not be read and were dropped"), logger.warnings)
        assertEquals(good, stored())
    }

    @Test
    fun `stored events with a reserved distinct id are dropped`() {
        val healthy = event(0)
        val poisoned = event(1).copy(distinctId = "cookieless-abc")
        storage.putString(KEY, "[${EventJson.encode(poisoned)},${EventJson.encode(healthy)}]")

        val queue = queue()

        assertEquals(1, queue.totalSize)
        assertEquals(listOf(healthy), stored())
        assertTrue(
            logger.warnings.single().startsWith("1 stored events were dropped: their distinct id uses the reserved"),
        )
    }

    @Test
    fun `a stored queue larger than the limit keeps its newest events`() {
        val events = List(5) { event(it) }
        storage.putString(KEY, events.joinToString(",", "[", "]") { EventJson.encode(it) })

        val queue = queue(maxQueueSize = 3)

        assertEquals(events.takeLast(3), queue.lock(maxCount = 10, maxBytes = MAX_BATCH_BYTES)?.events)
        assertEquals(listOf("2 of the oldest stored events were dropped to fit maxQueueSize 3"), logger.warnings)
    }

    @Test
    fun `a healthy stored queue is not rewritten on load`() {
        val raw = List(2) { event(it) }.joinToString(",", "[", "]") { EventJson.encode(it) }
        storage.putString(KEY, raw)

        queue()

        assertEquals(raw, storage.getString(KEY))
        assertEquals(emptyList(), logger.entries)
    }

    private fun queue(maxQueueSize: Int = 1_000) = EventQueue(storage, KEY, logger.safe(), maxQueueSize)

    private fun stored(): List<Event> {
        val array = JSONArray(storage.getString(KEY) ?: return emptyList())
        return List(array.length()) { assertNotNull(EventJson.decode(array.getJSONObject(it))) }
    }

    private fun event(index: Int, custom: Map<String, PropertyValue> = emptyMap()) = Event(
        eventId = "00000000-0000-7000-8000-%012x".format(index),
        kind = "kind_$index",
        distinctId = "anon-1",
        sessionId = "00000000-0000-7000-8000-0000000000aa",
        occurTimeMillis = 1_760_000_000_000L + index,
        autoProperties = mapOf("\$platform" to PropertyValue.StringValue("android")),
        customProperties = custom + ("index" to PropertyValue.IntValue(index.toLong())),
    )

    private companion object {
        const val KEY = "__pug_proj_1_queue__"
    }
}
