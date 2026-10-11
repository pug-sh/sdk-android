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
        assertFalse(options.persistTrackingConsent)
        assertTrue(options.autoTrack)
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
                autoTrack = false
                autoScreenViews = false
                autoInstallEvents = false
                this.logger = logger
                this.storage = storage
                batch(maxSize = 20, maxWaitMs = 1_000, maxQueueSize = 50)
                session(idleTimeoutMs = 60_000, maxDurationMs = 120_000)
                trackingConsent(default = TrackingConsent.DENIED, persist = true)
            }
        val builder =
            PugOptions
                .Builder("pub_test")
                .endpoint("https://eu.pugs.dev")
                .debug(true)
                .dryRun(true)
                .autoTrack(false)
                .autoScreenViews(false)
                .autoInstallEvents(false)
                .logger(logger)
                .storage(storage)
                .batch(20, 1_000, 50)
                .session(60_000, 120_000)
                .trackingConsent(TrackingConsent.DENIED, true)
                .build()

        for (options in listOf(dsl, builder)) {
            assertEquals("https://eu.pugs.dev", options.endpoint)
            assertTrue(options.debug)
            assertTrue(options.dryRun)
            assertFalse(options.autoTrack)
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
            assertTrue(options.persistTrackingConsent)
        }
        assertTrue(logger.entries.isEmpty())
    }

    @Test
    fun `group setters leave unnamed members at their defaults`() {
        val options =
            PugOptions("pub_test") {
                batch(maxWaitMs = 0)
                session(maxDurationMs = 60_000)
                trackingConsent(persist = true)
            }

        assertEquals(10, options.batchMaxSize)
        assertEquals(0L, options.batchMaxWaitMs)
        assertEquals(1_000, options.batchMaxQueueSize)
        assertEquals(30 * 60_000L, options.sessionIdleTimeoutMs)
        assertEquals(60_000L, options.sessionMaxDurationMs)
        assertEquals(TrackingConsent.GRANTED, options.defaultTrackingConsent)
        assertTrue(options.persistTrackingConsent)
    }

    @Test
    fun `out-of-range numbers warn and fall back to their defaults`() {
        val logger = RecordingLogger()

        val options =
            PugOptions("pub_test") {
                this.logger = logger
                batch(maxSize = 0, maxWaitMs = -1, maxQueueSize = 0)
                session(idleTimeoutMs = 0, maxDurationMs = -5)
            }

        assertEquals(10, options.batchMaxSize)
        assertEquals(5_000L, options.batchMaxWaitMs)
        assertEquals(1_000, options.batchMaxQueueSize)
        assertEquals(30 * 60_000L, options.sessionIdleTimeoutMs)
        assertEquals(24 * 60 * 60_000L, options.sessionMaxDurationMs)
        assertEquals(
            listOf(
                "batch maxSize 0 must be at least 1; using 10",
                "batch maxWaitMs -1 must be at least 0; using 5000",
                "batch maxQueueSize 0 must be at least 1; using 1000",
                "session idleTimeoutMs 0 must be at least 1; using 1800000",
                "session maxDurationMs -5 must be at least 1; using 86400000",
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
                batch(maxSize = 5_000)
            }

        assertEquals(1_000, options.batchMaxSize)
        assertEquals(listOf("batch maxSize 5000 is above the server's limit; using 1000"), logger.warnings)
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
        val builder = PugOptions.Builder("pub_test").batch(maxSize = 20)

        val first = builder.build()
        val second = builder.batch(maxSize = 30).build()

        assertEquals(20, first.batchMaxSize)
        assertEquals(30, second.batchMaxSize)
    }
}
