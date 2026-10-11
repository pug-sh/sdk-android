package sh.pug.android.internal.event

import sh.pug.android.SDK_VERSION
import sh.pug.android.internal.IdGenerator
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue
import sh.pug.android.internal.wire.PugWire

internal const val PLATFORM: String = "android"

/** The server's `Event.kind` pattern, `^[a-zA-Z0-9_.-]+$`. */
internal fun isValidKind(kind: String): Boolean = kind.isNotEmpty() &&
    kind.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }

internal class EventFactory(
    private val projectId: String,
    private val logger: SafeLogger,
    private val properties: PropertyMapper,
    private val ids: IdGenerator,
) {
    /** Logs and returns false for a kind the server would reject along with the rest of its batch. */
    fun acceptsKind(kind: String): Boolean {
        if (isValidKind(kind)) return true
        if (kind.isBlank()) {
            logger.warn("An event with a blank kind was dropped")
        } else {
            logger.warn("Event \"$kind\" was dropped: a kind may only contain letters, digits, '_', '.' and '-'")
        }
        return false
    }

    /** [timestamp] when the caller set a usable one, else the time of the call. */
    fun occurTime(kind: String, timestamp: Long?, callTimeMillis: Long): Long {
        if (timestamp == null) return callTimeMillis
        if (timestamp in 0..MAX_TIMESTAMP_MILLIS) return timestamp
        logger.warn("Event \"$kind\" has a timestamp outside 1970 to 9999 ($timestamp); using the time of the call")
        return callTimeMillis
    }

    /**
     * Builds the event for an already-accepted [kind] from [customProperties] the caller mapped, or
     * returns null when it would not fit in a request. `$projectId`, `$sdkVersion` and `$platform`
     * always come from the SDK.
     */
    fun create(
        kind: String,
        customProperties: Map<String, PropertyValue>,
        autoProperties: Map<String, Any?>,
        distinctId: String,
        sessionId: String,
        occurTimeMillis: Long,
    ): Event? {
        val auto = LinkedHashMap(properties.mapAuto(kind, autoProperties))
        auto["\$projectId"] = PropertyValue.StringValue(projectId)
        auto["\$sdkVersion"] = PropertyValue.StringValue(SDK_VERSION)
        auto["\$platform"] = PropertyValue.StringValue(PLATFORM)
        val event =
            Event(
                eventId = ids.nextId(),
                kind = kind,
                distinctId = distinctId,
                sessionId = sessionId,
                occurTimeMillis = occurTimeMillis,
                autoProperties = auto,
                customProperties = customProperties,
            )
        val size = PugWire.eventSize(event)
        if (size > MAX_BATCH_BYTES) {
            logger.error(
                "Event \"$kind\" was dropped: it encodes to $size bytes, over the $MAX_BATCH_BYTES-byte request limit",
            )
            return null
        }
        return event
    }
}
