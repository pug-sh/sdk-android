package sh.pug.android.internal.logging

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import sh.pug.android.Logger
import sh.pug.android.internal.RecordingLogger

class SafeLoggerTest {
    @Test
    fun `debug messages are delivered only when debug logging is on`() {
        val quiet = RecordingLogger()
        val verbose = RecordingLogger()

        SafeLogger(quiet, isDebugEnabled = false).debug("hidden")
        SafeLogger(verbose, isDebugEnabled = true).debug("shown")

        assertTrue(quiet.entries.isEmpty())
        assertEquals(listOf("shown"), verbose.debugs)
    }

    @Test
    fun `warnings and errors are always delivered with their throwable`() {
        val delegate = RecordingLogger()
        val failure = IOException("disk")
        val logger = SafeLogger(delegate, isDebugEnabled = false)

        logger.warn("careful")
        logger.error("broken", failure)

        assertEquals(
            listOf(RecordingLogger.Entry("warn", "careful", null), RecordingLogger.Entry("error", "broken", failure)),
            delegate.entries,
        )
    }

    @Test
    fun `a throwing logger is contained, whatever it throws`() {
        for (failure in listOf(IllegalStateException(), OutOfMemoryError(), CancellationException("not ours"))) {
            val logger = SafeLogger(ThrowingLogger(failure), isDebugEnabled = true)

            logger.debug("a")
            logger.warn("b", IOException())
            logger.error("c")
        }
    }

    @Test
    fun `a logger keeps working after it threw once`() {
        val delegate =
            object : Logger {
                var calls = 0

                override fun debug(message: String) = Unit

                override fun warn(message: String, throwable: Throwable?) {
                    calls++
                    if (calls == 1) throw IllegalStateException()
                }

                override fun error(message: String, throwable: Throwable?) = Unit
            }
        val logger = SafeLogger(delegate, isDebugEnabled = false)

        logger.warn("first")
        logger.warn("second")

        assertEquals(2, delegate.calls)
    }

    private class ThrowingLogger(private val failure: Throwable) : Logger {
        override fun debug(message: String) = throw failure

        override fun warn(message: String, throwable: Throwable?) = throw failure

        override fun error(message: String, throwable: Throwable?) = throw failure
    }
}
