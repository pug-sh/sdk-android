package sh.pug.android

/**
 * Receives the SDK's diagnostics in place of Logcat. Calls may arrive on any thread; a logger that
 * throws is contained. Debug messages arrive only when [Options.Builder.debug] is on.
 */
public interface Logger {
    public fun debug(message: String)

    public fun warn(message: String, throwable: Throwable?)

    public fun error(message: String, throwable: Throwable?)
}
