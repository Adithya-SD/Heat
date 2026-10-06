package app.heat.core.util

/**
 * Monotonic milliseconds, injectable so tests can drive protocol timeouts with
 * virtual time instead of sleeping. Never use wall-clock time for liveness: it
 * jumps when the user or the network changes it.
 */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.nanoTime() / 1_000_000L }
    }
}
