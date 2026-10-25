package sh.pug.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import sh.pug.android.internal.RecordingLogger

class PugOptionsTest {
    @Test
    fun `defaults match the family's`() {
        val options = PugOptions("pub_test")

        assertEquals("pub_test", options.apiKey)
        assertEquals("https://api.pugs.dev", options.endpoint)
        assertFalse(options.debug)
        assertFalse(options.dryRun)
        assertEquals(10, options.batchMaxSize)
        assertEquals(5_000L, options.batchMaxWaitMs)
        assertEquals(1_000, options.batchMaxQueueSize)
        assertEquals(30 * 60_000L, options.sessionIdleTimeoutMs)
        assertEquals(24 * 60 * 60_000L, options.sessionMaxDurationMs)
        assertEquals(TrackingConsent.GRANTED, options.defaultTrackingConsent)
        assertTrue(options.persistTrackingConsent)
        assertTrue(options.autoOpenCloseEvents)
        assertTrue(options.autoScreenViews)
        assertTrue(options.autoInstallEvents)
        assertNull(options.logger)
        assertNull(options.storage)
    }

    @Test
    fun `the Kotlin DSL and the Java builder configure the same options`() {
        val logger = RecordingLogger()
        val storage = MemoryPugStorage()

        val dsl =
            PugOptions("pub_test") {
                endpoint = "https://eu.pugs.dev"
                debug = true
                dryRun = true
                autoOpenCloseEvents = false
                autoScreenViews = false
                autoInstallEvents = false
                this.logger = logger
                this.storage = storage
                batchMaxSize = 20
                batchMaxWaitMs = 1_000
                batchMaxQueueSize = 50
                sessionIdleTimeoutMs = 60_000
                sessionMaxDurationMs = 120_000
                defaultTrackingConsent = TrackingConsent.DENIED
                persistTrackingConsent = false
            }
        val builder =
            PugOptions
                .Builder("pub_test")
                .endpoint("https://eu.pugs.dev")
                .debug(true)
                .dryRun(true)
                .autoOpenCloseEvents(false)
                .autoScreenViews(false)
                .autoInstallEvents(false)
                .logger(logger)
                .storage(storage)
                .batchMaxSize(20)
                .batchMaxWaitMs(1_000)
                .batchMaxQueueSize(50)
                .sessionIdleTimeoutMs(60_000)
                .sessionMaxDurationMs(120_000)
                .defaultTrackingConsent(TrackingConsent.DENIED)
                .persistTrackingConsent(false)
                .build()

        for (options in listOf(dsl, builder)) {
            assertEquals("https://eu.pugs.dev", options.endpoint)
            assertTrue(options.debug)
            assertTrue(options.dryRun)
            assertFalse(options.autoOpenCloseEvents)
            assertFalse(options.autoScreenViews)
            assertFalse(options.autoInstallEvents)
            assertSame(logger, options.logger)
            assertSame(storage, options.storage)
            assertEquals(20, options.batchMaxSize)
            assertEquals(1_000L, options.batchMaxWaitMs)
            assertEquals(50, options.batchMaxQueueSize)
            assertEquals(60_000L, options.sessionIdleTimeoutMs)
            assertEquals(120_000L, options.sessionMaxDurationMs)
            assertEquals(TrackingConsent.DENIED, options.defaultTrackingConsent)
            assertFalse(options.persistTrackingConsent)
        }
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun `each setter changes only its own option`() {
        val options =
            PugOptions("pub_test") {
                batchMaxWaitMs = 0
                sessionMaxDurationMs = 60_000
                persistTrackingConsent = false
            }
        val consent = PugOptions("pub_test") { defaultTrackingConsent = TrackingConsent.DENIED }

        assertEquals(TrackingConsent.DENIED, consent.defaultTrackingConsent)
        assertTrue(consent.persistTrackingConsent)

        assertEquals(10, options.batchMaxSize)
        assertEquals(0L, options.batchMaxWaitMs)
        assertEquals(1_000, options.batchMaxQueueSize)
        assertEquals(30 * 60_000L, options.sessionIdleTimeoutMs)
        assertEquals(60_000L, options.sessionMaxDurationMs)
        assertEquals(TrackingConsent.GRANTED, options.defaultTrackingConsent)
        assertFalse(options.persistTrackingConsent)
    }

    @Test
    fun `out-of-range numbers warn and fall back to their defaults`() {
        val logger = RecordingLogger()

        val options =
            PugOptions("pub_test") {
                this.logger = logger
                batchMaxSize = 0
                batchMaxWaitMs = -1
                batchMaxQueueSize = 0
                sessionIdleTimeoutMs = 0
                sessionMaxDurationMs = -5
            }

        assertEquals(10, options.batchMaxSize)
        assertEquals(5_000L, options.batchMaxWaitMs)
        assertEquals(1_000, options.batchMaxQueueSize)
        assertEquals(30 * 60_000L, options.sessionIdleTimeoutMs)
        assertEquals(24 * 60 * 60_000L, options.sessionMaxDurationMs)
        assertEquals(
            listOf(
                "batchMaxSize 0 must be at least 1; using 10",
                "batchMaxWaitMs -1 must be at least 0; using 5000",
                "batchMaxQueueSize 0 must be at least 1; using 1000",
                "sessionIdleTimeoutMs 0 must be at least 1; using 1800000",
                "sessionMaxDurationMs -5 must be at least 1; using 86400000",
            ),
            logger.warnings,
        )
    }

    @Test
    fun `a batch size above the server's limit is capped rather than reset`() {
        val logger = RecordingLogger()

        val options =
            PugOptions("pub_test") {
                this.logger = logger
                batchMaxSize = 5_000
            }

        assertEquals(1_000, options.batchMaxSize)
        assertEquals(listOf("batchMaxSize 5000 is above the server's limit; using 1000"), logger.warnings)
    }

    @Test
    fun `building never throws, leaving key and endpoint checks to init`() {
        val options =
            PugOptions(" ") {
                endpoint = "not a url"
                logger = RecordingLogger()
            }

        assertEquals(" ", options.apiKey)
        assertEquals("not a url", options.endpoint)
    }

    @Test
    fun `a builder can be reused`() {
        val builder = PugOptions.Builder("pub_test").batchMaxSize(20)

        val first = builder.build()
        val second = builder.batchMaxSize(30).build()

        assertEquals(20, first.batchMaxSize)
        assertEquals(30, second.batchMaxSize)
    }
}
