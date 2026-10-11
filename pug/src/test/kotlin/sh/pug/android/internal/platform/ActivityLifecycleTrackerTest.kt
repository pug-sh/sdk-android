package sh.pug.android.internal.platform

import android.app.Activity
import android.app.Application
import android.os.Looper
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import sh.pug.android.internal.RecordingLogger

class HomeActivity : Activity()

class DetailActivity : Activity()

@RunWith(RobolectricTestRunner::class)
class ActivityLifecycleTrackerTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val logger = RecordingLogger()
    private val events = mutableListOf<String>()
    private val observer =
        object : LifecycleObserver {
            override fun onForeground() {
                events += "foreground"
            }

            override fun onBackground() {
                events += "background"
            }

            override fun onActivityResumed(screenName: String, screenClass: String) {
                events += "$screenName ($screenClass)"
            }
        }

    @Test
    fun `the first activity to start brings the app to the foreground, the last to stop takes it back`() {
        tracker()

        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        home.pause().stop()

        assertEquals(listOf("foreground", HOME, "background"), events)
    }

    @Test
    fun `moving from one activity to the next stays in the foreground`() {
        tracker()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()

        home.pause()
        val detail = Robolectric.buildActivity(DetailActivity::class.java).setup()
        home.stop()
        assertEquals(listOf("foreground", HOME, DETAIL), events)
        detail.pause().stop()

        assertEquals(listOf("foreground", HOME, DETAIL, "background"), events)
    }

    @Test
    fun `a configuration change is not a trip to the background`() {
        tracker()
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()

        home.recreate()

        assertEquals(listOf("foreground", HOME, HOME), events)
    }

    @Test
    fun `an app on screen before tracking starts is in the foreground until its activity stops`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()

        tracker(startsInForeground = true)
        home.pause().stop()

        assertEquals(listOf("foreground", "background"), events)
    }

    @Test
    fun `the first activity to start settles what the seed assumed`() {
        tracker(startsInForeground = true)

        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        home.pause().stop()

        assertEquals(listOf("foreground", HOME, "background"), events)
    }

    @Test
    fun `an activity started before tracking is not taken for the last one leaving`() {
        val home = Robolectric.buildActivity(HomeActivity::class.java).setup()
        tracker(startsInForeground = true)

        home.pause()
        val detail = Robolectric.buildActivity(DetailActivity::class.java).setup()
        home.stop()
        assertEquals(listOf("foreground", DETAIL), events)
        detail.pause().stop()

        assertEquals(listOf("foreground", DETAIL, "background"), events)
    }

    @Test
    fun `stop unregisters the callbacks`() {
        val tracker = tracker()

        tracker.stop()
        Robolectric.buildActivity(HomeActivity::class.java).setup()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `start and stop called off the main thread take effect on it, in order`() {
        val tracker = ActivityLifecycleTracker(application, observer, logger.safe()) { true }

        thread { tracker.start() }.join()
        assertEquals(emptyList(), events)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("foreground"), events)
        thread { tracker.stop() }.join()
        shadowOf(Looper.getMainLooper()).idle()
        Robolectric.buildActivity(HomeActivity::class.java).setup()

        assertEquals(listOf("foreground"), events)
    }

    @Test
    fun `a stop that overtakes a pending start leaves the callbacks unregistered`() {
        val tracker = ActivityLifecycleTracker(application, observer, logger.safe()) { true }

        thread { tracker.start() }.join()
        tracker.stop()
        shadowOf(Looper.getMainLooper()).idle()
        Robolectric.buildActivity(HomeActivity::class.java).setup()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `a failure is logged rather than thrown into the app`() {
        val failing =
            object : LifecycleObserver by observer {
                override fun onForeground(): Unit = throw IllegalStateException("boom")
            }
        ActivityLifecycleTracker(application, failing, logger.safe()) { false }.start()

        Robolectric.buildActivity(HomeActivity::class.java).setup()

        assertEquals(listOf(HOME), events)
        assertEquals(listOf("App lifecycle tracking failed"), logger.errors)
    }

    private fun tracker(startsInForeground: Boolean = false) =
        ActivityLifecycleTracker(application, observer, logger.safe()) { startsInForeground }.also { it.start() }

    private companion object {
        val HOME = "HomeActivity (${HomeActivity::class.java.name})"
        val DETAIL = "DetailActivity (${DetailActivity::class.java.name})"
    }
}
