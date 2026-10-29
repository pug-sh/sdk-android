package sh.pug.android

/**
 * Never passed: the `vararg named` that starts each [TrackNamespace] method for an event with
 * properties. Kotlin takes the arguments after a vararg by name only, and this class has no
 * instances, so `Pug.track.purchase("sku-1", 89.0)` does not compile ("'NamedArguments' was expected")
 * and two values of one type cannot swap places unnoticed: write
 * `Pug.track.purchase(productId = "sku-1", amount = 89.0)`.
 */
public class NamedArguments private constructor()
