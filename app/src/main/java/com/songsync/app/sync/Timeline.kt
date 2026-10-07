package com.songsync.app.sync

import kotlinx.serialization.Serializable

const val NANOS_PER_MS = 1_000_000L

/**
 * The shared timeline, owned by the host and broadcast to every phone.
 *
 * Instead of imperative "play now" commands, the host publishes where the track is at a given
 * moment on the host's monotonic clock. Any phone that knows its offset to the host clock can
 * compute the expected position at any time, so lost messages, late joins and reconnects all
 * recover by simply applying the latest state.
 */
@Serializable
data class PlaybackState(
    /** Increases with every change; stale or duplicate states are ignored. */
    val seq: Long,
    /** [com.songsync.app.data.model.Track.key] of the current track, or null when idle. */
    val trackKey: String?,
    val playing: Boolean,
    /** Host monotonic time (elapsedRealtimeNanos on the host) at which [anchorPositionMs] holds. */
    val anchorHostNs: Long,
    val anchorPositionMs: Long,
) {
    /**
     * Expected track position at host time [hostNs]. Before a play anchor the track is held at
     * the anchor position, which is what lets every phone start from the same sample.
     */
    fun positionAt(hostNs: Long): Long =
        if (playing && hostNs > anchorHostNs) {
            anchorPositionMs + (hostNs - anchorHostNs + NANOS_PER_MS / 2) / NANOS_PER_MS
        } else {
            anchorPositionMs
        }

    /** True when both states describe the same motion, so switching between them is seamless. */
    fun isContinuationOf(other: PlaybackState, atHostNs: Long, toleranceMs: Long = 2): Boolean =
        trackKey == other.trackKey &&
            playing == other.playing &&
            (!playing || (atHostNs >= anchorHostNs && atHostNs >= other.anchorHostNs)) &&
            kotlin.math.abs(positionAt(atHostNs) - other.positionAt(atHostNs)) <= toleranceMs

    companion object {
        val Idle = PlaybackState(seq = 0, trackKey = null, playing = false, anchorHostNs = 0, anchorPositionMs = 0)
    }
}
