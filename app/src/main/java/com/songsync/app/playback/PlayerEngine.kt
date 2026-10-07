package com.songsync.app.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.source.NewPipeDownloader
import com.songsync.app.data.source.YouTubeStreamInterceptor
import com.songsync.app.sync.SyncPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * The app's single ExoPlayer, adapted to what the sync engine needs. Lives for the whole
 * process and must only be touched from the main thread.
 */
@OptIn(UnstableApi::class)
class PlayerEngine(context: Context, okHttp: OkHttpClient) : SyncPlayer {

    val player: ExoPlayer

    /** Set when the system paused us for good (another app took audio focus, headphones out). */
    private val _systemHold = MutableStateFlow(false)
    val systemHold: StateFlow<Boolean> = _systemHold.asStateFlow()

    private val _lastError = MutableStateFlow<PlaybackException?>(null)
    val lastError: StateFlow<PlaybackException?> = _lastError.asStateFlow()

    /** Notified whenever readiness/playing state changes, so the follower reacts immediately. */
    var onStateChanged: (() -> Unit)? = null

    override var loadedTrackKey: String? = null
        private set

    init {
        val app = context.applicationContext
        val http = OkHttpDataSource.Factory(okHttp.newBuilder().addInterceptor(YouTubeStreamInterceptor()).build())
            .setUserAgent(NewPipeDownloader.USER_AGENT)
        val renderers = DefaultRenderersFactory(app)
            // Speed nudges are applied by the audio output directly: immediate, no flush, and
            // ExoPlayer keeps reporting the true position while they are active.
            .setEnableAudioOutputPlaybackParameters(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 120_000, 1_000, 2_000)
            .build()
        player = ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)))
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekParameters(SeekParameters.EXACT)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                onStateChanged?.invoke()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onStateChanged?.invoke()
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                onStateChanged?.invoke()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)
                ) {
                    // Set synchronously, before the follower could call play() and steal focus back.
                    _systemHold.value = true
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _lastError.value = error
                onStateChanged?.invoke()
            }
        })
    }

    // --- SyncPlayer --------------------------------------------------------------------------

    override val isReady: Boolean get() = player.playbackState == Player.STATE_READY
    override val isPlaying: Boolean get() = player.isPlaying
    override val isInterrupted: Boolean
        get() = player.playWhenReady && player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE
    override val positionMs: Long get() = player.currentPosition
    override val speed: Float get() = player.playbackParameters.speed

    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    // --- loading -----------------------------------------------------------------------------

    val durationMs: Long? get() = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    /**
     * Loads [resolved] paused at 0 and suspends until it can play through the first seconds
     * without stalling. Throws on playback errors or timeout.
     */
    suspend fun load(resolved: ResolvedTrack) {
        loadedTrackKey = null
        _lastError.value = null
        val track = resolved.track
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .apply { if (track.artworkUrl.isNotBlank()) setArtworkUri(track.artworkUrl.toUri()) }
            .build()
        player.pause()
        player.setMediaItem(
            MediaItem.Builder().setUri(resolved.streamUrl).setMediaId(track.key).setMediaMetadata(metadata).build(),
        )
        player.prepare()
        withTimeout(LOAD_TIMEOUT_MS) {
            while (true) {
                _lastError.value?.let { throw it }
                if (isReady && hasHeadroom()) break
                delay(POLL_MS)
            }
        }
        loadedTrackKey = track.key
        onStateChanged?.invoke()
    }

    /** Retries after a network error without losing the position (e.g. a Wi-Fi blip). */
    fun recover() {
        if (player.playerError != null) {
            _lastError.value = null
            player.prepare()
        }
    }

    fun clearSystemHold() {
        _systemHold.value = false
    }

    fun unload() {
        loadedTrackKey = null
        player.stop()
        player.clearMediaItems()
        player.setPlaybackSpeed(1f)
        onStateChanged?.invoke()
    }

    private fun hasHeadroom(): Boolean {
        val buffered = player.bufferedPosition - player.currentPosition
        val duration = durationMs
        return buffered >= MIN_BUFFER_TO_START_MS || (duration != null && player.bufferedPosition >= duration)
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 25_000L
        const val POLL_MS = 50L
        const val MIN_BUFFER_TO_START_MS = 2_000L
    }
}
