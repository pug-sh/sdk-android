package sh.pug.android.sample;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;
import sh.pug.android.Pug;
import sh.pug.android.Events;
import sh.pug.android.events.SearchEvent;
import sh.pug.android.events.SearchResultClickedEvent;

/** The SDK from Java: overloads in place of default arguments, event objects in place of named ones. */
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
            Pug.track(new SearchEvent("from java"));
            Pug.track(new SearchResultClickedEvent("from java", "r1").index(0).extra("source", "java"));
            Pug.track(Events.SHARE);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Pug.whenConsentReady(this::showConsent);
    }

    private void showConsent() {
        TextView state = findViewById(R.id.consent_state);
        state.setText(getString(R.string.consent_state, Pug.getTrackingConsent(), Pug.isConsentPending()));
    }
}
