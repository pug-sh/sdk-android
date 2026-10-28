package sh.pug.android.internal.runtime;

import android.content.Context;
import sh.pug.android.Options;
import sh.pug.android.events.WellKnownEvent;

/** Calls into the SDK with the nulls Java code can pass where Kotlin code cannot. */
final class JavaCallers {
    private JavaCallers() {}

    static void init(Facade facade, Context context, String projectId, Options options) {
        facade.init(context, projectId, options);
    }

    static void trackWithNullKind(Facade facade) {
        facade.track(null, null, null);
    }

    static void trackNullEvent(Facade facade) {
        facade.track((WellKnownEvent) null, null);
    }

    static void identifyWithNullId(Facade facade) {
        facade.identify(null, null);
    }

    static void screenWithNullName(Facade facade) {
        facade.screen(null);
    }

    static void whenConsentReadyWithNullCallback(Facade facade) {
        facade.whenConsentReady(null);
    }

    static Options optionsWithNullApiKey() {
        return new Options.Builder(null).build();
    }

    static Options optionsWithNullEndpoint() {
        return new Options.Builder("pub_key").endpoint(null).build();
    }
}
