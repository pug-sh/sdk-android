package sh.pug.android.internal.event

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.wire.PropertyValue.BoolValue
import sh.pug.android.internal.wire.PropertyValue.DoubleValue
import sh.pug.android.internal.wire.PropertyValue.IntValue
import sh.pug.android.internal.wire.PropertyValue.StringValue
import sh.pug.android.internal.wire.PropertyValue.TimestampValue

class PropertyMapperTest {
    private val logger = RecordingLogger()
    private val mapper = PropertyMapper(logger.safe(), sdkInt = 26)

    private enum class Plan { PRO }

    @Test
    fun `a whole number for a double property of a well-known event is sent as a double`() {
        assertEquals(
            mapOf("price" to DoubleValue(10.0), "quantity" to IntValue(2), "productId" to StringValue("p1")),
            mapper.mapCustom("add_to_cart", mapOf("price" to 10, "quantity" to 2, "productId" to "p1")),
        )
        assertEquals(mapOf("price" to IntValue(10)), mapper.mapCustom("checkout", mapOf("price" to 10)))
    }

    @Test
    fun `scalars map onto their PropertyValue type`() {
        val mapped =
            mapper.mapCustom(
                "kind",
                linkedMapOf(
                    "string" to "s",
                    "charSequence" to StringBuilder("sb"),
                    "char" to 'c',
                    "enum" to Plan.PRO,
                    "true" to true,
                    "byte" to 1.toByte(),
                    "short" to 2.toShort(),
                    "int" to 3,
                    "long" to Long.MAX_VALUE,
                    "atomicInt" to AtomicInteger(4),
                    "atomicLong" to AtomicLong(5),
                    "double" to 2.5,
                    "float" to 0.1f,
                    "bigDecimal" to BigDecimal("1.25"),
                    "uByte" to 255.toUByte(),
                    "uInt" to UInt.MAX_VALUE,
                    "uLong" to Long.MAX_VALUE.toULong(),
                ),
            )

        assertEquals(
            mapOf(
                "string" to StringValue("s"),
                "charSequence" to StringValue("sb"),
                "char" to StringValue("c"),
                "enum" to StringValue("PRO"),
                "true" to BoolValue(true),
                "byte" to IntValue(1),
                "short" to IntValue(2),
                "int" to IntValue(3),
                "long" to IntValue(Long.MAX_VALUE),
                "atomicInt" to IntValue(4),
                "atomicLong" to IntValue(5),
                "double" to DoubleValue(2.5),
                "float" to DoubleValue(0.1),
                "bigDecimal" to DoubleValue(1.25),
                "uByte" to IntValue(255),
                "uInt" to IntValue(4_294_967_295L),
                "uLong" to IntValue(Long.MAX_VALUE),
            ),
            mapped,
        )
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `integers too wide for int64 keep their digits as a string`() {
        val mapped =
            mapper.mapCustom(
                "kind",
                mapOf(
                    "fits" to BigInteger.valueOf(Long.MIN_VALUE),
                    "wide" to BigInteger.ONE.shiftLeft(63),
                    "uLong" to ULong.MAX_VALUE,
                ),
            )

        assertEquals(
            mapOf(
                "fits" to IntValue(Long.MIN_VALUE),
                "wide" to StringValue("9223372036854775808"),
                "uLong" to StringValue("18446744073709551615"),
            ),
            mapped,
        )
    }

    @Test
    fun `dates map onto timestamps`() {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = -1_000L }

        val mapped =
            mapper.mapCustom(
                "kind",
                mapOf(
                    "date" to Date(1_760_000_000_123L),
                    "calendar" to calendar,
                    "instant" to Instant.ofEpochMilli(42L),
                ),
            )

        assertEquals(
            mapOf(
                "date" to TimestampValue(1_760_000_000_123L),
                "calendar" to TimestampValue(-1_000L),
                "instant" to TimestampValue(42L),
            ),
            mapped,
        )
    }

    @Test
    fun `an Instant is only understood from API 26`() {
        val mapped = PropertyMapper(logger.safe(), sdkInt = 25).mapCustom("kind", mapOf("instant" to Instant.EPOCH))

        assertEquals(emptyMap(), mapped)
        assertEquals(
            listOf("Property \"instant\" on event \"kind\" was dropped: java.time.Instant is not a supported type"),
            logger.warnings,
        )
    }

    @Test
    fun `times outside the years 1 to 9999 are dropped`() {
        val mapped =
            mapper.mapCustom(
                "kind",
                mapOf(
                    "first" to Date(MIN_TIMESTAMP_MILLIS),
                    "last" to Date(MAX_TIMESTAMP_MILLIS),
                    "before" to Date(MIN_TIMESTAMP_MILLIS - 1),
                    "after" to Date(MAX_TIMESTAMP_MILLIS + 1),
                    "instant" to Instant.MAX,
                ),
            )

        assertEquals(
            mapOf("first" to TimestampValue(MIN_TIMESTAMP_MILLIS), "last" to TimestampValue(MAX_TIMESTAMP_MILLIS)),
            mapped,
        )
        assertEquals(3, logger.warnings.size)
        assertTrue(logger.warnings.all { it.endsWith("the time is outside the years 1 to 9999") })
    }

    @Test
    fun `non-finite numbers are dropped with a warning`() {
        val mapped =
            mapper.mapCustom(
                "kind",
                linkedMapOf(
                    "nan" to Double.NaN,
                    "inf" to Float.POSITIVE_INFINITY,
                    "huge" to BigDecimal("1e400"),
                    "ok" to 1.0,
                ),
            )

        assertEquals(mapOf("ok" to DoubleValue(1.0)), mapped)
        assertEquals(
            listOf(
                "Property \"nan\" on event \"kind\" was dropped: NaN is not a finite number",
                "Property \"inf\" on event \"kind\" was dropped: Infinity is not a finite number",
                "Property \"huge\" on event \"kind\" was dropped: Infinity is not a finite number",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `null values are dropped silently`() {
        val mapped = mapper.mapCustom("kind", mapOf("gone" to null, "kept" to 1))

        assertEquals(mapOf("kept" to IntValue(1)), mapped)
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `unsupported types are dropped with a warning naming the type`() {
        val mapped = mapper.mapCustom("kind", mapOf("pair" to (1 to 2), "unit" to Unit, "kept" to "yes"))

        assertEquals(mapOf("kept" to StringValue("yes")), mapped)
        assertEquals(
            listOf(
                "Property \"pair\" on event \"kind\" was dropped: kotlin.Pair is not a supported type",
                "Property \"unit\" on event \"kind\" was dropped: kotlin.Unit is not a supported type",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `custom keys may not start with a dollar sign`() {
        val mapped = mapper.mapCustom("kind", linkedMapOf("\$os" to "spoofed", "os" to "fine"))

        assertEquals(mapOf("os" to StringValue("fine")), mapped)
        assertEquals(
            listOf("Property \"\$os\" on event \"kind\" was dropped: keys starting with '$' are reserved"),
            logger.warnings,
        )
    }

    @Test
    fun `auto-property keys must start with a dollar sign`() {
        val mapped = mapper.mapAuto("kind", linkedMapOf("\$os" to "Android", "os" to "bug"))

        assertEquals(mapOf("\$os" to StringValue("Android")), mapped)
        assertEquals(
            listOf("Auto-property \"os\" was dropped: auto-property keys start with '$'"),
            logger.warnings,
        )
    }

    @Test
    fun `a null key from Java is dropped`() {
        val properties = HashMap<String?, Any?>().apply {
            put(null, "x")
            put("kept", "y")
        }

        @Suppress("UNCHECKED_CAST")
        val mapped = mapper.mapCustom("kind", properties as Map<String, Any?>)

        assertEquals(mapOf("kept" to StringValue("y")), mapped)
        assertEquals(listOf("A property with a null key on event \"kind\" was dropped"), logger.warnings)
    }

    @Test
    fun `long strings are truncated to 1024 UTF-8 bytes`() {
        val mapped =
            mapper.mapCustom(
                "kind",
                linkedMapOf("ascii" to "a".repeat(2_000), "emoji" to "🌍".repeat(300), "exact" to "b".repeat(1024)),
            )

        assertEquals(StringValue("a".repeat(1024)), mapped["ascii"])
        assertEquals(StringValue("🌍".repeat(256)), mapped["emoji"])
        assertEquals(StringValue("b".repeat(1024)), mapped["exact"])
        assertEquals(
            listOf(
                "Property \"ascii\" on event \"kind\" was truncated to 1024 bytes",
                "Property \"emoji\" on event \"kind\" was truncated to 1024 bytes",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `maps, collections and arrays become JSON strings`() {
        val mapped =
            mapper.mapCustom(
                "kind",
                linkedMapOf(
                    "map" to linkedMapOf("a" to 1, "b" to listOf(true, null, 2.5), 3 to "int key"),
                    "list" to listOf("x", Plan.PRO, 'c', 10.0, 1.5f),
                    "set" to linkedSetOf(1L, 2L),
                    "array" to arrayOf("a", null),
                    "ints" to intArrayOf(1, 2),
                    "bytes" to byteArrayOf(-1),
                    "nonFinite" to doubleArrayOf(Double.NaN, Double.NEGATIVE_INFINITY),
                    "wide" to listOf(Long.MAX_VALUE, 9_007_199_254_740_991L, BigInteger.TEN.pow(30)),
                    "date" to listOf(Date(1_760_000_000_123L)),
                    "empty" to emptyMap<String, Any>(),
                ),
            )

        assertEquals(
            mapOf(
                "map" to StringValue("""{"a":1,"b":[true,null,2.5],"3":"int key"}"""),
                "list" to StringValue("""["x","PRO","c",10,1.5]"""),
                "set" to StringValue("[1,2]"),
                "array" to StringValue("""["a",null]"""),
                "ints" to StringValue("[1,2]"),
                "bytes" to StringValue("[-1]"),
                "nonFinite" to StringValue("[null,null]"),
                "wide" to StringValue("""["9223372036854775807",9007199254740991,"1000000000000000000000000000000"]"""),
                "date" to StringValue("""["2025-10-09T08:53:20.123Z"]"""),
                "empty" to StringValue("{}"),
            ),
            mapped,
        )
        assertEquals(emptyList(), logger.entries)
    }

    @Test
    fun `JSON strings are truncated like any string`() {
        val mapped = mapper.mapCustom("kind", mapOf("list" to List(1_000) { "item" }))

        val json = (mapped.getValue("list") as StringValue).value
        assertEquals(1024, json.length)
        assertTrue(json.startsWith("""["item","item""""))
        assertEquals(listOf("Property \"list\" on event \"kind\" was truncated to 1024 bytes"), logger.warnings)
    }

    @Test
    fun `self-referencing and deeply nested structures are dropped`() {
        val cyclic = mutableListOf<Any>()
        cyclic.add(cyclic)
        var deep: Any = "bottom"
        repeat(40) { deep = listOf(deep) }

        val mapped = mapper.mapCustom("kind", linkedMapOf("cyclic" to cyclic, "deep" to deep, "kept" to 1))

        assertEquals(mapOf("kept" to IntValue(1)), mapped)
        assertEquals(
            listOf(
                "Property \"cyclic\" on event \"kind\" was dropped: it is nested more than 32 levels deep",
                "Property \"deep\" on event \"kind\" was dropped: it is nested more than 32 levels deep",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `structures holding more than 10000 values are dropped`() {
        val mapped = mapper.mapCustom("kind", mapOf("large" to List(10_000) { it }, "small" to List(9_999) { it }))

        assertEquals(setOf("small"), mapped.keys)
        assertEquals(
            listOf("Property \"large\" on event \"kind\" was dropped: it holds more than 10000 values"),
            logger.warnings.filter { "dropped" in it },
        )
    }

    @Test
    fun `a value that throws while being read is dropped alone`() {
        val throwing =
            object : CharSequence {
                override val length: Int get() = 1

                override fun get(index: Int): Char = 'x'

                override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = this

                override fun toString(): String = throw IllegalStateException("host bug")
            }

        val mapped = mapper.mapCustom("kind", linkedMapOf("bad" to throwing, "good" to "ok"))

        assertEquals(mapOf("good" to StringValue("ok")), mapped)
        val warning = logger.entries.single()
        assertEquals("Property \"bad\" on event \"kind\" was dropped: reading it threw", warning.message)
        assertTrue(warning.throwable is IllegalStateException)
    }

    @Test
    fun `a map that fails while iterating keeps what was read`() {
        val failing =
            object : AbstractMap<String, Any?>() {
                override val entries: Set<Map.Entry<String, Any?>>
                    get() =
                        object : AbstractSet<Map.Entry<String, Any?>>() {
                            override val size: Int get() = 2

                            override fun iterator(): Iterator<Map.Entry<String, Any?>> = iterator {
                                yield(java.util.AbstractMap.SimpleEntry<String, Any?>("first", 1))
                                throw ConcurrentModificationException()
                            }
                        }
            }

        val mapped = mapper.mapCustom("kind", failing)

        assertEquals(mapOf("first" to IntValue(1)), mapped)
        assertEquals(listOf("The properties of event \"kind\" could not be read; 1 were kept"), logger.warnings)
    }
}
