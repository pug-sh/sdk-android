package sh.pug.android.internal.transport

import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okio.Buffer
import sh.pug.android.SDK_VERSION
import sh.pug.android.internal.wire.BatchCreateResponse
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.IdentifyRequest
import sh.pug.android.internal.wire.JsonValue
import sh.pug.android.internal.wire.PropertyValue
import sh.pug.android.internal.wire.ReferenceMessages
import sh.pug.android.internal.wire.Wire

class HttpTransportTest {
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
    fun `sendBatch posts a Connect unary request and returns the acknowledgement`() = runBlocking {
        server.enqueue(
            protobuf(
                ReferenceMessages.batchResponse(
                    accepted = 1,
                    dropped = 1,
                    droppedByReason = mapOf("salt_unavailable" to 1),
                ),
            ),
        )
        val events = listOf(event("first"), event("second"))

        val response = transport().sendBatch(events)

        assertEquals(BatchCreateResponse(1, mapOf("salt_unavailable" to 1L)), response)
        val request = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/sdk.events.v1.EventsService/BatchCreate", request.url.encodedPath)
        assertEquals("application/proto", request.headers["Content-Type"])
        assertEquals("1", request.headers["Connect-Protocol-Version"])
        assertEquals("pub_test", request.headers["X-Api-Key"])
        assertEquals("pug-android/$SDK_VERSION", request.headers["User-Agent"])
        assertContentEquals(Wire.encodeBatchCreateRequest(events), assertNotNull(request.body).toByteArray())
    }

    @Test
    fun `identify posts to the profiles service`() = runBlocking {
        server.enqueue(protobuf(ByteArray(0)))
        val request =
            IdentifyRequest("user-42", mapOf("plan" to JsonValue.StringValue("pro")), anonymousId = "anon-1")

        transport().identify(request)

        val recorded = assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("/sdk.profiles.v1.ProfilesSDKService/Identify", recorded.url.encodedPath)
        assertContentEquals(Wire.encodeIdentifyRequest(request), assertNotNull(recorded.body).toByteArray())
    }

    @Test
    fun `endpoint path prefix is kept and trailing slashes are dropped`() = runBlocking {
        server.enqueue(protobuf(ByteArray(0)))

        transport(endpoint = server.url("/ingest//").toString()).sendBatch(listOf(event("first")))

        assertEquals(
            "/ingest/sdk.events.v1.EventsService/BatchCreate",
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).url.encodedPath,
        )
    }

    @Test
    fun `connect error bodies map to their code`() = runBlocking {
        for (code in RpcCode.entries) {
            server.enqueue(connectError(400, code.wireName, "rejected: ${code.wireName}"))

            val error = sendFailure()

            assertEquals(code, error.code)
            assertEquals("rejected: ${code.wireName}", error.message)
            assertEquals(code.isPermanent, error.isPermanent, "permanence of ${code.wireName}")
        }
    }

    @Test
    fun `permanent codes are the ones retrying cannot fix`() {
        val permanent = RpcCode.entries.filter { it.isPermanent }.map { it.wireName }.toSet()

        assertEquals(
            setOf(
                "invalid_argument",
                "not_found",
                "already_exists",
                "permission_denied",
                "failed_precondition",
                "unimplemented",
                "unauthenticated",
            ),
            permanent,
        )
    }

    @Test
    fun `non-connect error bodies fall back to the HTTP status`() = runBlocking {
        val expectations =
            listOf(
                400 to RpcCode.INVALID_ARGUMENT,
                401 to RpcCode.UNAUTHENTICATED,
                403 to RpcCode.PERMISSION_DENIED,
                404 to RpcCode.UNIMPLEMENTED,
                408 to RpcCode.UNAVAILABLE,
                413 to RpcCode.INVALID_ARGUMENT,
                429 to RpcCode.UNAVAILABLE,
                500 to RpcCode.UNAVAILABLE,
                502 to RpcCode.UNAVAILABLE,
                503 to RpcCode.UNAVAILABLE,
                504 to RpcCode.UNAVAILABLE,
            )
        for ((status, code) in expectations) {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(status)
                    .setHeader("Content-Type", "text/html")
                    .body("<html>proxy page</html>")
                    .build(),
            )

            val error = sendFailure()

            assertEquals(code, error.code, "HTTP $status")
            assertEquals("HTTP $status", error.message)
        }
    }

    @Test
    fun `an unknown connect code falls back to the status but keeps the message`() = runBlocking {
        server.enqueue(connectError(503, "brand_new_code", "try later"))
        server.enqueue(connectError(422, "brand_new_code", ""))

        val transient = sendFailure()
        val permanent = sendFailure()

        assertEquals(RpcCode.UNAVAILABLE, transient.code)
        assertEquals("try later", transient.message)
        assertEquals(RpcCode.INVALID_ARGUMENT, permanent.code)
        assertEquals("HTTP 422", permanent.message)
    }

    @Test
    fun `a bodyless error maps from the status`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).build())

        val error = sendFailure()

        assertEquals(RpcCode.UNAUTHENTICATED, error.code)
        assertTrue(error.isPermanent)
    }

    @Test
    fun `a non-protobuf 2xx is retried rather than dropped`() = runBlocking {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .body("<html>Sign in to the hotel wifi</html>")
                .build(),
        )

        val error = sendFailure()

        assertEquals(RpcCode.UNAVAILABLE, error.code)
        assertFalse(error.isPermanent)
    }

    @Test
    fun `a 2xx without a content type is accepted`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).build())

        assertEquals(BatchCreateResponse(0, emptyMap()), transport().sendBatch(listOf(event("first"))))
    }

    @Test
    fun `an undecodable 2xx body is still a success`() = runBlocking {
        server.enqueue(protobuf(byteArrayOf(0x0A, 0x7F)))

        assertNull(transport().sendBatch(listOf(event("first"))))
    }

    @Test
    fun `a slow server times out as a transient deadline`() = runBlocking {
        server.enqueue(protobuf(ByteArray(0)).newBuilder().headersDelay(5, TimeUnit.SECONDS).build())
        val started = System.nanoTime()

        val error = sendFailure(transport(timeoutMillis = 300))

        assertEquals(RpcCode.DEADLINE_EXCEEDED, error.code)
        assertFalse(error.isPermanent)
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "timed out promptly")
    }

    @Test
    fun `a stalled response times out`() = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())

        assertEquals(RpcCode.DEADLINE_EXCEEDED, sendFailure(transport(timeoutMillis = 300)).code)
    }

    @Test
    fun `a dropped connection is transient`() = runBlocking {
        // HttpURLConnection retries a POST once when the connection drops before a response.
        repeat(2) {
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.ShutdownConnection).build())
        }

        val error = sendFailure()

        assertEquals(RpcCode.UNAVAILABLE, error.code)
        assertFalse(error.isPermanent)
    }

    @Test
    fun `an unreachable server is transient`() = runBlocking {
        val endpoint = server.url("/").toString()
        server.close()

        val error = sendFailure(transport(endpoint = endpoint))

        assertEquals(RpcCode.UNAVAILABLE, error.code)
        assertFalse(error.isPermanent)
    }

    @Test
    fun `an endpoint that is not an http url fails permanently`() = runBlocking {
        for (endpoint in listOf("not a url", "pug://ingest", "file:///tmp")) {
            val error = sendFailure(transport(endpoint = endpoint))

            assertEquals(RpcCode.INVALID_ARGUMENT, error.code, endpoint)
            assertTrue(error.isPermanent, endpoint)
        }
    }

    @Test
    fun `a body slower than the deadline times out and frees the request thread`() = runBlocking {
        val finished = CountDownLatch(1)
        // Each byte arrives well within the read timeout, so only the overall deadline can stop it.
        // MockWebServer's throttling outlives the test, hence a bare socket.
        ServerSocket(0).use { listener ->
            thread(isDaemon = true) {
                try {
                    listener.accept().use { socket ->
                        val out = socket.getOutputStream()
                        val headers =
                            "HTTP/1.1 200 OK\r\nContent-Type: application/proto\r\nContent-Length: 100\r\n\r\n"
                        out.write(headers.toByteArray())
                        repeat(100) {
                            out.write(0)
                            out.flush()
                            Thread.sleep(100)
                        }
                    }
                } catch (e: IOException) {
                    // The client hung up.
                }
            }
            val endpoint = "http://127.0.0.1:${listener.localPort}"
            val started = System.nanoTime()

            val error = sendFailure(transport(endpoint, timeoutMillis = 1_000, executor = signalling(finished)))

            assertEquals(RpcCode.DEADLINE_EXCEEDED, error.code)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "timed out promptly")
            assertTrue(finished.await(2, TimeUnit.SECONDS), "the request thread was freed")
        }
    }

    @Test
    fun `cancelling the caller aborts the request promptly and frees the request thread`() = runBlocking {
        server.enqueue(protobuf(ByteArray(0)).newBuilder().headersDelay(10, TimeUnit.SECONDS).build())
        val finished = CountDownLatch(1)
        val call =
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                transport(timeoutMillis = 20_000, executor = signalling(finished)).sendBatch(listOf(event("first")))
            }
        server.takeRequest(1, TimeUnit.SECONDS)
        delay(100)
        val started = System.nanoTime()

        call.cancel()

        assertFailsWith<CancellationException> { withTimeout(2_000) { call.await() } }
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2), "cancelled promptly")
        assertTrue(finished.await(2, TimeUnit.SECONDS), "the request thread was freed")
    }

    @Test
    fun `a request cancelled before its thread starts is never sent`() = runBlocking {
        val tasks = CopyOnWriteArrayList<Runnable>()
        val call =
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                transport(executor = { tasks += it }).sendBatch(listOf(event("first")))
            }

        call.cancel()
        assertFailsWith<CancellationException> { call.await() }
        tasks.single().run()

        assertEquals(0, server.requestCount)
    }

    private suspend fun sendFailure(transport: HttpTransport = transport()): TransportException =
        assertFailsWith<TransportException> { transport.sendBatch(listOf(event("first"))) }

    private fun transport(
        endpoint: String = server.url("/").toString(),
        timeoutMillis: Long = 5_000,
        executor: Executor = Dispatchers.IO.asExecutor(),
    ) = HttpTransport(
        endpoint,
        apiKey = "pub_test",
        batchTimeoutMillis = timeoutMillis,
        identifyTimeoutMillis = timeoutMillis,
        executor = executor,
    )

    // Runs requests on the default threads, counting [finished] down once one has returned.
    private fun signalling(finished: CountDownLatch) = Executor { task ->
        Dispatchers.IO.asExecutor().execute {
            try {
                task.run()
            } finally {
                finished.countDown()
            }
        }
    }

    private fun protobuf(message: com.google.protobuf.Message): MockResponse = protobuf(message.toByteArray())

    private fun protobuf(body: ByteArray): MockResponse = MockResponse
        .Builder()
        .code(200)
        .setHeader("Content-Type", "application/proto")
        .body(Buffer().write(body))
        .build()

    private fun connectError(status: Int, code: String, message: String): MockResponse = MockResponse
        .Builder()
        .code(status)
        .setHeader("Content-Type", "application/json")
        .body("""{"code":"$code","message":"$message"}""")
        .build()

    private fun event(id: String) = Event(
        eventId = "0192f3a4-1b2c-7d3e-8f40-$id".take(36),
        kind = "app_open",
        distinctId = "anon-1",
        sessionId = "0192f3a4-0000-7000-8000-000000000002",
        occurTimeMillis = 1_760_000_000_000L,
        autoProperties = mapOf("\$platform" to PropertyValue.StringValue("android")),
        customProperties = emptyMap(),
    )
}
