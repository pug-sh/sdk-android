package sh.pug.android.internal.platform

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import sh.pug.android.internal.logging.SafeLogger

private const val STORAGE_DIRECTORY = "pug"

internal class AndroidPlatform(context: Context, private val sdkInt: Int = Build.VERSION.SDK_INT) : Platform {
    private val context: Context = context.applicationContext ?: context

    // Not backed up (PLAN.md D10): restored on another device, the anonymous id and the queue would
    // make two installs one user.
    override fun storageDirectory(): File? {
        val base: File = context.noBackupFilesDir ?: return null
        return File(base, STORAGE_DIRECTORY)
    }

    override fun autoProperties(logger: SafeLogger): AutoProperties = AndroidAutoProperties(context, sdkInt, logger)

    override fun observeLifecycle(observer: LifecycleObserver, logger: SafeLogger): (() -> Unit)? {
        val application = context as? Application ?: return null
        val tracker = ActivityLifecycleTracker(application, observer, logger)
        tracker.start()
        return tracker::stop
    }
}
