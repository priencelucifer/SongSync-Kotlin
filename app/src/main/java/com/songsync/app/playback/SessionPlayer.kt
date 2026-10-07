package com.songsync.app.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** What the system media controls may do. Implemented by the session manager. */
interface MediaControls {
    /** Whether the group (or, on a client, this phone) is playing, as the user understands it. */
    val isPlaying: Boolean
    val canSeek: Boolean
    val canSkip: Boolean
    fun setPlaying(playing: Boolean)
    fun seekTo(positionMs: Long)
    fun skipNext()
}

/**
 * The player exposed to the MediaSession (lock screen, notification, headset buttons, Android
 * Auto). Commands are routed to the group logic instead of the local ExoPlayer: on the host,
 * "pause" pauses everyone; on a client it only pauses this phone.
 */
@OptIn(UnstableApi::class)
class SessionPlayer(player: Player, private val controls: MediaControls) : ForwardingSimpleBasePlayer(player) {

    /** Call when [controls] changed so the session re-reads the state. */
    fun refresh() = invalidateState()

    override fun getState(): State {
        val base = super.getState()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
            )
            .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, controls.canSeek)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, controls.canSkip)
            .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, controls.canSkip)
            .build()
        return base.buildUpon()
            .setAvailableCommands(commands)
            .setPlayWhenReady(controls.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            // Hide the sync engine's tiny speed nudges from the outside world.
            .setPlaybackParameters(PlaybackParameters.DEFAULT)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        controls.setPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controls.skipNext()
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> controls.seekTo(positionMs)
            else -> Unit // previous/back/forward are not offered
        }
        return Futures.immediateVoidFuture()
    }

    // The app owns the ExoPlayer's lifecycle; external controllers must not stop or release it.
    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleStop(): ListenableFuture<*> = Futures.immediateVoidFuture()
    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()
}
