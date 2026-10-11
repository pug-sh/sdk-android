package sh.pug.android.internal

import kotlin.random.Random
import sh.pug.android.PugLogger
import sh.pug.android.PugStorage
import sh.pug.android.internal.logging.SafeLogger

internal class RecordingLogger : PugLogger {
    data class Entry(val level: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    val debugs: List<String> get() = messages("debug")
    val warnings: List<String> get() = messages("warn")
    val errors: List<String> get() = messages("error")

    override fun debug(message: String) {
        entries += Entry("debug", message, null)
    }

    override fun warn(message: String, throwable: Throwable?) {
        entries += Entry("warn", message, throwable)
    }

    override fun error(message: String, throwable: Throwable?) {
        entries += Entry("error", message, throwable)
    }

    fun safe(debug: Boolean = true): SafeLogger = SafeLogger(this, debug)

    private fun messages(level: String) = entries.filter { it.level == level }.map { it.message }
}

internal class FakeClock(var now: Long = 1_760_000_000_000L) : PugClock {
    override fun nowMillis(): Long = now
}

/** Valid, predictable UUIDs: `00000000-0000-7000-8000-000000000001`, then …02, and so on. */
internal class SequentialIds : IdGenerator {
    private var count = 0L

    override fun nextId(): String = "00000000-0000-7000-8000-%012x".format(++count)
}

/** Always draws [value]; 0.5 puts retry jitter at exactly zero. */
internal class FixedRandom(private val value: Double = 0.5) : Random() {
    override fun nextBits(bitCount: Int): Int = 0

    override fun nextDouble(): Double = value
}

internal class ThrowingStorage(private val failure: Throwable) : PugStorage {
    var calls: Int = 0
        private set

    override fun getString(key: String): String? {
        calls++
        throw failure
    }

    override fun putString(key: String, value: String) {
        calls++
        throw failure
    }

    override fun remove(key: String) {
        calls++
        throw failure
    }
}
