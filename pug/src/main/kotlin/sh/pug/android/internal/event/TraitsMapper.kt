package sh.pug.android.internal.event

import android.os.Build
import sh.pug.android.internal.logging.SafeLogger
import sh.pug.android.internal.wire.JsonValue

/** Maps identify traits onto `google.protobuf.Struct` values; nesting is kept, unlike properties. */
internal class TraitsMapper(private val logger: SafeLogger, private val sdkInt: Int = Build.VERSION.SDK_INT) {
    fun map(traits: Map<String, Any?>): Map<String, JsonValue> {
        val mapped = LinkedHashMap<String, JsonValue>()
        try {
            if (traits.isEmpty()) return emptyMap()
            for (entry in traits.entries) {
                val key: String? = entry.key
                if (key == null) {
                    logger.warn("A trait with a null key was dropped")
                    continue
                }
                convert(key, entry.value)?.let { mapped[key] = it }
            }
        } catch (t: Throwable) {
            logger.warn("The traits could not be read; ${mapped.size} were kept", t)
        }
        return mapped
    }

    private fun convert(key: String, value: Any?): JsonValue? = try {
        val converted = HostJson.toJsonValue(value, sdkInt)
        // Only a non-finite number turns a non-null value into null.
        if (converted == JsonValue.Null && value != null) {
            logger.warn("Trait \"$key\" was dropped: it is not a finite number")
            null
        } else {
            converted
        }
    } catch (e: UnsupportedValueException) {
        logger.warn("Trait \"$key\" was dropped: ${e.message}")
        null
    } catch (t: Throwable) {
        logger.warn("Trait \"$key\" was dropped: reading it threw", t)
        null
    }
}
