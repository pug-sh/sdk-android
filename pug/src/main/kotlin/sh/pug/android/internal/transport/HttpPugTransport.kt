package sh.pug.android.internal.transport

import android.net.TrafficStats
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLConnection
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONException
import org.json.JSONObject
import sh.pug.android.SDK_VERSION
import sh.pug.android.internal.wire.BatchCreateResponse
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.IdentifyRequest
import sh.pug.android.internal.wire.ProtoDecodeException
import sh.pug.android.internal.wire.PugWire

internal const val DEFAULT_BATCH_TIMEOUT_MILLIS: Long = 10_000L
internal const val DEFAULT_IDENTIFY_TIMEOUT_MILLIS: Long = 15_000L

// "PUG" in ASCII; tags from 0xFFFFFF00 up are reserved for the system.
internal const val TRAFFIC_STATS_TAG: Int = 0x505547

internal class HttpPugTransport(
    endpoint: String,
    private val apiKey: String,
    private val batchTimeoutMillis: Long = DEFAULT_BATCH_TIMEOUT_MILLIS,
    private val identifyTimeoutMillis: Long = DEFAULT_IDENTIFY_TIMEOUT_MILLIS,
    private val executor: Executor = Dispatchers.IO.asExecutor(),
    private val openConnection: (URL) -> URLConnection = URL::openConnection,
) : PugTransport {
    private val baseUrl = endpoint.trimEnd('/')

    override suspend fun sendBatch(events: List<Event>): BatchCreateResponse? {
        val body = encode { PugWire.encodeBatchCreateRequest(events) }
        val response = unary(BATCH_CREATE_PATH, body, batchTimeoutMillis)
        return try {
            PugWire.decodeBatchCreateResponse(response)
        } catch (e: ProtoDecodeException) {
            null
        }
    }

    override suspend fun identify(request: IdentifyRequest) {
        val body = encode { PugWire.encodeIdentifyRequest(request) }
        unary(IDENTIFY_PATH, body, identifyTimeoutMillis)
    }

    // An encoding failure repeats identically on every retry, so it is permanent.
    private inline fun encode(block: () -> ByteArray): ByteArray = try {
        block()
    } catch (t: Throwable) {
        throw PugTransportException(RpcCode.INTERNAL, "could not encode the request", t, isPermanent = true)
    }

    private suspend fun unary(path: String, body: ByteArray, timeoutMillis: Long): ByteArray {
        val connection = open(path, body.size, timeoutMillis)
        val response =
            try {
                withTimeout(timeoutMillis) { exchange(connection, body) }
            } catch (e: TimeoutCancellationException) {
                throw PugTransportException(RpcCode.DEADLINE_EXCEEDED, "request timed out after $timeoutMillis ms", e)
            }
        if (response.status !in 200..299) throw errorFrom(response)
        // A 2xx that is not protobuf comes from something between us and the server (a captive
        // portal, a proxy), which goes away when the network changes: retry rather than drop.
        if (response.contentType.isNotEmpty() && !response.contentType.contains("proto", ignoreCase = true)) {
            throw PugTransportException(
                RpcCode.UNAVAILABLE,
                "expected a protobuf response but got content-type \"${response.contentType}\"",
            )
        }
        return response.body
    }

    private fun open(path: String, contentLength: Int, timeoutMillis: Long): HttpURLConnection {
        val connection =
            try {
                openConnection(URL(baseUrl + path)) as HttpURLConnection
            } catch (e: MalformedURLException) {
                throw PugTransportException(RpcCode.INVALID_ARGUMENT, "invalid endpoint URL", e)
            } catch (e: ClassCastException) {
                throw PugTransportException(RpcCode.INVALID_ARGUMENT, "endpoint is not an http(s) URL", e)
            } catch (e: IOException) {
                throw PugTransportException(RpcCode.UNAVAILABLE, "could not open a connection", e)
            }
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.useCaches = false
        connection.connectTimeout = timeoutMillis.toInt()
        connection.readTimeout = timeoutMillis.toInt()
        connection.setFixedLengthStreamingMode(contentLength)
        connection.setRequestProperty("Content-Type", "application/proto")
        connection.setRequestProperty("Connect-Protocol-Version", "1")
        connection.setRequestProperty("X-Api-Key", apiKey)
        connection.setRequestProperty("User-Agent", USER_AGENT)
        return connection
    }

    // Socket reads ignore thread interrupts, so a timeout or cancellation unblocks the worker
    // thread by disconnecting; its late result then lands on a cancelled continuation and is dropped.
    private suspend fun exchange(connection: HttpURLConnection, body: ByteArray): RawResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { connection.disconnect() }
            executor.execute {
                continuation.resumeWith(runCatching { tagged { execute(connection, body) } })
            }
        }

    // StrictMode's detectUntaggedSockets, part of detectAll() from API 26, reports every socket
    // opened without a TrafficStats tag, so a host whose VM policy has penaltyDeath would crash on
    // the first send. The executor's threads are shared, so the thread's own tag is put back.
    private inline fun <T> tagged(block: () -> T): T {
        val previous = TrafficStats.getThreadStatsTag()
        TrafficStats.setThreadStatsTag(TRAFFIC_STATS_TAG)
        try {
            return block()
        } finally {
            TrafficStats.setThreadStatsTag(previous)
        }
    }

    private fun execute(connection: HttpURLConnection, body: ByteArray): RawResponse = try {
        connection.outputStream.use { it.write(body) }
        val status = connection.responseCode
        val contentType = connection.contentType.orEmpty()
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { it.readUpTo(MAX_RESPONSE_BYTES) } ?: ByteArray(0)
        RawResponse(status, contentType, bytes)
    } catch (e: SocketTimeoutException) {
        connection.disconnect()
        throw PugTransportException(RpcCode.DEADLINE_EXCEEDED, "request timed out", e)
    } catch (e: IOException) {
        connection.disconnect()
        throw PugTransportException(RpcCode.UNAVAILABLE, "network request failed (${e.javaClass.simpleName})", e)
    } catch (e: SecurityException) {
        connection.disconnect()
        throw PugTransportException(RpcCode.UNAVAILABLE, "network access was denied", e)
    } catch (e: RuntimeException) {
        connection.disconnect()
        throw PugTransportException(
            RpcCode.INTERNAL,
            "HTTP client failed (${e.javaClass.simpleName})",
            e,
            isPermanent = true,
        )
    }

    private fun errorFrom(response: RawResponse): PugTransportException {
        val connectError = parseConnectError(response.body)
        val code = connectError?.first ?: RpcCode.fromHttpStatus(response.status)
        val message = connectError?.second?.takeIf { it.isNotEmpty() } ?: "HTTP ${response.status}"
        return PugTransportException(code, message)
    }

    private fun parseConnectError(body: ByteArray): Pair<RpcCode?, String>? {
        if (body.isEmpty()) return null
        return try {
            val json = JSONObject(body.decodeToString())
            RpcCode.fromWireName(json.optString("code")) to json.optString("message")
        } catch (e: JSONException) {
            null
        }
    }

    private class RawResponse(val status: Int, val contentType: String, val body: ByteArray)

    private companion object {
        const val BATCH_CREATE_PATH = "/sdk.events.v1.EventsService/BatchCreate"
        const val IDENTIFY_PATH = "/sdk.profiles.v1.ProfilesSDKService/Identify"
        const val USER_AGENT = "pug-android/$SDK_VERSION"
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}

private fun InputStream.readUpTo(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
