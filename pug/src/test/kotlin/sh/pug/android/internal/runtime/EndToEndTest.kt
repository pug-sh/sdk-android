package sh.pug.android.internal.runtime

import com.google.protobuf.DynamicMessage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import sh.pug.android.MemoryPugStorage
import sh.pug.android.PugOptions
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.platform.FakePlatform
import sh.pug.android.internal.storage.PugStorageKeys
import sh.pug.android.internal.transport.HttpPugTransport
import sh.pug.android.internal.wire.ReferenceMessages

/** The whole pipeline on a real worker, over HTTP, with requests decoded by protobuf-java. */
class EndToEndTest {
    private val server = MockWebServer()

    @BeforeTest
    fun startServer() {
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.close()
    }

    @Test
    fun `tracked events reach the server as one batch`() {
        server.enqueue(protobufResponse(ReferenceMessages.batchResponse(accepted = 3, dropped = 0, emptyMap())))
        val client = client { batch(maxSize = 3) }

        client.track("checkout_started", mapOf("cartId" to "c1", "items" to 2), null)
        client.track("add_to_cart", mapOf("price" to 9.99), null)
        client.track("purchase", null, null)

        val request = server.takeRequestWithin()
        assertEquals("/sdk.events.v1.EventsService/BatchCreate", request.url.encodedPath)
        val events = request.decodeEvents()
        assertEquals(listOf("checkout_started", "add_to_cart", "purchase"), events.map { it.string("kind") })
        assertEquals(1, events.map { it.string("session_id") }.distinct().size)
        assertEquals(1, events.map { it.string("distinct_id") }.distinct().size)
        assertTrue(events.first().string("distinct_id").startsWith("anon-"))
        assertEquals(mapOf("cartId" to "c1", "items" to 2L), events[0].properties("custom_properties"))
        assertEquals(mapOf("price" to 9.99), events[1].properties("custom_properties"))
        assertEquals(PROJECT, events[2].properties("auto_properties")["\$projectId"])
        destroy(client)
    }

    @Test
    fun `identify reaches the server with the anonymous id`() {
        server.enqueue(protobufResponse(ByteArray(0)))
        server.enqueue(protobufResponse(ReferenceMessages.batchResponse(accepted = 1, dropped = 0, emptyMap())))
        val storage = MemoryPugStorage()
        val client = client(storage) { batch(maxSize = 1) }

        client.identify("user-42", mapOf("plan" to "pro"))
        val identify = server.takeRequestWithin()
        awaitLinked(storage)
        client.track("after", null, null)
        val batch = server.takeRequestWithin()

        assertEquals("/sdk.profiles.v1.ProfilesSDKService/Identify", identify.url.encodedPath)
        val message = DynamicMessage.parseFrom(ReferenceMessages.identifyRequest, identify.bodyBytes())
        assertEquals("user-42", message.string("external_id"))
        assertTrue(message.string("anonymous_id").startsWith("anon-"))
        assertEquals("user-42", batch.decodeEvents().single().string("distinct_id"))
        destroy(client)
    }

    @Test
    fun `a batch the server could not take is sent again`() {
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(protobufResponse(ReferenceMessages.batchResponse(accepted = 1, dropped = 0, emptyMap())))
        val client = client { batch(maxSize = 1, maxWaitMs = 0) }

        client.track("app_open", null, null)
        val failed = server.takeRequestWithin()
        val retried = server.takeRequestWithin()

        assertContentEquals(failed.bodyBytes(), retried.bodyBytes())
        destroy(client)
    }

    private fun client(
        storage: MemoryPugStorage = MemoryPugStorage(),
        configure: PugOptions.Builder.() -> Unit,
    ): PugClient {
        val options =
            PugOptions("pub_test") {
                endpoint = server.url("/").toString()
                this.storage = storage
                configure()
            }
        val transport = HttpPugTransport(options.endpoint, options.apiKey, 5_000, 5_000)
        val worker = Dispatchers.IO.limitedParallelism(1)
        return PugClient(PROJECT, options, transport, worker, FakePlatform(), random = FixedRandom())
            .also { it.start(null) }
    }

    // The link lands on the worker after the response, so it can trail the server seeing the request.
    private fun awaitLinked(storage: MemoryPugStorage) = runBlocking {
        withTimeout(10_000) {
            while (storage.getString(PugStorageKeys(PROJECT).externalId) == null) delay(10)
        }
    }

    private fun destroy(client: PugClient) = runBlocking {
        withTimeout(10_000) { client.destroy().join() }
    }

    private companion object {
        const val PROJECT = "proj_e2e"
    }
}
