package sh.pug.android.internal.runtime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.pug.android.PugOptions
import sh.pug.android.TrackOptions
import sh.pug.android.internal.event.WellKnownEvents
import sh.pug.android.internal.logging.LogcatLogger
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.platform.AndroidPlatform
import sh.pug.android.internal.platform.HomeActivity
import sh.pug.android.internal.transport.HttpPugTransport

/**
 * `Pug.init` and most calls run on the main thread, often under StrictMode, so whatever reads the disk
 * or asks the system about the app belongs on the worker. StrictMode does not run on the JVM: the
 * application records the SDK's calls to it, by thread, instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = MainThreadIoTest.RecordingApplication::class)
class MainThreadIoTest {
    class RecordingApplication : Application() {
        val onMainThread = CopyOnWriteArrayList<String>()
        val onWorker = CopyOnWriteArrayList<String>()

        override fun getNoBackupFilesDir(): File = record("getNoBackupFilesDir") { super.getNoBackupFilesDir() }

        override fun getPackageManager(): PackageManager = record("getPackageManager") { super.getPackageManager() }

        override fun getApplicationInfo(): ApplicationInfo = record("getApplicationInfo") { super.getApplicationInfo() }

        override fun getResources(): Resources = record("getResources") { super.getResources() }

        override fun getSystemService(name: String): Any? = record("getSystemService") { super.getSystemService(name) }

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            record("checkPermission") { super.checkPermission(permission, pid, uid) }

        private inline fun <T> record(call: String, block: () -> T): T {
            if (calledBySdk()) {
                (if (Looper.myLooper() == Looper.getMainLooper()) onMainThread else onWorker) += call
            }
            return block()
        }

        // The framework and Robolectric ask too; only the SDK's own calls count.
        private fun calledBySdk(): Boolean = Thread.currentThread().stackTrace.any {
            it.className.startsWith("sh.pug.android.") && !it.className.startsWith(MainThreadIoTest::class.java.name)
        }
    }

    @Test
    fun `init, calls and lifecycle callbacks on the main thread leave the disk and the system to the worker`() {
        val application = RuntimeEnvironment.getApplication() as RecordingApplication
        val clients = mutableListOf<PugClient>()
        val worker = Dispatchers.IO.limitedParallelism(1)
        val facade =
            PugFacade(SafeLogger(LogcatLogger, isDebugEnabled = false)) { context, projectId, options ->
                val transport = HttpPugTransport(options.endpoint, options.apiKey)
                PugClient(projectId, options, transport, worker, AndroidPlatform(context)).also { clients += it }
            }

        facade.init(application, "proj_main_thread", PugOptions("pub_test") { dryRun = true })
        facade.track("checkout_started", mapOf("cartId" to "c1"), TrackOptions(immediate = true))
        facade.trackTyped(WellKnownEvents.SEARCH, null, null, "trail shoes")
        facade.screen("Settings")
        facade.identify("user-42", mapOf("plan" to "pro"))
        facade.getTrackingConsent()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        home.pause().stop()
        facade.optOutTracking()
        facade.optInTracking()
        facade.flush()
        facade.destroy()
        runBlocking { withTimeout(10_000) { assertNotNull(clients.single()).destroy().join() } }

        assertEquals(emptyList(), application.onMainThread)
        assertTrue(
            application.onWorker.containsAll(listOf("getNoBackupFilesDir", "getPackageManager", "getResources")),
            "${application.onWorker}",
        )
    }
}
