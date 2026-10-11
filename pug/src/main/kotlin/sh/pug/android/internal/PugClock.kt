package sh.pug.android.internal

internal fun interface PugClock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: PugClock = PugClock { System.currentTimeMillis() }
    }
}
