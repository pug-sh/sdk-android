@file:OptIn(ExperimentalCoroutinesApi::class)

package sh.pug.android.internal.runtime

import android.content.ContextWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import sh.pug.android.MemoryPugStorage
import sh.pug.android.PugOptions
import sh.pug.android.TrackOptions
import sh.pug.android.TrackingConsent
import sh.pug.android.internal.FixedRandom
import sh.pug.android.internal.RecordingLogger
import sh.pug.android.internal.SequentialIds
import sh.pug.android.internal.platform.FakePlatform
import sh.pug.android.internal.wire.PropertyValue

class PugFacadeTest {
    private val scheduler = TestCoroutineScheduler()
    private val worker = StandardTestDispatcher(scheduler)
    private val transport = FakeTransport { scheduler.currentTime }
    private val logger = RecordingLogger()
    private val fallbackLogger = RecordingLogger()
    private val storage = MemoryPugStorage()
    private val ids = SequentialIds()
    private val context = ContextWrapper(null)
    private val platform = FakePlatform()
    private val facade =
        PugFacade(fallbackLogger.safe(debug = false)) { _, projectId, options ->
            PugClient(
                projectId,
                options,
                transport,
                worker,
                platform,
                ids = ids,
                random = FixedRandom(),
                sdkInt = 26,
            )
        }

    @Test
    fun `init rejects a blank project id or API key`() {
        assertEquals(
            "Pug.init() needs a projectId",
            assertFailsWith<IllegalArgumentException> { facade.init(context, " ", options()) }.message,
        )
        assertEquals(
            "Pug.init() needs an API key",
            assertFailsWith<IllegalArgumentException> { facade.init(context, PROJECT, PugOptions(" ")) }.message,
        )
        assertNull(facade.getTrackingConsent())
    }

    @Test
    fun `init rejects a key that is not a public key`() {
        for (apiKey in listOf("prv_0123456789abcdef0123", PROJECT, "PUB_0123456789abcdef0123")) {
            val failure =
                assertFailsWith<IllegalArgumentException> { facade.init(context, PROJECT, options(apiKey)) }
            assertEquals(
                "Pug.init() needs a public API key (pub_…), not a private key or the project id",
                failure.message,
            )
        }
        assertNull(facade.getTrackingConsent())
    }

    @Test
    fun `init rejects a key with spaces, control characters or non-ASCII`() {
        for (apiKey in listOf("pub_test\n", " pub_test", "pub_te st", "pub_tëst", "pub_\u0000")) {
            val failure =
                assertFailsWith<IllegalArgumentException> { facade.init(context, PROJECT, options(apiKey)) }
            assertEquals(
                "The Pug API key must be printable ASCII without spaces (check for a trailing newline)",
                failure.message,
            )
        }
        facade.init(context, PROJECT, options("pub_0123456789abcdef0123"))
        scheduler.runCurrent()
        assertEquals(TrackingConsent.GRANTED, facade.getTrackingConsent())
    }

    @Test
    fun `init rejects an endpoint that is not an http(s) URL`() {
        for (endpoint in listOf("api.pugs.dev", "ftp://api.pugs.dev", "https://", "")) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    facade.init(context, PROJECT, options { this.endpoint = endpoint })
                }
            assertEquals("The Pug endpoint must be an http(s) URL, not \"$endpoint\"", failure.message)
        }
        facade.init(context, PROJECT, options { endpoint = "http://10.0.2.2:8080/" })
    }

    @Test
    fun `init rejects nulls from Java`() {
        val expectations =
            listOf(
                "Pug.init() needs a Context" to { JavaCallers.init(facade, null, PROJECT, options()) },
                "Pug.init() needs a projectId" to { JavaCallers.init(facade, context, null, options()) },
                "Pug.init() needs PugOptions" to { JavaCallers.init(facade, context, PROJECT, null) },
                "Pug.init() needs an API key" to {
                    JavaCallers.init(facade, context, PROJECT, JavaCallers.optionsWithNullApiKey())
                },
                "The Pug endpoint must be an http(s) URL, not \"null\"" to {
                    JavaCallers.init(facade, context, PROJECT, JavaCallers.optionsWithNullEndpoint())
                },
            )
        for ((message, call) in expectations) {
            assertEquals(message, assertFailsWith<IllegalArgumentException> { call() }.message)
        }
    }

    @Test
    fun `nulls from Java in calls are logged, not thrown`() {
        facade.init(context, PROJECT, options())

        JavaCallers.trackWithNullKind(facade)
        JavaCallers.trackNullEvent(facade)
        JavaCallers.identifyWithNullId(facade)
        JavaCallers.screenWithNullName(facade)
        JavaCallers.whenConsentReadyWithNullCallback(facade)
        scheduler.runCurrent()

        assertEquals(
            listOf(
                "An event with a null kind was dropped",
                "A null event was dropped",
                "A screen with a null name was dropped",
                "Pug.whenConsentReady() was given a null callback; it was ignored",
            ),
            logger.warnings,
        )
        assertEquals(listOf("identify() was dropped: externalId is null"), logger.errors)
    }

    @Test
    fun `calls before init are ignored with a warning`() {
        facade.track("a", null, null)
        facade.identify("user-1", null)
        facade.screen("Home")
        facade.reset()
        facade.rotateSession()
        facade.flush()
        facade.optInTracking()
        facade.optOutTracking()
        facade.whenConsentReady {}
        facade.destroy()

        assertNull(facade.getTrackingConsent())
        assertFalse(facade.isTrackingEnabled())
        assertTrue(facade.isConsentPending())
        assertEquals(
            listOf(
                "track",
                "identify",
                "screen",
                "reset",
                "rotateSession",
                "flush",
                "optInTracking",
                "optOutTracking",
                "whenConsentReady",
                "destroy",
            )
                .map { "Pug.$it() was called before Pug.init(); it was ignored" } +
                listOf(
                    "Pug.getTrackingConsent() was called before Pug.init(); returning null",
                    "Pug.isTrackingEnabled() was called before Pug.init(); returning false",
                    "Pug.isConsentPending() was called before Pug.init(); returning true",
                ),
            fallbackLogger.warnings,
        )
    }

    @Test
    fun `a second init is ignored`() {
        val otherLogger = RecordingLogger()
        facade.init(context, PROJECT, options())

        facade.init(context, "other", PugOptions("pub_other") { logger = otherLogger })
        facade.track("a", null, TrackOptions { immediate = true })
        scheduler.runCurrent()

        assertEquals(listOf("Pug.init() was called again; the first configuration stays"), otherLogger.warnings)
        assertEquals(
            PropertyValue.StringValue(PROJECT),
            transport.batches.single().single().autoProperties["\$projectId"],
        )
    }

    @Test
    fun `the consent getters answer through the running client`() {
        facade.init(context, PROJECT, options { defaultTrackingConsent = TrackingConsent.DENIED })
        scheduler.runCurrent()

        assertEquals(TrackingConsent.DENIED, facade.getTrackingConsent())
        assertFalse(facade.isTrackingEnabled())
        assertTrue(facade.isConsentPending())
        facade.optInTracking()
        assertEquals(TrackingConsent.GRANTED, facade.getTrackingConsent())
        assertFalse(facade.isConsentPending())
    }

    @Test
    fun `a new client waits for the last one's teardown before reading storage`() {
        val options = options { batchMaxSize = 2 }
        facade.init(context, PROJECT, options)
        transport.hold()
        facade.track("a", null, null)
        facade.track("b", null, null)
        scheduler.runCurrent()
        facade.track("c", null, null)

        facade.destroy()
        facade.init(context, PROJECT, options)
        facade.track("d", null, null)
        scheduler.runCurrent()
        assertEquals(1, transport.batches.size)
        transport.releaseAll()
        scheduler.runCurrent()
        facade.flush()
        scheduler.runCurrent()

        // Started early, the new client would have read a, b and c from storage and sent them again.
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), transport.kinds)
    }

    @Test
    fun `whenConsentReady waits for the client that is running when its callback is due`() {
        val options = options { defaultTrackingConsent = TrackingConsent.DENIED }
        facade.init(context, PROJECT, options)
        facade.optInTracking()
        facade.destroy()
        scheduler.advanceUntilIdle()

        facade.init(context, PROJECT, options)
        val seen = mutableListOf<Pair<TrackingConsent?, Boolean>>()
        facade.whenConsentReady { seen += facade.getTrackingConsent() to facade.isConsentPending() }
        transport.hold()
        facade.track("a", null, null)
        facade.destroy()
        facade.init(context, PROJECT, options)
        // The first client has read consent and is in its final flush, which the second one waits for.
        scheduler.runCurrent()
        platform.runMainThread()
        assertEquals(emptyList(), seen)

        transport.releaseAll()
        scheduler.runCurrent()
        platform.runMainThread()
        assertEquals(listOf<Pair<TrackingConsent?, Boolean>>(TrackingConsent.GRANTED to false), seen)
        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `destroy keeps the identity for the next init`() {
        facade.init(context, PROJECT, options())
        facade.identify("user-1", null)
        scheduler.runCurrent()

        facade.destroy()
        facade.init(context, PROJECT, options())
        facade.track("a", null, TrackOptions { immediate = true })
        scheduler.runCurrent()

        assertEquals("user-1", transport.batches.single().single().distinctId)
    }

    @Test
    fun `calls after destroy are ignored with a warning`() {
        facade.init(context, PROJECT, options())
        facade.destroy()

        facade.track("a", null, null)
        scheduler.advanceUntilIdle()

        assertEquals(emptyList(), transport.batches)
        assertEquals(listOf("Pug.track() was called before Pug.init(); it was ignored"), fallbackLogger.warnings)
    }

    @Test
    fun `a client that cannot be built leaves Pug uninitialized`() {
        val failing = PugFacade(fallbackLogger.safe(debug = false)) { _, _, _ -> throw IllegalStateException("boom") }

        failing.init(context, PROJECT, options())
        failing.track("a", null, null)

        assertEquals(listOf("Pug.init() failed; calls are ignored"), fallbackLogger.errors)
        assertEquals(listOf("Pug.track() was called before Pug.init(); it was ignored"), fallbackLogger.warnings)
    }

    private fun options(apiKey: String = "pub_key", configure: PugOptions.Builder.() -> Unit = {}): PugOptions =
        PugOptions(apiKey) {
            logger = this@PugFacadeTest.logger
            storage = this@PugFacadeTest.storage
            configure()
        }

    private companion object {
        const val PROJECT = "proj_1"
    }
}
