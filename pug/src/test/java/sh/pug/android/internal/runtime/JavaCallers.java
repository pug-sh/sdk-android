package sh.pug.android.internal.runtime;

import android.content.Context;
import sh.pug.android.PugOptions;

/** Calls into the SDK with the nulls Java code can pass where Kotlin code cannot. */
final class JavaCallers {
    private JavaCallers() {}

    static void init(PugFacade facade, Context context, String projectId, PugOptions options) {
        facade.init(context, projectId, options);
    }

    static void trackWithNullKind(PugFacade facade) {
        facade.track(null, null, null);
    }

    static void identifyWithNullId(PugFacade facade) {
        facade.identify(null, null);
    }

    static void screenWithNullName(PugFacade facade) {
        facade.screen(null);
    }

    static PugOptions optionsWithNullApiKey() {
        return new PugOptions.Builder(null).build();
    }

    static PugOptions optionsWithNullEndpoint() {
        return new PugOptions.Builder("pub_key").endpoint(null).build();
    }
}
