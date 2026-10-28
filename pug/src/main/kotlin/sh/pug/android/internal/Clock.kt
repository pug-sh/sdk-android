package sh.pug.android.internal

internal fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}
