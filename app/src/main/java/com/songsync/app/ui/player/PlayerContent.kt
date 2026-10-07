package com.songsync.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songsync.app.R
import com.songsync.app.data.model.Track
import com.songsync.app.ui.AppViewModel
import com.songsync.app.ui.components.CalibrationControl
import com.songsync.app.ui.components.DiagnosticsPanel
import com.songsync.app.ui.components.SeekBar
import com.songsync.app.ui.components.SyncStatusChip
import com.songsync.app.ui.components.TrackArtwork
import com.songsync.app.ui.components.TrackRow
import com.songsync.app.ui.components.artistOrUnknown
import com.songsync.app.ui.components.rememberPlaybackPosition

/** The full player. The host controls the group; a client can only pause itself. */
@Composable
fun PlayerContent(vm: AppViewModel, isHost: Boolean, hostName: String, modifier: Modifier = Modifier) {
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val track = nowPlaying.track

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        val artSize = if (wide) minOf(maxHeight - 48.dp, 360.dp) else minOf(maxWidth - 48.dp, 340.dp)

        if (track == null) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val loading = nowPlaying.loading
                if (loading != null) CircularProgressIndicator()
                Text(
                    if (loading != null) stringResource(R.string.player_loading, loading.title)
                    else stringResource(R.string.player_waiting_host, hostName),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                SyncPanel(vm)
            }
            return@BoxWithConstraints
        }

        if (wide) {
            Row(Modifier.fillMaxSize().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArtwork(track.artworkUrl, artSize, corner = 16.dp)
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    PlayerDetails(vm, track, isHost)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TrackArtwork(track.artworkUrl, artSize, corner = 16.dp)
                Spacer(Modifier.height(24.dp))
                PlayerDetails(vm, track, isHost)
            }
        }
    }
}

@Composable
private fun ColumnScope.PlayerDetails(vm: AppViewModel, track: Track, isHost: Boolean) {
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val queue by vm.queue.collectAsStateWithLifecycle()
    val onHold by vm.onHold.collectAsStateWithLifecycle()

    Text(track.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
    Text(
        artistOrUnknown(track),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    nowPlaying.loading?.takeIf { it.key != track.key }?.let { next ->
        Text(
            stringResource(R.string.player_loading, next.title),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
    Spacer(Modifier.height(16.dp))

    val duration = rememberPlaybackPosition(nowPlaying.playing) { vm.durationMs() }
    SeekBar(
        playing = nowPlaying.playing,
        position = vm::positionMs,
        durationMs = duration,
        onSeek = if (isHost) vm::seekTo else null,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    if (isHost) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp))
            Spacer(Modifier.width(24.dp))
            FilledIconButton(onClick = { vm.setPlaying(!nowPlaying.playing) }, modifier = Modifier.size(72.dp)) {
                Icon(
                    painterResource(if (nowPlaying.playing) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (nowPlaying.playing) R.string.action_pause else R.string.action_play),
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = vm::skipNext, enabled = queue.isNotEmpty(), modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.action_next))
            }
        }
    } else {
        FilledTonalButton(onClick = { vm.setLocalHold(!onHold) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Icon(painterResource(if (onHold) R.drawable.ic_play else R.drawable.ic_pause), contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (onHold) R.string.hold_rejoin else R.string.hold_pause_here))
        }
        if (onHold) {
            Text(
                stringResource(R.string.hold_explainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
        }
    }

    Spacer(Modifier.height(16.dp))
    SyncPanel(vm)

    if (isHost && queue.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Text(
            stringResource(R.string.player_up_next),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        )
        queue.forEachIndexed { index, queued ->
            TrackRow(queued, onClick = null) {
                IconButton(onClick = { vm.removeFromQueue(index) }) {
                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.player_remove))
                }
            }
        }
    }
}

/** Sync status, echo calibration and (optionally) diagnostics. */
@Composable
private fun SyncPanel(vm: AppViewModel) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val route by vm.audioRoute.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val log by vm.syncLog.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        SyncStatusChip(stats)
        Spacer(Modifier.height(8.dp))
        CalibrationControl(calibration, route, vm::setCalibration, Modifier.fillMaxWidth())
        if (diagnostics) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth()) { DiagnosticsPanel(stats, log) }
        }
    }
}
