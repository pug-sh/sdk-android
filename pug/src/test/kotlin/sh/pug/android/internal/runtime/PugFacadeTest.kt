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
    private val facade =
        PugFacade(fallbackLogger.safe(debug = false)) { projectId, options ->
            PugClient(projectId, options, transport, worker, ids = ids, random = FixedRandom(), sdkInt = 26)
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
        JavaCallers.identifyWithNullId(facade)
        scheduler.runCurrent()

        assertEquals(listOf("An event with a null kind was dropped"), logger.warnings)
        assertEquals(listOf("identify() was dropped: externalId is null"), logger.errors)
    }

    @Test
    fun `calls before init are ignored with a warning`() {
        facade.track("a", null, null)
        facade.identify("user-1", null)
        facade.reset()
        facade.rotateSession()
        facade.flush()
        facade.optInTracking()
        facade.optOutTracking()
        facade.destroy()

        assertNull(facade.getTrackingConsent())
        assertFalse(facade.isTrackingEnabled())
        assertTrue(facade.isConsentPending())
        assertEquals(
            listOf("track", "identify", "reset", "rotateSession", "flush", "optInTracking", "optOutTracking", "destroy")
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
        facade.track("a", null, TrackOptions(immediate = true))
        scheduler.runCurrent()

        assertEquals(listOf("Pug.init() was called again; the first configuration stays"), otherLogger.warnings)
        assertEquals(
            PropertyValue.StringValue(PROJECT),
            transport.batches.single().single().autoProperties["\$projectId"],
        )
    }

    @Test
    fun `the consent getters answer through the running client`() {
        facade.init(context, PROJECT, options { trackingConsent(default = TrackingConsent.DENIED) })
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
        val options = options { batch(maxSize = 2) }
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
    fun `destroy keeps the identity for the next init`() {
        facade.init(context, PROJECT, options())
        facade.identify("user-1", null)
        scheduler.runCurrent()

        facade.destroy()
        facade.init(context, PROJECT, options())
        facade.track("a", null, TrackOptions(immediate = true))
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
        val failing = PugFacade(fallbackLogger.safe(debug = false)) { _, _ -> throw IllegalStateException("boom") }

        failing.init(context, PROJECT, options())
        failing.track("a", null, null)

        assertEquals(listOf("Pug.init() failed; calls are ignored"), fallbackLogger.errors)
        assertEquals(listOf("Pug.track() was called before Pug.init(); it was ignored"), fallbackLogger.warnings)
    }

    private fun options(configure: PugOptions.Builder.() -> Unit = {}) = PugOptions("pub_key") {
        logger = this@PugFacadeTest.logger
        storage = this@PugFacadeTest.storage
        configure()
    }

    private companion object {
        const val PROJECT = "proj_1"
    }
}
