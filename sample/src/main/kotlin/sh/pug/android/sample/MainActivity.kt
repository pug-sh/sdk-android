package sh.pug.android.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import sh.pug.android.Pug

class MainActivity : Activity() {
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        onClick(R.id.track_event) {
            taps++
            Pug.track("sample_button_tapped", mapOf("button" to "track_event", "taps" to taps))
        }
        onClick(R.id.track_search) { Pug.track.search(query = "trail shoes") }
        onClick(R.id.show_settings) { Pug.screen("Settings") }
        onClick(R.id.open_product) { startActivity(Intent(this, ProductActivity::class.java)) }
        onClick(R.id.identify) { Pug.identify("sample-user-42", mapOf("plan" to "pro", "beta" to true)) }
        onClick(R.id.reset) { Pug.reset() }
        onClick(R.id.flush) { Pug.flush() }
        onClick(R.id.open_consent) { startActivity(Intent(this, ConsentActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        val mode =
            if (BuildConfig.PUG_API_KEY.isEmpty()) {
                getString(R.string.mode_dry_run)
            } else {
                getString(R.string.mode_sending, BuildConfig.PUG_ENDPOINT)
            }
        findViewById<TextView>(R.id.status).text = getString(R.string.status, mode, Pug.getTrackingConsent())
    }

    private inline fun onClick(id: Int, crossinline action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }
}
