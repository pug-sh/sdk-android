package sh.pug.android.events

import sh.pug.android.internal.event.EventSchema

/**
 * A well-known event as an object, the typed form for Java:
 * `Pug.track(new PurchaseEvent().orderId("o-42").amount(39.98).extra("coupon", "FALL10"))`.
 *
 * Every property has a setter, required ones too: Java has no named arguments, and a constructor
 * would take required values by position, where two of one type could swap. An optional property
 * never set is not sent, and a number or boolean once set stays set. An event missing a required
 * property (never set, set to null, or with a value dropped as invalid) is sent without it and logs a
 * warning; each class names its required properties.
 * `extra` adds a property the event does not define, and one named like a property that is set is
 * dropped with a warning. `Pug.track(event)` reads the event during the call, so it can be changed and
 * tracked again once the call returns.
 */
public abstract class WellKnownEvent internal constructor(schema: EventSchema, className: String) {
    // Internal members are public in bytecode: without JvmSynthetic, Java could write to values.
    @get:JvmSynthetic
    internal val schema: EventSchema = schema

    @get:JvmSynthetic
    internal val caller: String = "Pug.track($className)"

    @get:JvmSynthetic
    internal val values: Array<Any?> = arrayOfNulls(schema.fields.size)

    @get:JvmSynthetic
    internal var extras: MutableMap<String, Any?>? = null
        private set

    @JvmSynthetic
    internal fun putExtra(key: String, value: Any?) {
        val map = extras ?: LinkedHashMap<String, Any?>().also { extras = it }
        map[key] = value
    }
}
