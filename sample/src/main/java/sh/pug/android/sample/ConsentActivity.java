package sh.pug.android.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import sh.pug.android.Pug;
import sh.pug.android.PugEvents;

/** The SDK from Java: the same calls, with overloads in place of named and default arguments. */
public final class ConsentActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_consent);
        findViewById(R.id.opt_in).setOnClickListener(view -> {
            Pug.optInTracking();
            showConsent();
        });
        findViewById(R.id.opt_out).setOnClickListener(view -> {
            Pug.optOutTracking();
            showConsent();
        });
        findViewById(R.id.track_from_java).setOnClickListener(view -> {
            Map<String, Object> properties = new HashMap<>();
            properties.put("source", "java");
            Pug.track("consent_screen_action", properties);
            Pug.track.search("from java");
            Pug.track(PugEvents.SHARE);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        showConsent();
    }

    private void showConsent() {
        TextView state = findViewById(R.id.consent_state);
        state.setText(getString(R.string.consent_state, Pug.getTrackingConsent(), Pug.isConsentPending()));
    }
}
