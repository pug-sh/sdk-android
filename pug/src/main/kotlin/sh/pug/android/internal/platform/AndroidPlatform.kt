package sh.pug.android.internal.platform

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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

    // The installer of record on every level, which API 30 moved from getInstallerPackageName to
    // getInstallSourceInfo. sdkInt is Build.VERSION.SDK_INT, injected so tests reach both branches.
    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    override fun installSource(): String? {
        val packageManager = context.packageManager
        val source =
            if (sdkInt >= 30) {
                try {
                    packageManager.getInstallSourceInfo(context.packageName).installingPackageName
                } catch (e: PackageManager.NameNotFoundException) {
                    // Only a package manager that does not know this app throws, such as Robolectric's
                    // unless a test sets an install source; a warning would sound in every host test.
                    null
                }
            } else {
                packageManager.getInstallerPackageName(context.packageName)
            }
        return source?.takeIf { it.isNotEmpty() }
    }

    override fun observeLifecycle(observer: LifecycleObserver, logger: SafeLogger): (() -> Unit)? {
        val application = context as? Application ?: return null
        val tracker = ActivityLifecycleTracker(application, observer, logger)
        tracker.start()
        return tracker::stop
    }

    override fun postToMainThread(block: () -> Unit) {
        check(Handler(Looper.getMainLooper()).post(block)) { "The main looper is not running" }
    }
}
