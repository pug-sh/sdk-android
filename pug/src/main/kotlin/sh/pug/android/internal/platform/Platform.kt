package sh.pug.android.internal.platform

import java.io.File
import sh.pug.android.internal.logging.SafeLogger

/**
 * What the runtime takes from the device, behind an interface so that the runtime also runs on a
 * plain JVM in tests. [AndroidPlatform] is the real one.
 */
internal interface Platform {
    /** Where the default storage keeps its files, or null when there is nowhere. Called on the worker. */
    fun storageDirectory(): File?

    /** Reads the fixed values once; the rest are read per event by [AutoProperties.current]. Called on the worker. */
    fun autoProperties(logger: SafeLogger): AutoProperties

    /** The package name of the app that installed this one, or null when none is recorded. Called on the worker. */
    fun installSource(): String?

    /**
     * Reports the app's lifecycle to [observer] until the returned function is called, or returns
     * null when the lifecycle cannot be observed.
     */
    fun observeLifecycle(observer: LifecycleObserver, logger: SafeLogger): (() -> Unit)?

    /** Runs [block] on the main thread later, even when called there; throws when it cannot. */
    fun postToMainThread(block: () -> Unit)
}

internal interface AutoProperties {
    /** The app's version name, which install and update tracking compare across launches. */
    val appVersion: String?

    /** The `$` properties of an event happening now. Called on the worker. */
    fun current(): Map<String, Any?>
}

/** Called on the main thread. */
internal interface LifecycleObserver {
    fun onForeground()

    fun onBackground()

    fun onActivityResumed(screenName: String, screenClass: String)
}
