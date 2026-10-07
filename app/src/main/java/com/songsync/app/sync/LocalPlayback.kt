package com.songsync.app.sync

import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.net.AudioFormatInfo

/** This phone's playback, as seen by the host/client coordinators. */
interface LocalPlayback {
    val follower: PlaybackFollower

    /** Duration of the loaded track once the player knows it. */
    val durationMs: Long?

    /** What the player is decoding, once known (for spotting phones playing different files). */
    val audioFormat: AudioFormatInfo? get() = null

    /**
     * Turns [track] into a stream URL. With [allowAlternatives] (host only) a blocked YouTube
     * video may be replaced by an equivalent upload; the returned track says which one.
     */
    suspend fun resolve(track: Track, allowAlternatives: Boolean): ResolvedTrack

    /** Loads the stream into the player and suspends until it can start without stalling. */
    suspend fun prepare(resolved: ResolvedTrack)
}

data class SessionConfig(
    val heartbeatMs: Long = 1_000,
    val readyTimeoutMs: Long = 8_000,
    /** Lead time for scheduled play/pause/seek = clamp(2 x worst p90 RTT + base, min, max). */
    val leadBaseMs: Long = 250,
    val minLeadMs: Long = 400,
    val maxLeadMs: Long = 1_500,
    val statusIntervalMs: Long = 2_000,
    val timeSyncBurst: Int = 16,
    val timeSyncBurstIntervalMs: Long = 60,
    val timeSyncIntervalMs: Long = 1_000,
    /** The follower is only given a clock offset once this many samples back it up. */
    val minClockSamples: Int = 4,
)
