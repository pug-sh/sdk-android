package sh.pug.android;

import static org.junit.Assert.assertEquals;
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
                .batchMaxSize(20)
                .batchMaxWaitMs(1_000L)
                .batchMaxQueueSize(500)
                .sessionIdleTimeoutMs(60_000L)
                .sessionMaxDurationMs(3_600_000L)
                .defaultTrackingConsent(TrackingConsent.DENIED)
                .persistTrackingConsent(true)
                .build();

        assertNotNull(options);
        TrackOptions trackOptions = new TrackOptions.Builder().immediate(true).timestamp(1_760_000_000_000L).build();
        assertTrue(trackOptions.getImmediate());
        assertEquals(Long.valueOf(1_760_000_000_000L), trackOptions.getTimestamp());
        assertNull(new TrackOptions.Builder().timestamp(null).build().getTimestamp());
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
        Pug.screen(null);
        Pug.screen("Home");
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

    @Test
    public void memoryStorageIgnoresNulls() {
        MemoryPugStorage storage = new MemoryPugStorage();
        storage.putString("key", "value");

        storage.putString(null, "value");
        storage.putString("key", null);
        storage.remove(null);

        assertNull(storage.getString(null));
        assertEquals("value", storage.getString("key"));
    }

    @Test
    public void typedEventsAreFieldsAndOverloads() {
        Pug.track.addToCart("p1");
        Pug.track.addToCart("p1", 9.99, "USD");
        Pug.track.addToCart(
                "p1", null, null, null, 2, null, null, null, Collections.singletonMap("coupon", "SPRING"),
                new TrackOptions.Builder().immediate(true).build());
        Pug.track.addToCart(null);
        Pug.track.videoPlay("v1", 1_500L);
        Pug.track.appOpen();
        Pug.track(PugEvents.ADD_TO_CART, Collections.singletonMap("productId", "p1"));
    }

    @Test
    public void eventKindsAreCompileTimeConstants() {
        String kind = "add_to_cart";
        switch (kind) {
            case PugEvents.ADD_TO_CART:
                break;
            default:
                throw new AssertionError(kind);
        }
    }
}
