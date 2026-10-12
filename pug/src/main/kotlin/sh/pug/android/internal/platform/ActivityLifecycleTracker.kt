package sh.pug.android.internal.platform

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.IdentityHashMap
import sh.pug.android.internal.logging.SafeLogger

/**
 * Turns activity callbacks into the app's foreground and background transitions and its screens
 * (PLAN.md §4.9). The app is in the foreground while one of its activities is started; an activity
 * stopped for a configuration change does not count as leaving. Confined to the main thread, where
 * [start] and [stop] move themselves.
 */
internal class ActivityLifecycleTracker(
    private val application: Application,
    private val observer: LifecycleObserver,
    private val logger: SafeLogger,
    private val startsInForeground: () -> Boolean = ::processIsInForeground,
) : Application.ActivityLifecycleCallbacks {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val started: MutableSet<Activity> = Collections.newSetFromMap(IdentityHashMap())

    // An activity the seed believes was started before the callbacks were registered; the first
    // activity seen starting settles the question.
    private var assumedStarted = false
    private var foreground = false
    private var stopped = false

    fun start() {
        onMainThread {
            if (stopped) return@onMainThread
            application.registerActivityLifecycleCallbacks(this)
            if (startsInForeground()) {
                assumedStarted = true
                enterForeground()
            }
        }
    }

    fun stop() {
        onMainThread {
            stopped = true
            application.unregisterActivityLifecycleCallbacks(this)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        guarded {
            assumedStarted = false
            started.add(activity)
            enterForeground()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        guarded {
            val type = activity.javaClass
            observer.onActivityResumed(type.simpleName.ifEmpty { type.name }, type.name)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        guarded {
            // An activity started before registration can only be the one the seed assumed.
            if (!started.remove(activity)) assumedStarted = false
            if (foreground && started.isEmpty() && !assumedStarted && !activity.isChangingConfigurations) {
                foreground = false
                observer.onBackground()
            }
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}

    private fun enterForeground() {
        if (foreground) return
        foreground = true
        observer.onForeground()
    }

    private fun onMainThread(block: () -> Unit) {
        val work = Runnable { guarded(block) }
        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else mainHandler.post(work)
    }

    // These run inside the host's own activity callbacks, where a throw would crash the app. Nothing
    // here suspends, so whatever is thrown is a failure to contain whole.
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("App lifecycle tracking failed", t)
        }
    }

    private companion object {
        fun processIsInForeground(): Boolean {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }
}
