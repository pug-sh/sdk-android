package sh.pug.android.internal.runtime

import android.app.Application
import com.google.protobuf.DynamicMessage
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import sh.pug.android.PugOptions
import sh.pug.android.internal.platform.HomeActivity
import sh.pug.android.internal.storage.FilePugStorage
import sh.pug.android.internal.storage.PugStorageKeys
import sh.pug.android.internal.wire.ReferenceMessages

/**
 * The SDK as `Pug.init` builds it, on Robolectric's Android, against a real HTTP server. That server
 * is the JDK's: MockWebServer's OkHttp resolves to its Android build here, which needs API 37.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidEndToEndTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val batches = LinkedBlockingQueue<List<DynamicMessage>>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/sdk.events.v1.EventsService/BatchCreate") { exchange ->
                val events = decodeEvents(exchange.requestBody.readBytes())
                batches.add(events)
                val body = ReferenceMessages.batchResponse(events.size, 0, emptyMap()).toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/proto")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `a launch, its first screen and leaving the app reach the server, and the state is stored`() {
        shadowOf(application.packageManager).getInternalMutablePackageInfo(application.packageName).versionName = "1.0"
        val facade = PugFacade.create()

        facade.init(
            application,
            PROJECT,
            PugOptions("pub_test") {
                endpoint = "http://127.0.0.1:${server.address.port}"
                batch(maxSize = 3)
            },
        )
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        val launch = nextBatch()
        home.pause().stop()
        val close = nextBatch()
        facade.destroy()

        assertEquals(listOf("app_install", "app_open", "screen_view"), launch.map { it.string("kind") })
        val screenView = launch[2]
        assertEquals("HomeActivity", screenView.properties("custom_properties")["screenName"])
        val auto = screenView.properties("auto_properties")
        assertEquals("HomeActivity", auto["\$url"])
        assertEquals("android", auto["\$platform"])
        assertEquals("Android", auto["\$os"])
        assertEquals("1.0", auto["\$appVersion"])
        assertEquals(application.packageName, auto["\$appPackage"])
        assertEquals(listOf("app_close"), close.map { it.string("kind") })
        val keys = PugStorageKeys(PROJECT)
        awaitStoredKeys(listOf(keys.session, keys.profile, keys.lastVersion))
    }

    private fun nextBatch(): List<DynamicMessage> = assertNotNull(batches.poll(10, TimeUnit.SECONDS), "no batch")

    // The queue was delivered, so its file is gone once destroy() has written storage.
    private fun awaitStoredKeys(keys: List<String>) {
        val directory = File(application.noBackupFilesDir, "pug")
        val expected = keys.map(FilePugStorage::fileName).sorted()
        runBlocking {
            withTimeout(10_000) {
                while (directory.list()?.sorted() != expected) delay(10)
            }
        }
    }

    private companion object {
        const val PROJECT = "proj_android"
    }
}
