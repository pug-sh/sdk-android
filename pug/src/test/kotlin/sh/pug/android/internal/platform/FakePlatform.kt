package sh.pug.android.internal.platform

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertNotNull
import sh.pug.android.internal.logging.SafeLogger

/** A fixed device whose lifecycle the test drives, as the main thread would. */
internal class FakePlatform(
    private val directory: File? = null,
    private val properties: Map<String, Any?> = emptyMap(),
    private val appVersion: String? = null,
    private val installSource: String? = null,
    private val observable: Boolean = true,
) : Platform {
    private var observer: LifecycleObserver? = null
    private val mainThreadQueue = ConcurrentLinkedQueue<() -> Unit>()

    var observing: Boolean = false
        private set

    override fun storageDirectory(): File? = directory

    override fun autoProperties(logger: SafeLogger): AutoProperties = object : AutoProperties {
        override val appVersion: String? = this@FakePlatform.appVersion

        override fun current(): Map<String, Any?> = properties
    }

    override fun installSource(): String? = installSource

    override fun observeLifecycle(observer: LifecycleObserver, logger: SafeLogger): (() -> Unit)? {
        if (!observable) return null
        this.observer = observer
        observing = true
        return { observing = false }
    }

    override fun postToMainThread(block: () -> Unit) {
        mainThreadQueue.add(block)
    }

    /** Runs what was posted to the main thread so far, as its looper would. */
    fun runMainThread() {
        while (true) {
            val block = mainThreadQueue.poll() ?: return
            block()
        }
    }

    fun foreground() {
        observer().onForeground()
    }

    fun background() {
        observer().onBackground()
    }

    fun resume(screenClass: String) {
        observer().onActivityResumed(screenClass.substringAfterLast('.'), screenClass)
    }

    private fun observer(): LifecycleObserver = assertNotNull(observer, "the client is not observing the lifecycle")
}
