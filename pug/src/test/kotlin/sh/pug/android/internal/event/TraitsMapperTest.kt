package sh.pug.android.internal.event

import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.wire.JsonValue

class TraitsMapperTest {
    private val logger = RecordingLogger()
    private val mapper = TraitsMapper(logger.safe(), sdkInt = 26)

    @Test
    fun `nested maps and lists are kept as Struct values`() {
        val mapped =
            mapper.map(
                linkedMapOf(
                    "plan" to "pro",
                    "seats" to 3,
                    "score" to 0.5f,
                    "active" to true,
                    "deleted" to null,
                    "address" to mapOf("city" to "Zürich", "zip" to 8001),
                    "tags" to listOf("a", null, listOf(1L), emptyMap<String, Any>()),
                    "flags" to booleanArrayOf(true),
                ),
            )

        assertEquals(
            mapOf(
                "plan" to JsonValue.StringValue("pro"),
                "seats" to JsonValue.NumberValue(3.0),
                "score" to JsonValue.NumberValue(0.5),
                "active" to JsonValue.BoolValue(true),
                "deleted" to JsonValue.Null,
                "address" to
                    JsonValue.ObjectValue(
                        mapOf("city" to JsonValue.StringValue("Zürich"), "zip" to JsonValue.NumberValue(8001.0)),
                    ),
                "tags" to
                    JsonValue.ArrayValue(
                        listOf(
                            JsonValue.StringValue("a"),
                            JsonValue.Null,
                            JsonValue.ArrayValue(listOf(JsonValue.NumberValue(1.0))),
                            JsonValue.ObjectValue(emptyMap()),
                        ),
                    ),
                "flags" to JsonValue.ArrayValue(listOf(JsonValue.BoolValue(true))),
            ),
            mapped,
        )
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `integers a double cannot hold exactly become strings`() {
        val mapped = mapper.map(mapOf("safe" to 9_007_199_254_740_991L, "unsafe" to 9_007_199_254_740_993L))

        assertEquals(
            mapOf(
                "safe" to JsonValue.NumberValue(9_007_199_254_740_991.0),
                "unsafe" to JsonValue.StringValue("9007199254740993"),
            ),
            mapped,
        )
    }

    @Test
    fun `dates become ISO 8601 strings`() {
        val mapped = mapper.map(mapOf("signedUp" to Date(0L), "lastSeen" to Instant.ofEpochMilli(1_500L)))

        assertEquals(
            mapOf(
                "signedUp" to JsonValue.StringValue("1970-01-01T00:00:00.000Z"),
                "lastSeen" to JsonValue.StringValue("1970-01-01T00:00:01.500Z"),
            ),
            mapped,
        )
    }

    @Test
    fun `the first and last representable days use the proleptic Gregorian calendar`() {
        val mapped = mapper.map(mapOf("first" to Date(MIN_TIMESTAMP_MILLIS), "last" to Date(MAX_TIMESTAMP_MILLIS)))

        assertEquals(
            mapOf(
                "first" to JsonValue.StringValue("0001-01-01T00:00:00.000Z"),
                "last" to JsonValue.StringValue("9999-12-31T23:59:59.999Z"),
            ),
            mapped,
        )
    }

    @Test
    fun `a non-finite number is dropped at the top level and null when nested`() {
        val mapped = mapper.map(linkedMapOf("nan" to Double.NaN, "nested" to listOf(Double.POSITIVE_INFINITY)))

        assertEquals(mapOf("nested" to JsonValue.ArrayValue(listOf(JsonValue.Null))), mapped)
        assertEquals(listOf("Trait \"nan\" was dropped: it is not a finite number"), logger.warnings)
    }

    @Test
    fun `strings are not truncated`() {
        val long = "x".repeat(5_000)

        assertEquals(mapOf("bio" to JsonValue.StringValue(long)), mapper.map(mapOf("bio" to long)))
    }

    @Test
    fun `values of other types are sent as their text`() {
        val id = UUID.fromString("0190a5b4-7c2e-7a3b-8f00-0123456789ab")

        val mapped = mapper.map(linkedMapOf("id" to id, "pair" to (1 to 2), "nested" to mapOf("ids" to listOf(id))))

        assertEquals(
            mapOf(
                "id" to JsonValue.StringValue("0190a5b4-7c2e-7a3b-8f00-0123456789ab"),
                "pair" to JsonValue.StringValue("(1, 2)"),
                "nested" to
                    JsonValue.ObjectValue(
                        mapOf(
                            "ids" to
                                JsonValue.ArrayValue(
                                    listOf(JsonValue.StringValue("0190a5b4-7c2e-7a3b-8f00-0123456789ab")),
                                ),
                        ),
                    ),
            ),
            mapped,
        )
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `a value whose text throws is dropped alone`() {
        val throwing =
            object {
                override fun toString(): String = throw IllegalStateException("host bug")
            }

        val mapped = mapper.map(linkedMapOf("bad" to throwing, "nested" to listOf(throwing), "kept" to 1))

        assertEquals(mapOf("kept" to JsonValue.NumberValue(1.0)), mapped)
        assertEquals(
            listOf("Trait \"bad\" was dropped: reading it threw", "Trait \"nested\" was dropped: reading it threw"),
            logger.warnings,
        )
    }
}
