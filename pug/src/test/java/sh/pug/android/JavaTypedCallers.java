package sh.pug.android;

/** Calls typed methods with the nulls Java code can pass where Kotlin code cannot. */
final class JavaTypedCallers {
    private JavaTypedCallers() {}

    static void addToCartWithNullProductId(TrackNamespace track) {
        track.addToCart(null);
    }
}
