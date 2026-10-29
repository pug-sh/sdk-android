package sh.pug.android;

import sh.pug.android.events.AddToCartEvent;

/** Builds event objects with the nulls Java code can pass where Kotlin code cannot. */
final class JavaTypedCallers {
    private JavaTypedCallers() {}

    static AddToCartEvent addToCartEventWithNullProductId() {
        return new AddToCartEvent().productId(null);
    }
}
