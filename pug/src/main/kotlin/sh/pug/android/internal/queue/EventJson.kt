package sh.pug.android.internal.queue

import org.json.JSONObject
import sh.pug.android.internal.event.MAX_STRING_BYTES
import sh.pug.android.internal.event.OCCUR_TIME_MILLIS
import sh.pug.android.internal.event.TIMESTAMP_MILLIS
import sh.pug.android.internal.event.isValidKind
import sh.pug.android.internal.isUuid
import sh.pug.android.internal.json.JsonWriter
import sh.pug.android.internal.utf8Length
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue

/**
 * The persisted form of a queued event, in the family's domain shape: values are tagged
 * `{"stringValue": …}`, `{"intValue": …}` and so on, and times are epoch milliseconds.
 */
internal object EventJson {
    fun encode(event: Event): String {
        val json =
            JsonWriter(256)
                .beginObject()
                .name("eventId")
                .value(event.eventId)
                .name("kind")
                .value(event.kind)
                .name("sessionId")
                .value(event.sessionId)
                .name("distinctId")
                .value(event.distinctId)
                .name("occurTime")
                .value(event.occurTimeMillis)
        json.name("customProperties").properties(event.customProperties)
        json.name("autoProperties").properties(event.autoProperties)
        return json.endObject().toString()
    }

    /**
     * The event in [json], or null when it is incomplete or would fail the server's validation: one
     * such event would get its whole batch rejected.
     */
    fun decode(json: JSONObject): Event? {
        val eventId = json.opt("eventId") as? String ?: return null
        val kind = json.opt("kind") as? String ?: return null
        val sessionId = json.opt("sessionId") as? String ?: return null
        val distinctId = json.opt("distinctId") as? String ?: return null
        val occurTime = (json.opt("occurTime") as? Number)?.toLong() ?: return null
        if (!eventId.isUuid() || !sessionId.isUuid() || distinctId.isEmpty() || !isValidKind(kind)) return null
        if (occurTime !in OCCUR_TIME_MILLIS) return null
        return Event(
            eventId = eventId,
            kind = kind,
            distinctId = distinctId,
            sessionId = sessionId,
            occurTimeMillis = occurTime,
            autoProperties = properties(json.opt("autoProperties"), auto = true) ?: return null,
            customProperties = properties(json.opt("customProperties"), auto = false) ?: return null,
        )
    }

    private fun JsonWriter.properties(properties: Map<String, PropertyValue>) {
        beginObject()
        for ((key, value) in properties) {
            name(key).beginObject()
            when (value) {
                is PropertyValue.StringValue -> name("stringValue").value(value.value)
                is PropertyValue.IntValue -> name("intValue").value(value.value)
                is PropertyValue.DoubleValue -> name("doubleValue").value(value.value)
                is PropertyValue.BoolValue -> name("boolValue").value(value.value)
                is PropertyValue.TimestampValue -> name("timestampValue").value(value.epochMillis)
            }
            endObject()
        }
        endObject()
    }

    private fun properties(json: Any?, auto: Boolean): Map<String, PropertyValue>? {
        if (json !is JSONObject) return null
        val properties = LinkedHashMap<String, PropertyValue>()
        for (key in json.keys()) {
            if (key.startsWith('$') != auto) return null
            val value = json.opt(key) as? JSONObject ?: return null
            properties[key] = propertyValue(value) ?: return null
        }
        return properties
    }

    private fun propertyValue(json: JSONObject): PropertyValue? {
        if (json.length() != 1) return null
        val type = json.keys().next()
        val value = json.opt(type)
        return when (type) {
            "stringValue" -> string(value)
            "intValue" -> (value as? Number)?.let { PropertyValue.IntValue(it.toLong()) }
            "doubleValue" -> finiteDouble(value)
            "boolValue" -> (value as? Boolean)?.let { PropertyValue.BoolValue(it) }
            "timestampValue" -> timestamp(value)
            else -> null
        }
    }

    private fun string(value: Any?): PropertyValue? {
        val string = value as? String ?: return null
        return if (string.utf8Length() <= MAX_STRING_BYTES) PropertyValue.StringValue(string) else null
    }

    private fun timestamp(value: Any?): PropertyValue? {
        val millis = (value as? Number)?.toLong() ?: return null
        return if (millis in TIMESTAMP_MILLIS) PropertyValue.TimestampValue(millis) else null
    }

    private fun finiteDouble(value: Any?): PropertyValue? {
        val number = (value as? Number)?.toDouble() ?: return null
        return if (number.isFinite()) PropertyValue.DoubleValue(number) else null
    }
}
