@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android

import android.content.ContextWrapper
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import java.lang.reflect.Modifier
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import sh.pug.android.events.AddToCartEvent
import sh.pug.android.events.WellKnownEvent
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.event.EventProtos
import sh.pug.android.internal.platform.FakePlatform
import sh.pug.android.internal.runtime.Client
import sh.pug.android.internal.runtime.Facade
import sh.pug.android.internal.runtime.FakeTransport
import sh.pug.android.internal.wire.Event
import sh.pug.android.internal.wire.PropertyValue
import sh.pug.android.internal.wire.PropertyValue.BoolValue
import sh.pug.android.internal.wire.PropertyValue.DoubleValue
import sh.pug.android.internal.wire.PropertyValue.IntValue
import sh.pug.android.internal.wire.PropertyValue.StringValue

// The events whose methods the generator leaves out, since Pug tracks them itself.
private val SDK_TRACKED = setOf("app_open", "app_close", "app_install", "app_update", "screen_view")

class TrackNamespaceTest {
    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private val transport = FakeTransport { scheduler.currentTime }
    private val logger = RecordingLogger()
    private val fallbackLogger = RecordingLogger()
    private val facade =
        Facade(fallbackLogger.safe(debug = false)) { _, projectId, options ->
            Client(
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
                .filter { Modifier.isPublic(it.modifiers) && '$' !in it.name }
                .groupBy { it.name }
        val typed = EventProtos.android.filterNot { it.kind in SDK_TRACKED }
        assertEquals(typed.map { it.method }.toSet(), methods.keys)

        val expected =
            typed.map { event ->
                val overloads = methods.getValue(event.method)
                if (event.properties.isEmpty()) {
                    // @JvmOverloads: none, extras, and extras with options.
                    assertEquals(3, overloads.size, event.method)
                    assertTrue(overloads.none { it.isSynthetic }, event.method)
                } else {
                    // Hidden from Java, which has the event's class instead.
                    assertTrue(overloads.single().isSynthetic, event.method)
                }
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
            listOf("Extra \"price\" of Pug.track.addToCart() was dropped: the event's own price is set"),
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

        track.scroll()
        track.share(
            options = TrackOptions {
                immediate = true
                timestamp = 42L
            },
        )
        scheduler.runCurrent()

        val event = transport.batches.single().single()
        assertEquals("share", event.kind)
        assertEquals(42L, event.occurTimeMillis)
    }

    @Test
    fun `the events Pug tracks itself have no method, only a kind`() {
        val methods = TrackNamespace::class.java.declaredMethods.map { it.name }.toSet()
        val kinds = Events::class.java.fields.map { it.get(null) }.toSet()

        for (kind in SDK_TRACKED) {
            val event = EventProtos.android.single { it.kind == kind }
            assertFalse(event.method in methods, event.method)
            assertTrue(kind in kinds, kind)
        }
    }

    @Test
    fun `every event with properties has a class that tracks it with each property set`() {
        start()
        val expected =
            EventProtos.android.filter { it.kind !in SDK_TRACKED && it.properties.isNotEmpty() }.map { event ->
                val type = Class.forName(event.className)
                val arguments = event.parameters.mapIndexed { index, property -> Argument(property, index) }
                val (required, optional) = arguments.partition { it.property.required }
                val constructor = type.getConstructor(*required.map { it.javaType }.toTypedArray())
                val instance = constructor.newInstance(*required.map { it.value }.toTypedArray()) as WellKnownEvent
                optional.forEach { type.getMethod(it.setter, it.setterType).invoke(instance, it.value) }
                assertEquals(
                    optional.map { it.setter }.toSet() + "extra",
                    type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                        .map { it.name }.toSet(),
                    type.simpleName,
                )
                facade.track(instance, null)
                event.kind to arguments.associate { it.property.name to it.sent }
            }

        assertEquals(expected, sent().map { it.kind to it.customProperties })
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `events without properties, and those Pug tracks itself, have no class`() {
        for (event in EventProtos.android.filter { it.properties.isEmpty() || it.kind in SDK_TRACKED }) {
            assertFailsWith<ClassNotFoundException>(event.kind) { Class.forName(event.className) }
        }
    }

    @Test
    fun `an event object sends its extras, but a property it sets wins over an extra of its name`() {
        start()

        facade.track(AddToCartEvent("p1").price(9.99).extra("price", 1.5).extra("coupon", "SPRING"), null)

        assertEquals(
            mapOf("productId" to StringValue("p1"), "price" to DoubleValue(9.99), "coupon" to StringValue("SPRING")),
            sent().single().customProperties,
        )
        assertEquals(
            listOf("Extra \"price\" of Pug.track(AddToCartEvent) was dropped: the event's own price is set"),
            logger.warnings,
        )
    }

    @Test
    fun `an event object is read during the call, so it can be changed and tracked again`() {
        start()
        val event = AddToCartEvent("p1").quantity(1)

        facade.track(event, null)
        event.quantity(2)
        facade.track(event, TrackOptions { timestamp = 42L })

        val sent = sent()
        assertEquals(listOf(IntValue(1), IntValue(2)), sent.map { it.customProperties["quantity"] })
        assertEquals(42L, sent.last().occurTimeMillis)
    }

    @Test
    fun `a null from Java for a required constructor argument drops the event`() {
        start()

        facade.track(JavaTypedCallers.addToCartEventWithNullProductId(), null)

        assertEquals(emptyList(), sent())
        assertEquals(listOf("Pug.track(AddToCartEvent) was dropped: productId is null"), logger.warnings)
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
        Pug.track(AddToCartEvent("p1").price(9.99))
    }

    private fun start() {
        val options =
            Options("pub_key") {
                logger = this@TrackNamespaceTest.logger
                storage = MemoryStorage()
                debug = true
                batchMaxSize = 1_000
            }
        facade.init(ContextWrapper(null), "proj_1", options)
    }

    private fun sent(): List<Event> {
        facade.flush()
        scheduler.runCurrent()
        return transport.batches.flatten()
    }

    private val EventProtos.Event.className: String
        get() = "sh.pug.android.events." + method.replaceFirstChar(Char::uppercaseChar) + "Event"

    /** A distinct argument for the [index]th parameter, and the property it should be sent as. */
    private class Argument(val property: EventProtos.Property, index: Int) {
        val javaType: Class<*>
        val value: Any
        val sent: PropertyValue
        val setter: String = if (property.isDuration) "${property.name}Ms" else property.name

        // The setters of the event classes take numbers and booleans as primitives.
        val setterType: Class<*> get() = javaType.kotlin.javaPrimitiveType ?: javaType

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
