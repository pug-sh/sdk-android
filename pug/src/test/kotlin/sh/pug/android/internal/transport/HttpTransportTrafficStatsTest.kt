package sh.pug.android.internal.transport

import android.net.TrafficStats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.pug.android.internal.wire.IdentifyRequest

/** The transport opens its sockets under the SDK's TrafficStats tag, which StrictMode looks for. */
@RunWith(RobolectricTestRunner::class)
class HttpTransportTrafficStatsTest {
    @AfterTest
    fun clearTag() {
        TrafficStats.clearThreadStatsTag()
    }

    @Test
    fun `a request connects under the SDK's tag, and the thread's own tag is put back`() = runBlocking {
        val connection = RecordingConnection()
        TrafficStats.setThreadStatsTag(HOST_TAG)

        transport(connection).identify(IdentifyRequest("user-42", emptyMap(), anonymousId = null))

        assertEquals(TRAFFIC_STATS_TAG, connection.tagWhenConnecting)
        assertEquals(HOST_TAG, TrafficStats.getThreadStatsTag())
    }

    @Test
    fun `a failed request puts the thread's own tag back too`() = runBlocking {
        val connection = RecordingConnection(failure = IOException("unreachable"))
        TrafficStats.setThreadStatsTag(HOST_TAG)

        assertFailsWith<TransportException> {
            transport(connection).identify(IdentifyRequest("user-42", emptyMap(), anonymousId = null))
        }

        assertEquals(TRAFFIC_STATS_TAG, connection.tagWhenConnecting)
        assertEquals(HOST_TAG, TrafficStats.getThreadStatsTag())
    }

    // The executor runs requests on the calling thread, so the test thread's tag is the one in play.
    private fun transport(connection: HttpURLConnection) = HttpTransport(
        ENDPOINT,
        "pub_test",
        executor = Executor { it.run() },
        openConnection = { connection },
    )

    private class RecordingConnection(private val failure: IOException? = null) : HttpURLConnection(URL(ENDPOINT)) {
        var tagWhenConnecting: Int? = null

        override fun connect() {}

        override fun disconnect() {}

        override fun usingProxy(): Boolean = false

        override fun getOutputStream(): OutputStream {
            tagWhenConnecting = TrafficStats.getThreadStatsTag()
            failure?.let { throw it }
            return ByteArrayOutputStream()
        }

        override fun getResponseCode(): Int = 200

        override fun getContentType(): String = "application/proto"

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    private companion object {
        const val ENDPOINT = "https://api.example.test"
        const val HOST_TAG = 7
    }
}
