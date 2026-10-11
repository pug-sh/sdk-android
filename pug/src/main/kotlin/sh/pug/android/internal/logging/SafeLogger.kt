package sh.pug.android.internal.logging

import android.util.Log
import sh.pug.android.PugLogger

internal const val LOG_TAG: String = "Pug"

internal object LogcatLogger : PugLogger {
    override fun debug(message: String) {
        Log.d(LOG_TAG, message)
    }

    override fun warn(message: String, throwable: Throwable?) {
        Log.w(LOG_TAG, message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        Log.e(LOG_TAG, message, throwable)
    }
}

/**
 * The only logger the SDK calls: drops debug messages unless debug logging is on, and contains a
 * delegate that throws. Throwables are caught whole here, CancellationException included: a logger
 * is plain host code, not a suspension point, so nothing it throws is a cancellation of ours.
 */
internal class SafeLogger(private val delegate: PugLogger, val isDebugEnabled: Boolean) {
    @Volatile private var failureReported = false

    fun debug(message: String) {
        if (!isDebugEnabled) return
        try {
            delegate.debug(message)
        } catch (t: Throwable) {
            reportFailure(t)
        }
    }

    fun warn(message: String, throwable: Throwable? = null) {
        try {
            delegate.warn(message, throwable)
        } catch (t: Throwable) {
            reportFailure(t)
        }
    }

    fun error(message: String, throwable: Throwable? = null) {
        try {
            delegate.error(message, throwable)
        } catch (t: Throwable) {
            reportFailure(t)
        }
    }

    private fun reportFailure(failure: Throwable) {
        if (failureReported) return
        failureReported = true
        try {
            Log.w(LOG_TAG, "The PugLogger threw; its failing messages are dropped", failure)
        } catch (t: Throwable) {
            // Nowhere left to report to.
        }
    }
}
