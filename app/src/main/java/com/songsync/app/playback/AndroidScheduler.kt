package com.songsync.app.playback

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.songsync.app.sync.Cancellable
import com.songsync.app.sync.MonotonicClock
import com.songsync.app.sync.Scheduler

object SystemMonotonicClock : MonotonicClock {
    override fun nowNs(): Long = SystemClock.elapsedRealtimeNanos()
}

/**
 * Runs actions on the main thread (where ExoPlayer lives) at precise monotonic times. Handler
 * delays only have millisecond granularity, so it wakes slightly early and spins for the last
 * stretch; the follower measures and corrects whatever error remains.
 */
class AndroidScheduler(private val handler: Handler = Handler(Looper.getMainLooper())) : Scheduler {

    override fun schedule(atNs: Long, action: () -> Unit): Cancellable {
        val runnable = Runnable {
            while (true) {
                val remaining = atNs - SystemClock.elapsedRealtimeNanos()
                if (remaining <= 0 || remaining > MAX_SPIN_NS) break
            }
            action()
        }
        val delayMs = (atNs - SystemClock.elapsedRealtimeNanos() - SPIN_NS) / 1_000_000
        if (delayMs <= 0) handler.post(runnable) else handler.postDelayed(runnable, delayMs)
        return Cancellable { handler.removeCallbacks(runnable) }
    }

    private companion object {
        const val SPIN_NS = 2_000_000L
        /** Never busy-wait on the UI thread for longer than this. */
        const val MAX_SPIN_NS = 4_000_000L
    }
}
