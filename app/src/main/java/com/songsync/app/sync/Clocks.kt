package com.songsync.app.sync

/** Monotonic time source. On Android this is SystemClock.elapsedRealtimeNanos(), never wall time. */
fun interface MonotonicClock {
    fun nowNs(): Long
}

fun interface Cancellable {
    fun cancel()
}

/** Runs actions at precise local monotonic times. */
fun interface Scheduler {
    fun schedule(atNs: Long, action: () -> Unit): Cancellable
}
