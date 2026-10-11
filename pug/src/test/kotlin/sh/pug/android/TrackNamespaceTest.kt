@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android

import android.content.ContextWrapper
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import java.lang.reflect.Modifier
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.event.EventProtos
import sh.pug.android.internal.platform.FakePlatform
import sh.pug.android.internal.runtime.FakeTransport
import sh.pug.android.internal.runtime.PugClient
import sh.pug.android.internal.runtime.PugFacade
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue
import sh.pug.android.internal.wire.PropertyValue.BoolValue
import sh.pug.android.internal.wire.PropertyValue.DoubleValue
import sh.pug.android.internal.wire.PropertyValue.IntValue
import sh.pug.android.internal.wire.PropertyValue.StringValue

class TrackNamespaceTest {
    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private val transport = FakeTransport { scheduler.currentTime }
    private val logger = RecordingLogger()
    private val fallbackLogger = RecordingLogger()
    private val facade =
        PugFacade(fallbackLogger.safe(debug = false)) { _, projectId, options ->
            PugClient(
                projectId,
                options,
                transport,
                worker,
                FakePlatform(),
                ids = SequentialIds(),
                random = FixedRandom(),
            )
        }
    private val track = TrackNamespace(facade)

    @Test
    fun `every method tracks its event with each argument as a property`() {
        start()
        val methods =
            TrackNamespace::class.java.declaredMethods
                .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                .groupBy { it.name }
        assertEquals(EventProtos.android.map { it.method }.toSet(), methods.keys)

        val expected =
            EventProtos.android.map { event ->
                val overloads = methods.getValue(event.method)
                // @JvmOverloads: one overload per parameter with a default (the optional properties, extras
                // and options), and the full one.
                assertEquals(event.parameters.count { !it.required } + 3, overloads.size, event.method)
                val arguments = event.parameters.mapIndexed { index, property -> Argument(property, index) }
                val method = overloads.maxBy { it.parameterCount }
                assertEquals(
                    arguments.map { it.javaType } + listOf(Map::class.java, TrackOptions::class.java),
                    method.parameterTypes.toList(),
                    event.method,
                )
                method.invoke(track, *(arguments.map { it.value } + listOf(null, null)).toTypedArray())
                event.kind to arguments.associate { it.property.name to it.sent }
            }

        assertEquals(expected, sent().map { it.kind to it.customProperties })
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `arguments are sent with their types, and those left null are not sent`() {
        start()

        track.addToCart(productId = "p1", price = 9.99, quantity = 2)
        track.fileUploaded(fileId = "f1", sizeBytes = 5_000_000_000L)
        track.errorOccurred(errorCode = "E1", unhandled = false)

        assertEquals(
            listOf(
                mapOf("productId" to StringValue("p1"), "price" to DoubleValue(9.99), "quantity" to IntValue(2)),
                mapOf("fileId" to StringValue("f1"), "sizeBytes" to IntValue(5_000_000_000L)),
                mapOf("errorCode" to StringValue("E1"), "unhandled" to BoolValue(false)),
            ),
            sent().map { it.customProperties },
        )
    }

    @Test
    fun `a duration is sent in seconds`() {
        start()

        track.videoSeeked(videoId = "v1", fromPositionMs = 1_500, toPositionMs = 61_000)

        assertEquals(
            mapOf(
                "videoId" to StringValue("v1"),
                "fromPosition" to StringValue("1.5s"),
                "toPosition" to StringValue("61s"),
            ),
            sent().single().customProperties,
        )
    }

    @Test
    fun `extras are sent too, but an argument that is set wins over an extra of its name`() {
        start()

        track.addToCart(
            productId = "p1",
            price = 9.99,
            extras = mapOf("price" to 1.5, "currency" to "EUR", "coupon" to "SPRING"),
        )

        assertEquals(
            mapOf(
                "price" to DoubleValue(9.99),
                "currency" to StringValue("EUR"),
                "coupon" to StringValue("SPRING"),
                "productId" to StringValue("p1"),
            ),
            sent().single().customProperties,
        )
        assertEquals(
            listOf("Extra \"price\" of Pug.track.addToCart() was dropped: an argument sets it"),
            logger.warnings,
        )
    }

    @Test
    fun `a whole number in the extras for a double property is sent as a double`() {
        start()

        track.addToCart(productId = "p1", extras = mapOf("price" to 10))

        assertEquals(DoubleValue(10.0), sent().single().customProperties["price"])
    }

    @Test
    fun `options apply as they do to untyped events`() {
        start()

        track.appOpen()
        track.appClose(options = TrackOptions(immediate = true, timestamp = 42L))
        scheduler.runCurrent()

        val event = transport.batches.single().single()
        assertEquals("app_close", event.kind)
        assertEquals(42L, event.occurTimeMillis)
    }

    @Test
    fun `screenView only tracks the event`() {
        start()

        track.screenView(screenName = "Settings")
        track.screenView(screenName = "Settings")
        track.appOpen()

        val events = sent()
        assertEquals(listOf("screen_view", "screen_view", "app_open"), events.map { it.kind })
        assertNull(events.last().autoProperties["\$url"])
    }

    @Test
    fun `a null from Java for a required argument drops the event`() {
        start()

        JavaTypedCallers.addToCartWithNullProductId(track)

        assertEquals(emptyList(), sent())
        assertEquals(listOf("Pug.track.addToCart() was dropped: productId is null"), logger.warnings)
    }

    @Test
    fun `a call before init is ignored with a warning`() {
        track.addToCart(productId = "p1")

        assertEquals(
            listOf("Pug.track.addToCart() was called before Pug.init(); it was ignored"),
            fallbackLogger.warnings,
        )
    }

    @Test
    fun `Pug_track is both the untyped call and the typed methods`() {
        // Compiling is the check: before Pug.init() both calls only log.
        Pug.track("checkout_started", mapOf("cartId" to "c1"))
        Pug.track.addToCart(productId = "p1", price = 9.99)
    }

    private fun start() {
        val options =
            PugOptions("pub_key") {
                logger = this@TrackNamespaceTest.logger
                storage = MemoryPugStorage()
                debug = true
                batch(maxSize = 1_000)
            }
        facade.init(ContextWrapper(null), "proj_1", options)
    }

    private fun sent(): List<Event> {
        facade.flush()
        scheduler.runCurrent()
        return transport.batches.flatten()
    }

    /** A distinct argument for the [index]th parameter, and the property it should be sent as. */
    private class Argument(val property: EventProtos.Property, index: Int) {
        val javaType: Class<*>
        val value: Any
        val sent: PropertyValue

        init {
            val n = index + 1
            val required = property.required
            when (property.descriptor.type) {
                Type.STRING -> {
                    javaType = String::class.java
                    value = "v$n"
                    sent = StringValue(value)
                }

                Type.INT32, Type.SINT32, Type.SFIXED32 -> {
                    javaType = if (required) Int::class.javaPrimitiveType!! else Int::class.javaObjectType
                    value = n
                    sent = IntValue(n.toLong())
                }

                Type.INT64, Type.SINT64, Type.SFIXED64, Type.UINT32, Type.FIXED32 -> {
                    javaType = if (required) Long::class.javaPrimitiveType!! else Long::class.javaObjectType
                    value = 1_000L * n
                    sent = IntValue(value)
                }

                Type.DOUBLE, Type.FLOAT -> {
                    javaType = if (required) Double::class.javaPrimitiveType!! else Double::class.javaObjectType
                    value = n + 0.5
                    sent = DoubleValue(value)
                }

                Type.BOOL -> {
                    javaType = if (required) Boolean::class.javaPrimitiveType!! else Boolean::class.javaObjectType
                    value = n % 2 == 0
                    sent = BoolValue(value)
                }

                else -> {
                    check(property.isDuration) { "unexpected type of ${property.name}" }
                    javaType = if (required) Long::class.javaPrimitiveType!! else Long::class.javaObjectType
                    value = 1_500L * n
                    sent = StringValue(BigDecimal.valueOf(value, 3).stripTrailingZeros().toPlainString() + "s")
                }
            }
        }
    }
}
