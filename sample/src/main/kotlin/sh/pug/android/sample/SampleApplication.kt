package sh.pug.android.sample

import android.app.Application
import android.os.StrictMode
import sh.pug.android.Pug
import sh.pug.android.PugOptions

class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Pug.init and every Pug call run on the main thread here, as in most apps: any disk or
            // network access they made on it would crash the debug build.
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .penaltyDeath()
                    .build(),
            )
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        Pug.init(
            this,
            BuildConfig.PUG_PROJECT_ID,
            PugOptions(BuildConfig.PUG_API_KEY.ifEmpty { "dry-run" }) {
                endpoint = BuildConfig.PUG_ENDPOINT
                dryRun = BuildConfig.PUG_API_KEY.isEmpty()
                // Every event in Logcat (tag Pug), in the minified build too.
                debug = true
                persistTrackingConsent = true
            },
        )
    }
}
