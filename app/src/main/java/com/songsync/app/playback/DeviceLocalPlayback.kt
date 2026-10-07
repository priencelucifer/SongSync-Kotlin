package com.songsync.app.playback

import com.songsync.app.data.TrackResolver
import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.net.AudioFormatInfo
import com.songsync.app.sync.LocalPlayback
import com.songsync.app.sync.PlaybackFollower

/** [LocalPlayback] backed by the real ExoPlayer and music sources. */
class DeviceLocalPlayback(
    private val engine: PlayerEngine,
    private val resolver: TrackResolver,
    override val follower: PlaybackFollower,
) : LocalPlayback {

    override val durationMs: Long? get() = engine.durationMs

    override val audioFormat: AudioFormatInfo? get() = engine.audioFormat.value

    override suspend fun resolve(track: Track, allowAlternatives: Boolean): ResolvedTrack =
        resolver.resolve(track, allowAlternatives)

    override suspend fun prepare(resolved: ResolvedTrack) {
        engine.load(resolved)
        follower.onPlayerChanged()
    }
}
