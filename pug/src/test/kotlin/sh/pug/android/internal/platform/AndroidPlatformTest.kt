package sh.pug.android.internal.platform

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import sh.pug.android.internal.RecordingLogger

@RunWith(RobolectricTestRunner::class)
class AndroidPlatformTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val logger = RecordingLogger()
    private val screens = mutableListOf<String>()
    private val observer =
        object : LifecycleObserver {
            override fun onForeground() {}

            override fun onBackground() {}

            override fun onActivityResumed(screenName: String, screenClass: String) {
                screens += screenName
            }
        }

    @Test
    fun `storage lives in a directory that is not backed up`() {
        assertEquals(File(application.noBackupFilesDir, "pug"), AndroidPlatform(application).storageDirectory())
    }

    @Test
    fun `any context of the app leads to its Application`() {
        val platform = AndroidPlatform(ContextWrapper(application))

        val stop = assertNotNull(platform.observeLifecycle(observer, logger.safe()))
        Robolectric.buildActivity(HomeActivity::class.java).setup()
        stop()
        Robolectric.buildActivity(DetailActivity::class.java).setup()

        assertEquals(listOf("HomeActivity"), screens)
    }

    @Test
    fun `a context that does not lead to an Application cannot observe the lifecycle`() {
        val detached =
            object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this
            }

        assertNull(AndroidPlatform(detached).observeLifecycle(observer, logger.safe()))
    }
}
