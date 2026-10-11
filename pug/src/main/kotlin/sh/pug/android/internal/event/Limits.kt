package sh.pug.android.internal.event

// PropertyValue.string_value is capped at 1024 code points. A UTF-8 byte count is never below the
// code-point count, so capping bytes is stricter than the server, never looser.
internal const val MAX_STRING_BYTES: Int = 1024

// The server refuses bodies over 8 MiB with resource_exhausted, which is transient, so an oversized
// batch would be retried forever. Half the cap leaves room for the request envelope.
internal const val MAX_BATCH_BYTES: Int = 4 * 1024 * 1024

// google.protobuf.Timestamp spans 0001-01-01T00:00:00Z to 9999-12-31T23:59:59.999999999Z.
internal const val MIN_TIMESTAMP_MILLIS: Long = -62_135_596_800_000L
internal const val MAX_TIMESTAMP_MILLIS: Long = 253_402_300_799_999L
