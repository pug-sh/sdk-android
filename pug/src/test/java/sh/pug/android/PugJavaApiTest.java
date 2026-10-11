package sh.pug.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.ContextWrapper;
import java.util.Collections;
import org.junit.Test;

/** The public API as Java code sees it; Pug is not initialized here, so calls only log. */
public class PugJavaApiTest {
    @Test
    public void optionsBuildFluently() {
        PugOptions options = new PugOptions.Builder("pub_key")
                .endpoint("https://api.example.com")
                .debug(true)
                .dryRun(true)
                .autoTrack(false)
                .autoScreenViews(false)
                .autoInstallEvents(false)
                .logger(null)
                .storage(new MemoryPugStorage())
                .batch(20)
                .batch(20, 1_000L)
                .batch(20, 1_000L, 500)
                .session(60_000L)
                .session(60_000L, 3_600_000L)
                .trackingConsent(TrackingConsent.DENIED)
                .trackingConsent(TrackingConsent.DENIED, true)
                .build();

        assertNotNull(options);
        assertNotNull(new TrackOptions());
        assertNotNull(new TrackOptions(true));
        assertNotNull(new TrackOptions(false, 1_760_000_000_000L));
    }

    @Test
    public void initRejectsInvalidArguments() {
        PugOptions options = new PugOptions.Builder("pub_key").build();
        ContextWrapper context = new ContextWrapper(null);

        assertThrows(IllegalArgumentException.class, () -> Pug.init(null, "proj_1", options));
        assertThrows(IllegalArgumentException.class, () -> Pug.init(context, null, options));
        assertThrows(IllegalArgumentException.class, () -> Pug.init(context, "proj_1", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> Pug.init(context, "proj_1", new PugOptions.Builder(null).build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> Pug.init(context, "proj_1", new PugOptions.Builder("pub_key").endpoint("ftp://x").build()));
    }

    @Test
    public void callsAcceptNullsWithoutThrowing() {
        Pug.track(null);
        Pug.track("kind", null);
        Pug.track("kind", Collections.singletonMap("key", null), null);
        Pug.identify(null);
        Pug.identify("user-1", null);
        Pug.reset();
        Pug.rotateSession();
        Pug.flush();
        Pug.optInTracking();
        Pug.optOutTracking();
        Pug.destroy();

        assertNull(Pug.getTrackingConsent());
        assertFalse(Pug.isTrackingEnabled());
        assertTrue(Pug.isConsentPending());
    }
}
