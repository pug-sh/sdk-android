@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android.internal.runtime

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.json.JSONArray
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import sh.pug.android.MemoryPugStorage
import sh.pug.android.PugOptions
import sh.pug.android.TrackingConsent.DENIED
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.platform.AutoProperties
import sh.pug.android.internal.platform.FakePlatform
import sh.pug.android.internal.platform.LifecycleObserver
import sh.pug.android.internal.platform.Platform
import sh.pug.android.internal.storage.FilePugStorage
import sh.pug.android.internal.storage.PugStorageKeys
import sh.pug.android.internal.storage.STORAGE_SYNC_DELAY_MS
import sh.pug.android.internal.transport.RpcCode
import sh.pug.android.internal.wire.PropertyValue.IntValue
import sh.pug.android.internal.wire.PropertyValue.StringValue

/** The client with a device under it: auto-properties, the app lifecycle, screens, installs, files. */
class PugClientPlatformTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private val transport = FakeTransport { scheduler.currentTime }
    private val logger = RecordingLogger()
    private val storage = MemoryPugStorage()
    private val keys = PugStorageKeys(PROJECT)
    private val ids = SequentialIds()

    // Time that passes on the main thread while the worker has not run yet.
    private var elapsedMs = 0L

    @Test
    fun `device and app properties ride on every event`() {
        val platform = FakePlatform(properties = mapOf("\$os" to "Android", "\$screenWidth" to 411))
        val client = client(platform) { batchMaxSize = 1 }

        client.track("a", null, null)
        scheduler.runCurrent()

        val auto = transport.batches.single().single().autoProperties
        assertEquals(StringValue("Android"), auto["\$os"])
        assertEquals(IntValue(411), auto["\$screenWidth"])
        assertEquals(StringValue(PROJECT), auto["\$projectId"])
        assertNull(auto["\$url"])
    }

    @Test
    fun `each visit to the foreground opens with app_open and closes with app_close`() {
        val platform = FakePlatform()
        val client = client(platform) { batchMaxSize = 1 }

        platform.foreground()
        platform.foreground()
        scheduler.runCurrent()
        platform.background()
        scheduler.runCurrent()
        platform.foreground()
        scheduler.runCurrent()

        assertEquals(listOf("app_open", "app_close", "app_open"), transport.kinds.flatten())
        client.destroy()
    }

    @Test
    fun `leaving the foreground sends app_close at once, and the queue with it`() {
        val platform = FakePlatform()
        val client = client(platform) { batchMaxWaitMs = 60_000 }

        platform.foreground()
        client.track("a", null, null)
        scheduler.runCurrent()
        assertEquals(emptyList(), transport.batches)
        platform.background()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("app_close"), listOf("app_open", "a")), transport.kinds)
    }

    @Test
    fun `leaving the foreground sends the queue without autoOpenCloseEvents, and despite a pending retry`() {
        transport.failNextBatch(RpcCode.UNAVAILABLE)
        val platform = FakePlatform()
        val client = client(platform) {
            autoOpenCloseEvents = false
            batchMaxSize = 1
            batchMaxWaitMs = 60_000
        }

        platform.foreground()
        client.track("a", null, null)
        scheduler.runCurrent()
        platform.background()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a"), listOf("a")), transport.kinds)
    }

    @Test
    fun `an app_open that consent dropped leaves no app_close to follow`() {
        val platform = FakePlatform()
        val client = client(platform) { defaultTrackingConsent = DENIED }

        platform.foreground()
        platform.background()
        scheduler.runCurrent()
        client.optInTracking()
        platform.foreground()
        platform.background()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("app_close"), listOf("app_open")), transport.kinds)
    }

    @Test
    fun `opting in on screen tracks what the launch could not`() {
        val platform = FakePlatform(appVersion = "1.0")
        val client = client(platform) {
            defaultTrackingConsent = DENIED
            batchMaxSize = 3
        }

        platform.foreground()
        platform.resume("com.example.HomeActivity")
        scheduler.runCurrent()
        assertEquals(emptyList(), transport.batches)
        elapsedMs = 10
        client.optInTracking()
        client.optInTracking()
        client.flush()
        elapsedMs = 20
        scheduler.runCurrent()

        val events = transport.batches.single()
        assertEquals(listOf("app_install", "app_open", "screen_view"), events.map { it.kind })
        assertEquals(listOf(START + 10), events.map { it.occurTimeMillis }.distinct())
        assertEquals(StringValue("HomeActivity"), events[2].customProperties["screenName"])
        assertEquals(StringValue("HomeActivity"), events[1].autoProperties["\$url"])
        assertEquals("1.0", storage.getString(keys.lastVersion))
    }

    @Test
    fun `opting out ends the visit, and opting back in opens one for the new identity`() {
        val platform = FakePlatform()
        val client = client(platform) { batchMaxSize = 1 }

        platform.foreground()
        scheduler.runCurrent()
        client.optOutTracking()
        client.optInTracking()
        platform.background()
        scheduler.runCurrent()

        val (firstOpen, secondOpen, close) = transport.batches.map { it.single() }
        assertEquals(listOf("app_open", "app_open", "app_close"), listOf(firstOpen, secondOpen, close).map { it.kind })
        assertNotEquals(firstOpen.distinctId, secondOpen.distinctId)
        assertEquals(secondOpen.distinctId, close.distinctId)
    }

    @Test
    fun `resumed activities are screens, named by $url and $referrer on what follows`() {
        val platform = FakePlatform()
        val client = client(platform) { batchMaxSize = 1 }

        platform.resume("com.example.HomeActivity")
        platform.resume("com.example.DetailActivity")
        client.track("tap", null, null)
        scheduler.runCurrent()

        val (home, detail, tap) = transport.batches.map { it.single() }
        assertEquals(
            mapOf(
                "screenName" to StringValue("HomeActivity"),
                "screenClass" to StringValue("com.example.HomeActivity"),
            ),
            home.customProperties,
        )
        assertEquals(StringValue("HomeActivity"), home.autoProperties["\$url"])
        assertNull(home.autoProperties["\$referrer"])
        assertEquals("screen_view", detail.kind)
        for (event in listOf(detail, tap)) {
            assertEquals(StringValue("DetailActivity"), event.autoProperties["\$url"])
            assertEquals(StringValue("HomeActivity"), event.autoProperties["\$referrer"])
        }
    }

    @Test
    fun `the current screen shown again is not tracked again`() {
        val platform = FakePlatform()
        val client = client(platform) { batchMaxSize = 1 }

        platform.resume("com.example.HomeActivity")
        platform.resume("com.example.HomeActivity")
        client.screen("HomeActivity")
        scheduler.runCurrent()

        assertEquals(listOf(listOf("screen_view")), transport.kinds)
    }

    @Test
    fun `screen names screens that are not activities`() {
        val client = client { batchMaxSize = 1 }

        client.screen("Settings")
        scheduler.runCurrent()

        val event = transport.batches.single().single()
        assertEquals("screen_view", event.kind)
        assertEquals(mapOf("screenName" to StringValue("Settings")), event.customProperties)
        assertEquals(StringValue("Settings"), event.autoProperties["\$url"])
    }

    @Test
    fun `with autoScreenViews off, activities leave the screen to screen()`() {
        val platform = FakePlatform()
        val client = client(platform) {
            autoScreenViews = false
            batchMaxSize = 1
        }

        client.screen("Feed")
        platform.resume("com.example.MainActivity")
        client.track("tap", null, null)
        scheduler.runCurrent()

        assertEquals(listOf("screen_view", "tap"), transport.kinds.flatten())
        assertEquals(StringValue("Feed"), transport.batches.last().single().autoProperties["\$url"])
    }

    @Test
    fun `a blank screen name is dropped and a long one cut short`() {
        val client = client { batchMaxSize = 1 }

        client.screen(" ")
        client.screen("é".repeat(600))
        scheduler.runCurrent()

        val event = transport.batches.single().single()
        assertEquals(StringValue("é".repeat(512)), event.customProperties["screenName"])
        assertEquals(StringValue("é".repeat(512)), event.autoProperties["\$url"])
        assertEquals(
            listOf("A screen with a blank name was dropped", "A screen name was truncated to 1024 bytes"),
            logger.warnings,
        )
    }

    @Test
    fun `the first launch tracks app_install, dated at init and before the launch app_open`() {
        val platform = FakePlatform(appVersion = "1.0")
        client(platform) { batchMaxSize = 2 }

        elapsedMs = 10
        platform.foreground()
        elapsedMs = 20
        scheduler.runCurrent()

        val (install, open) = transport.batches.single()
        assertEquals("app_install", install.kind)
        assertEquals(START, install.occurTimeMillis)
        assertEquals(mapOf("appVersion" to StringValue("1.0")), install.customProperties)
        assertEquals("app_open", open.kind)
        assertEquals(START + 10, open.occurTimeMillis)
        assertEquals("1.0", storage.getString(keys.lastVersion))
    }

    @Test
    fun `app_install names the app that installed it`() {
        client(FakePlatform(appVersion = "1.0", installSource = "com.android.vending")) { batchMaxSize = 1 }

        scheduler.runCurrent()

        val install = transport.batches.single().single()
        assertEquals(
            mapOf("appVersion" to StringValue("1.0"), "installSource" to StringValue("com.android.vending")),
            install.customProperties,
        )
    }

    @Test
    fun `an install source that cannot be read is left out`() {
        val platform =
            object : Platform by FakePlatform(appVersion = "1.0") {
                override fun installSource(): String? = throw SecurityException("no access")
            }
        client(platform) { batchMaxSize = 1 }

        scheduler.runCurrent()

        val install = transport.batches.single().single()
        assertEquals(mapOf("appVersion" to StringValue("1.0")), install.customProperties)
        assertEquals(listOf("The install source could not be read"), logger.warnings)
    }

    @Test
    fun `a new app version tracks app_update, without reading the install source`() {
        storage.putString(keys.lastVersion, "1.0")
        val platform =
            object : Platform by FakePlatform(appVersion = "1.1") {
                override fun installSource(): String? = throw AssertionError("the install source was read")
            }
        client(platform) { batchMaxSize = 1 }

        scheduler.runCurrent()

        val update = transport.batches.single().single()
        assertEquals("app_update", update.kind)
        assertEquals(
            mapOf("appVersion" to StringValue("1.1"), "previousVersion" to StringValue("1.0")),
            update.customProperties,
        )
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `with autoInstallEvents off neither is tracked`() {
        client(FakePlatform(appVersion = "1.0")) {
            autoInstallEvents = false
            batchMaxSize = 1
        }

        scheduler.runCurrent()

        assertEquals(emptyList(), transport.batches)
        assertNull(storage.getString(keys.lastVersion))
    }

    @Test
    fun `an init after destroy does not track the install again`() {
        val first = client(FakePlatform(appVersion = "1.0")) { batchMaxSize = 1 }
        scheduler.runCurrent()
        val teardown = first.destroy()
        scheduler.runCurrent()

        val second = client(FakePlatform(appVersion = "1.0"), teardown) { batchMaxSize = 1 }
        second.track("a", null, null)
        scheduler.runCurrent()

        assertEquals(listOf(listOf("app_install"), listOf("a")), transport.kinds)
    }

    @Test
    fun `the default storage writes to the platform's directory after a pause`() {
        val directory = folder.newFolder("pug")
        val client = client(FakePlatform(directory)) {
            storage = null
            batchMaxWaitMs = 60_000
        }

        client.track("a", null, null)
        scheduler.runCurrent()
        assertEquals(emptyList(), directory.list()?.toList())
        scheduler.advanceTimeBy(STORAGE_SYNC_DELAY_MS)
        scheduler.runCurrent()

        assertEquals(listOf("a"), storedKinds(directory))
    }

    @Test
    fun `an opt-out reaches the disk at once`() {
        val directory = folder.newFolder("pug")
        val client = client(FakePlatform(directory)) {
            storage = null
            batchMaxWaitMs = 60_000
        }
        client.track("a", null, null)
        scheduler.advanceTimeBy(STORAGE_SYNC_DELAY_MS)
        scheduler.runCurrent()
        assertEquals(listOf("a"), storedKinds(directory))

        client.optOutTracking()
        scheduler.runCurrent()

        assertEquals(emptyList(), storedKinds(directory))
        assertEquals("denied", File(directory, FilePugStorage.fileName(keys.consent)).readText())
    }

    @Test
    fun `an op that throws is logged and the ops behind it still run`() {
        var failing = false
        val properties =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>>
                    get() = if (failing) throw IllegalStateException("boom") else emptySet()
            }
        val client = client(FakePlatform(properties = properties)) { autoInstallEvents = false }

        client.track("a", null, null)
        scheduler.runCurrent()
        failing = true
        client.track("boom", null, null)
        scheduler.runCurrent()
        failing = false
        client.track("b", null, null)
        client.flush()
        scheduler.runCurrent()

        assertEquals(listOf("Pug.track() failed"), logger.errors)
        assertEquals(listOf(listOf("a", "b")), transport.kinds)
        val teardown = client.destroy()
        scheduler.runCurrent()
        assertTrue(teardown.isCompleted)
    }

    @Test
    fun `leaving the foreground writes storage at once, and again once the sends settle`() {
        val directory = folder.newFolder("pug")
        val platform = FakePlatform(directory)
        val client = client(platform) {
            storage = null
            autoOpenCloseEvents = false
        }
        transport.hold()

        client.track("a", null, null)
        scheduler.runCurrent()
        platform.background()
        scheduler.runCurrent()
        assertEquals(listOf("a"), storedKinds(directory))
        transport.releaseAll()
        scheduler.runCurrent()

        assertEquals(emptyList(), storedKinds(directory))
    }

    @Test
    fun `destroy writes storage before the next client reads it`() {
        val directory = folder.newFolder("pug")
        transport.batchFailure = RpcCode.UNAVAILABLE
        val first = client(FakePlatform(directory)) { storage = null }
        first.track("a", null, null)
        val teardown = first.destroy()
        scheduler.runCurrent()
        transport.batchFailure = null

        val second = client(FakePlatform(directory), teardown) { storage = null }
        second.flush()
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a"), listOf("a")), transport.kinds)
    }

    @Test
    fun `a client that fails to start writes nothing afterwards`() {
        val directory = folder.newFolder("pug")
        val platform =
            object : Platform by FakePlatform(directory) {
                override fun autoProperties(logger: SafeLogger): AutoProperties = object : AutoProperties {
                    override val appVersion: String = "1.0"

                    override fun current(): Map<String, Any?> = throw IllegalStateException("boom")
                }
            }
        client(platform) { storage = null }

        scheduler.runCurrent()
        scheduler.advanceTimeBy(STORAGE_SYNC_DELAY_MS)
        scheduler.runCurrent()

        assertEquals(listOf("Pug failed to start; calls are ignored"), logger.errors)
        assertEquals(emptyList(), directory.list()?.toList())
    }

    @Test
    fun `without a storage directory state stays in memory`() {
        val client = client(FakePlatform(directory = null)) {
            storage = null
            batchMaxSize = 1
        }

        client.track("a", null, null)
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a")), transport.kinds)
        assertEquals(
            listOf("No storage directory is available; state is kept in memory until the app restarts"),
            logger.warnings,
        )
    }

    @Test
    fun `destroy stops observing the lifecycle, and later callbacks are ignored`() {
        val platform = FakePlatform()
        val client = client(platform)
        assertTrue(platform.observing)

        client.destroy()
        platform.foreground()
        scheduler.runCurrent()

        assertFalse(platform.observing)
        assertEquals(emptyList(), transport.batches)
        assertTrue("Tracking the app moving to the foreground was ignored: Pug was destroyed" in logger.debugs)
    }

    @Test
    fun `a context without the Application is reported`() {
        client(FakePlatform(observable = false))

        assertEquals(
            listOf("App lifecycle events are not tracked: the context does not lead to the Application"),
            logger.warnings,
        )
    }

    @Test
    fun `a failure to observe the lifecycle leaves the client running`() {
        val platform =
            object : Platform by FakePlatform() {
                override fun observeLifecycle(observer: LifecycleObserver, logger: SafeLogger): (() -> Unit)? =
                    throw IllegalStateException("boom")
            }
        val client = client(platform) { batchMaxSize = 1 }

        client.track("a", null, null)
        scheduler.runCurrent()

        assertEquals(listOf(listOf("a")), transport.kinds)
        assertEquals(listOf("App lifecycle events are not tracked: observing them failed"), logger.errors)
    }

    private fun client(
        platform: Platform = FakePlatform(),
        previousTeardown: Job? = null,
        configure: PugOptions.Builder.() -> Unit = {},
    ): PugClient {
        val options =
            PugOptions("pub_key") {
                logger = this@PugClientPlatformTest.logger
                storage = this@PugClientPlatformTest.storage
                debug = true
                configure()
            }
        return PugClient(
            PROJECT,
            options,
            transport,
            worker,
            platform,
            clock = { START + scheduler.currentTime + elapsedMs },
            ids = ids,
            random = FixedRandom(),
            sdkInt = 26,
        ).also { it.start(previousTeardown) }
    }

    private fun storedKinds(directory: File): List<String> {
        val file = File(directory, FilePugStorage.fileName(keys.queue))
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        return List(array.length()) { array.getJSONObject(it).getString("kind") }
    }

    private companion object {
        const val PROJECT = "proj_1"
        const val START = 1_760_000_000_000L
    }
}
