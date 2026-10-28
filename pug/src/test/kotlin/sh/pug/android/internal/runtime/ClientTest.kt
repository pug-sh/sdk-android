@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android.internal.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import sh.pug.android.Logger
import sh.pug.android.MemoryStorage
import sh.pug.android.Options
import sh.pug.android.TrackOptions
import sh.pug.android.TrackingConsent.DENIED
import sh.pug.android.TrackingConsent.GRANTED
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.ThrowingStorage
import sh.pug.android.internal.isUuid
import sh.pug.android.internal.platform.FakePlatform
import sh.pug.android.internal.queue.EventJson
import sh.pug.android.internal.storage.StorageKeys
import sh.pug.android.internal.transport.RpcCode
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.IdentifyRequest
import sh.pug.android.internal.wire.JsonValue
import sh.pug.android.internal.wire.PropertyValue

class ClientTest {
    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private val transport = FakeTransport { scheduler.currentTime }
    private val logger = RecordingLogger()
    private val storage = MemoryStorage()
    private val keys = StorageKeys(PROJECT)
    private val ids = SequentialIds()
    private val platform = FakePlatform()

    @Test
    fun `an event carries the session, the anonymous id and the time of the call`() {
        val client = client { batchMaxSize = 1 }

        scheduler.advanceTimeBy(1_000)
        client.track("add_to_cart", mapOf("productId" to "p1", "quantity" to 2), null)
        scheduler.runCurrent()

        val event = transport.batches.single().single()
        assertEquals("add_to_cart", event.kind)
        assertEquals(START + 1_000, event.occurTimeMillis)
        assertTrue(event.distinctId.startsWith("anon-"))
        assertTrue(event.sessionId.isUuid())
        assertEquals(
            mapOf("productId" to PropertyValue.StringValue("p1"), "quantity" to PropertyValue.IntValue(2)),
            event.customProperties,
        )
        assertEquals(PropertyValue.StringValue(PROJECT), event.autoProperties["\$projectId"])
    }

    @Test
    fun `properties are read during the call`() {
        val client = client { batchMaxSize = 1 }
        val count = AtomicInteger(1)
        val tags = mutableListOf("a")
        val properties = mutableMapOf<String, Any?>("count" to count, "tags" to tags)

        client.track("tick", properties, null)
        count.set(2)
        tags += "b"
        properties["late"] = true
        scheduler.runCurrent()

        assertEquals(
            mapOf("count" to PropertyValue.IntValue(1), "tags" to PropertyValue.StringValue("[\"a\"]")),
            transport.batches.single().single().customProperties,
        )
    }

    @Test
    fun `an untyped well-known event without a required property gets a debug hint`() {
        val client = client()

        client.track("add_to_cart", mapOf("price" to 10), null)
        client.track("add_to_cart", mapOf("productId" to "p1"), null)
        client.track("checkout", null, null)

        assertEquals(
            listOf("Event \"add_to_cart\" is missing its required \"productId\""),
            logger.debugs.filter { "missing" in it },
        )
    }

    @Test
    fun `a timestamp option sets when the event happened`() {
        val client = client { batchMaxSize = 1 }

        client.track("imported", null, TrackOptions { timestamp = 42L })
        scheduler.runCurrent()

        assertEquals(42L, transport.batches.single().single().occurTimeMillis)
    }

    @Test
    fun `an invalid kind is dropped`() {
        val client = client()

        client.track("add to cart", null, null)
        client.track(" ", null, null)
        client.flush()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertEquals(2, logger.warnings.size)
    }

    @Test
    fun `an immediate event is sent on its own`() {
        val client = client()

        client.track("queued", null, null)
        client.track("urgent", null, TrackOptions { immediate = true })
        scheduler.runCurrent()

        assertEquals(listOf(listOf("urgent")), transport.kinds)
    }

    @Test
    fun `nothing is tracked or identified while consent is denied`() {
        val client = client { defaultTrackingConsent = DENIED }

        client.track("a", null, null)
        client.identify("user-1", null)
        client.rotateSession()
        client.flush()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertEquals(emptyList(), transport.identifies)
        assertTrue(keys.all.all { storage.getString(it) == null })
        assertTrue("Event \"a\" was dropped: tracking consent is denied" in logger.debugs)
        assertTrue("identify() was dropped: tracking consent is denied" in logger.debugs)
    }

    @Test
    fun `dryRun logs events and identify without sending them`() {
        val client = client { dryRun = true }

        client.track("a", null, TrackOptions { immediate = true })
        client.identify("user-1", null)
        client.flush()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertEquals(emptyList(), transport.identifies)
        assertTrue("dryRun: event \"a\" was not sent" in logger.debugs)
        assertTrue("dryRun: identify() was not sent" in logger.debugs)
        assertEquals(listOf("dryRun is on: events and identify() are logged, never sent"), logger.warnings)
    }

    @Test
    fun `the first identify links the anonymous id, and later events carry the external id`() {
        val client = client { batchMaxSize = 1 }

        client.track("before", null, null)
        client.identify("user-1", mapOf("plan" to "pro"))
        scheduler.runCurrent()
        val anonymousId = transport.batches.single().single().distinctId
        client.track("after", null, null)
        client.identify("user-1", null)
        scheduler.runCurrent()

        assertEquals(
            listOf(
                IdentifyRequest("user-1", mapOf("plan" to JsonValue.StringValue("pro")), anonymousId),
                IdentifyRequest("user-1", emptyMap(), anonymousId = null),
            ),
            transport.identifies,
        )
        assertEquals("user-1", transport.batches.last().single().distinctId)
        assertEquals("user-1", storage.getString(keys.externalId))
    }

    @Test
    fun `a failed identify is logged and leaves the user anonymous`() {
        transport.failNextIdentify(RpcCode.INVALID_ARGUMENT)
        val client = client()

        client.identify("user-1", null)
        scheduler.runCurrent()

        assertEquals(listOf("identify() failed (invalid_argument: scripted)"), logger.errors)
        assertNull(storage.getString(keys.externalId))
    }

    @Test
    fun `identify drops blank and reserved ids`() {
        val client = client()

        client.identify(" ", null)
        client.identify("cookieless-abc", null)
        scheduler.runCurrent()

        assertEquals(emptyList(), transport.identifies)
        assertEquals(
            listOf(
                "identify() was dropped: externalId is blank",
                "identify() was dropped: externalId may not start with \"cookieless-\"",
            ),
            logger.errors,
        )
    }

    @Test
    fun `a reset during identify wins`() {
        val client = client()
        transport.hold()

        client.identify("user-1", null)
        scheduler.runCurrent()
        client.reset()
        scheduler.runCurrent()
        transport.releaseAll()
        scheduler.runCurrent()

        assertNull(storage.getString(keys.externalId))
        assertTrue("identify() finished after the identity was reset; it was not applied" in logger.debugs)
    }

    @Test
    fun `an opt-out during identify wins`() {
        val client = client()
        transport.hold()

        client.identify("user-1", null)
        scheduler.runCurrent()
        client.optOutTracking()
        client.optInTracking()
        scheduler.runCurrent()
        transport.releaseAll()
        scheduler.runCurrent()

        assertNull(storage.getString(keys.externalId))
        assertNull(storage.getString(keys.profile))
    }

    @Test
    fun `the last identify called wins, whichever finishes last`() {
        val client = client()
        transport.hold()

        client.identify("user-1", null)
        client.identify("user-2", null)
        scheduler.runCurrent()
        transport.release(1)
        scheduler.runCurrent()
        transport.release(0)
        scheduler.runCurrent()

        assertEquals("user-2", storage.getString(keys.externalId))
        assertTrue("identify() finished after a later identify(); the later one stands" in logger.debugs)
    }

    @Test
    fun `reset starts a new identity and sends what the last user queued`() {
        val client = client()
        client.identify("user-1", null)
        scheduler.runCurrent()
        client.track("before", null, null)
        scheduler.runCurrent()

        client.reset()
        scheduler.runCurrent()
        client.track("after", null, null)
        client.flush()
        scheduler.runCurrent()

        val (before, after) = transport.batches.map { it.single() }
        assertEquals("before", before.kind)
        assertEquals("user-1", before.distinctId)
        assertTrue(after.distinctId.startsWith("anon-"))
        assertNotEquals(before.sessionId, after.sessionId)
        assertNull(storage.getString(keys.externalId))
    }

    @Test
    fun `reset while consent is denied clears an identity kept from before`() {
        storage.putString(keys.profile, """{"anonymousId":"anon-1","externalId":"user-1"}""")
        storage.putString(keys.externalId, "user-1")
        val client = client { defaultTrackingConsent = DENIED }

        client.reset()
        scheduler.runCurrent()

        assertNull(storage.getString(keys.profile))
        assertNull(storage.getString(keys.externalId))
    }

    @Test
    fun `rotateSession starts a new session for the same user`() {
        val client = client { batchMaxSize = 1 }

        client.track("a", null, null)
        client.rotateSession()
        client.track("b", null, null)
        scheduler.runCurrent()

        val (a, b) = transport.batches.map { it.single() }
        assertNotEquals(a.sessionId, b.sessionId)
        assertEquals(a.distinctId, b.distinctId)
    }

    @Test
    fun `opting out drops queued events unsent and forgets the identity`() {
        val client = client()
        client.identify("user-1", null)
        client.track("a", null, null)
        scheduler.runCurrent()

        client.optOutTracking()
        client.flush()
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertTrue((keys.all - keys.consent).all { storage.getString(it) == null })
        assertEquals("denied", storage.getString(keys.consent))
        assertTrue("Dropped 1 event unsent: tracking consent is denied" in logger.debugs)
    }

    @Test
    fun `opting back in starts a fresh identity`() {
        val client = client { batchMaxSize = 1 }
        client.track("a", null, null)
        scheduler.runCurrent()

        client.optOutTracking()
        client.optInTracking()
        client.track("b", null, null)
        scheduler.runCurrent()

        val (a, b) = transport.batches.map { it.single() }
        assertNotEquals(a.distinctId, b.distinctId)
        assertNotEquals(a.sessionId, b.sessionId)
    }

    @Test
    fun `a stored opt-out purges the stored identity and queue at start`() {
        storage.putString(keys.consent, "denied")
        storage.putString(keys.profile, """{"anonymousId":"anon-1"}""")
        storage.putString(keys.queue, "[${EventJson.encode(event("a"))}]")
        val client = client()

        scheduler.runCurrent()

        assertNull(storage.getString(keys.profile))
        assertNull(storage.getString(keys.queue))
        assertEquals("denied", storage.getString(keys.consent))
        assertEquals(DENIED, client.trackingConsent)
        assertFalse(client.isConsentPending)
        assertFalse(client.isTrackingEnabled)
    }

    @Test
    fun `consent getters answer with the latest choice at once`() {
        val client = client()

        client.optOutTracking()

        assertEquals(DENIED, client.trackingConsent)
        assertFalse(client.isConsentPending)
        assertFalse(client.isTrackingEnabled)
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `consent getters report the restored choice once started`() {
        storage.putString(keys.consent, "granted")
        val client = client { defaultTrackingConsent = DENIED }

        scheduler.runCurrent()

        assertEquals(GRANTED, client.trackingConsent)
        assertFalse(client.isConsentPending)
        assertTrue(client.isTrackingEnabled)
    }

    @Test
    fun `consent getters report the default as pending`() {
        val client = client()

        scheduler.runCurrent()

        assertEquals(GRANTED, client.trackingConsent)
        assertTrue(client.isConsentPending)
    }

    @Test
    fun `consent getters answer unknown at once before start-up has read consent`() {
        val client = client(previousTeardown = Job())

        scheduler.runCurrent()

        assertNull(client.trackingConsent)
        assertTrue(client.isConsentPending)
        assertFalse(client.isTrackingEnabled)
        assertEquals(
            listOf(
                "Tracking consent was asked for before Pug read it from storage; it is reported as unknown. " +
                    "Ask from Pug.whenConsentReady() instead",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `whenConsentReady runs on the main thread once start-up has read the stored choice`() {
        storage.putString(keys.consent, "granted")
        val previousTeardown = Job()
        val client = client(previousTeardown) { defaultTrackingConsent = DENIED }
        val seen = mutableListOf<Pair<Any?, Boolean>>()

        client.whenConsentReady { seen += client.trackingConsent to client.isConsentPending }
        scheduler.runCurrent()
        platform.runMainThread()
        assertEquals(emptyList(), seen)

        previousTeardown.complete()
        scheduler.runCurrent()
        assertEquals(emptyList(), seen)
        platform.runMainThread()

        assertEquals(listOf<Pair<Any?, Boolean>>(GRANTED to false), seen)
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `whenConsentReady after start-up runs on the main thread's next turn, once`() {
        val client = client()
        scheduler.runCurrent()
        var runs = 0

        client.whenConsentReady { runs++ }
        assertEquals(0, runs)
        platform.runMainThread()
        platform.runMainThread()

        assertEquals(1, runs)
    }

    @Test
    fun `a throwing whenConsentReady callback is contained`() {
        val client = client()
        scheduler.runCurrent()
        var ran = false

        client.whenConsentReady { throw IllegalStateException("boom") }
        client.whenConsentReady { ran = true }
        platform.runMainThread()

        assertTrue(ran)
        assertEquals(listOf("The callback given to Pug.whenConsentReady() failed"), logger.errors)
    }

    @Test
    fun `nothing runs until the previous client's teardown is over, then calls run in order`() {
        val previous = Job()
        val client = client(previousTeardown = previous) { batchMaxSize = 1 }

        client.track("a", null, null)
        client.optOutTracking()
        client.optInTracking()
        client.track("b", null, null)
        scheduler.advanceUntilIdle()
        assertTrue(keys.all.all { storage.getString(it) == null })
        assertEquals(emptyList(), transport.batches)
        previous.complete()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("b")), transport.kinds)
    }

    @Test
    fun `flush sends now, even while a retry is pending`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val client = client {
            batchMaxSize = 1
            batchMaxWaitMs = 60_000
        }

        client.track("a", null, null)
        scheduler.runCurrent()
        client.flush()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a"), listOf("a")), transport.kinds)
        assertNull(storage.getString(keys.queue))
    }

    @Test
    fun `destroy sends what is queued and keeps the identity`() {
        val client = client()
        client.identify("user-1", null)
        client.track("a", null, null)

        val teardown = client.destroy()
        scheduler.runCurrent()

        assertTrue(teardown.isCompleted)
        assertEquals(listOf(listOf("a")), transport.kinds)
        assertNull(storage.getString(keys.queue))
        assertEquals("user-1", storage.getString(keys.externalId))
        assertNotNull(storage.getString(keys.session))
    }

    @Test
    fun `destroy waits for a batch in flight before letting go of storage`() {
        val client = client { batchMaxSize = 2 }
        transport.hold()
        client.track("a", null, null)
        client.track("b", null, null)
        scheduler.runCurrent()

        client.track("c", null, null)
        val teardown = client.destroy()
        scheduler.advanceTimeBy(5_000)
        assertFalse(teardown.isCompleted)
        transport.releaseAll()
        scheduler.runCurrent()

        assertTrue(teardown.isCompleted)
        assertEquals(listOf(listOf("a", "b"), listOf("c")), transport.kinds)
        assertNull(storage.getString(keys.queue))
    }

    @Test
    fun `destroy waits for an identify and an immediate event in flight`() {
        val client = client()
        transport.hold()
        client.identify("user-1", null)
        client.track("x", null, TrackOptions { immediate = true })
        scheduler.runCurrent()

        val teardown = client.destroy()
        scheduler.advanceTimeBy(5_000)
        assertFalse(teardown.isCompleted)
        transport.releaseAll()
        scheduler.runCurrent()

        assertTrue(teardown.isCompleted)
        assertEquals("user-1", storage.getString(keys.externalId))
        assertEquals(listOf(listOf("x")), transport.kinds)
        assertNull(storage.getString(keys.queue))
    }

    @Test
    fun `an immediate event that destroy stops waiting for is kept`() {
        val client = client()
        transport.hold()
        client.track("x", null, TrackOptions { immediate = true })

        client.destroy()
        scheduler.advanceTimeBy(TEARDOWN_TIMEOUT_MS)
        scheduler.runCurrent()

        assertEquals(listOf("x"), stored().map { it.kind })
    }

    @Test
    fun `destroy stops waiting for the network after ten seconds and keeps the events`() {
        val client = client()
        transport.hold()
        client.track("a", null, null)

        val teardown = client.destroy()
        scheduler.advanceTimeBy(TEARDOWN_TIMEOUT_MS - 1)
        assertFalse(teardown.isCompleted)
        scheduler.advanceTimeBy(1)
        scheduler.runCurrent()
        assertTrue(teardown.isCompleted)
        transport.releaseAll()
        scheduler.advanceUntilIdle()

        assertEquals(listOf("a"), stored().map { it.kind })
        assertEquals(listOf("Pug.destroy() stopped waiting for the network after 10000 ms"), logger.warnings)
    }

    @Test
    fun `a send failing during destroy is not retried`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val client = client()
        client.track("a", null, null)

        val teardown = client.destroy()
        scheduler.advanceUntilIdle()

        assertTrue(teardown.isCompleted)
        assertEquals(1, transport.batches.size)
        assertEquals(listOf("a"), stored().map { it.kind })
    }

    @Test
    fun `events a destroyed client could not send go out with the next one`() {
        transport.batchFailure = RpcCode.UNAVAILABLE
        val first = client()
        first.track("a", null, null)
        val teardown = first.destroy()
        scheduler.advanceUntilIdle()
        transport.batchFailure = null

        val second = client(previousTeardown = teardown)
        second.flush()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a"), listOf("a")), transport.kinds)
        assertNull(storage.getString(keys.queue))
    }

    @Test
    fun `calls after destroy are ignored`() {
        val client = client()

        client.destroy()
        client.track("late", null, null)
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertTrue("Pug.track() was ignored: Pug was destroyed" in logger.debugs)
    }

    @Test
    fun `a failing storage falls back to memory and tracking goes on`() {
        val client = client {
            storage = ThrowingStorage(IllegalStateException("disk full"))
            batchMaxSize = 1
        }

        client.track("a", null, null)
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a")), transport.kinds)
        assertEquals(
            listOf("Storage failed; state is kept in memory only until the app restarts"),
            logger.warnings,
        )
    }

    @Test
    fun `a throwing logger changes nothing`() {
        val throwing =
            object : Logger {
                override fun debug(message: String): Unit = throw IllegalStateException("debug")

                override fun warn(message: String, throwable: Throwable?): Unit = throw IllegalStateException("warn")

                override fun error(message: String, throwable: Throwable?): Unit = throw IllegalStateException("error")
            }
        val client = client {
            logger = throwing
            batchMaxSize = 1
        }

        client.track("bad kind", null, null)
        client.track("a", null, null)
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a")), transport.kinds)
    }

    private fun client(previousTeardown: Job? = null, configure: Options.Builder.() -> Unit = {}): Client {
        val options =
            Options("pub_key") {
                logger = this@ClientTest.logger
                storage = this@ClientTest.storage
                debug = true
                configure()
            }
        return Client(
            PROJECT,
            options,
            transport,
            worker,
            platform,
            clock = { START + scheduler.currentTime },
            ids = ids,
            random = FixedRandom(),
            sdkInt = 26,
        ).also { it.start(previousTeardown) }
    }

    private fun stored(): List<Event> {
        val array = JSONArray(assertNotNull(storage.getString(keys.queue)))
        return List(array.length()) { assertNotNull(EventJson.decode(array.getJSONObject(it))) }
    }

    private fun event(kind: String) = Event(
        eventId = "00000000-0000-7000-8000-0000000000ff",
        kind = kind,
        distinctId = "anon-1",
        sessionId = "00000000-0000-7000-8000-0000000000aa",
        occurTimeMillis = START,
        autoProperties = emptyMap(),
        customProperties = emptyMap(),
    )

    private companion object {
        const val PROJECT = "proj_1"
        const val START = 1_760_000_000_000L
    }
}
