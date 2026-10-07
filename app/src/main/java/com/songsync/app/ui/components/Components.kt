package com.songsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.songsync.app.R
import com.songsync.app.data.model.Track
import com.songsync.app.playback.AudioRoute
import com.songsync.app.session.SyncStats
import com.songsync.app.sync.FollowerPhase
import com.songsync.app.ui.theme.SyncColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TrackArtwork(url: String, size: Dp, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size / 2.5f),
        )
        if (url.isNotBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
fun artistOrUnknown(track: Track): String = track.artist.ifBlank { stringResource(R.string.unknown_artist) }

@Composable
fun TrackRow(track: Track, onClick: (() -> Unit)?, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrackArtwork(track.artworkUrl, 52.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = buildString {
                append(artistOrUnknown(track))
                if (track.durationMs > 0) append(" · ").append(formatTime(track.durationMs))
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * Current playback position, refreshed a few times a second. Only the composable that calls
 * this recomposes as time passes, never the whole screen.
 */
@Composable
fun rememberPlaybackPosition(playing: Boolean, read: () -> Long): Long {
    var position by remember { mutableLongStateOf(read()) }
    LaunchedEffect(playing) {
        while (true) {
            position = read()
            delay(if (playing) 250 else 1_000)
        }
    }
    return position
}

/** Seeks only when the finger lifts; dragging just moves the thumb (no seek storms). */
@Composable
fun SeekBar(
    playing: Boolean,
    position: () -> Long,
    durationMs: Long,
    onSeek: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val current = rememberPlaybackPosition(playing, position)
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val max = durationMs.coerceAtLeast(1).toFloat()
    val shown = if (dragging) dragValue else current.toFloat().coerceIn(0f, max)
    Column(modifier) {
        Slider(
            value = shown,
            valueRange = 0f..max,
            enabled = onSeek != null && durationMs > 0,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                if (dragging) onSeek?.invoke(dragValue.toLong())
                dragging = false
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val style = MaterialTheme.typography.labelMedium
            val color = MaterialTheme.colorScheme.onSurfaceVariant
            Text(formatTime(shown.toLong()), style = style, color = color)
            Text(if (durationMs > 0) formatTime(durationMs) else "–:––", style = style, color = color)
        }
    }
}

@Composable
fun SyncStatusChip(stats: SyncStats, modifier: Modifier = Modifier) {
    val follower = stats.follower
    val error = follower.errorMs
    val (label, color) = when (follower.phase) {
        FollowerPhase.LOCKED -> {
            val text = if (error != null) {
                stringResource(R.string.sync_locked) + " · " + stringResource(R.string.sync_error_ms, abs(error).roundToInt())
            } else {
                stringResource(R.string.sync_locked)
            }
            text to when {
                error == null || abs(error) <= 10 -> SyncColors.good
                abs(error) <= 30 -> SyncColors.warn
                else -> SyncColors.bad
            }
        }
        FollowerPhase.SETTLING, FollowerPhase.ARMED, FollowerPhase.PAUSE_PENDING, FollowerPhase.WAITING_FOR_CLOCK ->
            stringResource(R.string.sync_syncing) to SyncColors.warn
        FollowerPhase.BUFFERING -> stringResource(R.string.sync_buffering) to SyncColors.warn
        FollowerPhase.WAITING_FOR_TRACK -> stringResource(R.string.sync_waiting) to SyncColors.warn
        FollowerPhase.ON_HOLD -> stringResource(R.string.sync_hold) to MaterialTheme.colorScheme.onSurfaceVariant
        FollowerPhase.INTERRUPTED -> stringResource(R.string.sync_interrupted) to SyncColors.bad
        FollowerPhase.PAUSED, FollowerPhase.IDLE -> stringResource(R.string.sync_paused) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        modifier = modifier,
        label = { Text(label) },
        leadingIcon = {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        },
        colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.onSurface),
    )
}

@Composable
fun routeLabel(route: AudioRoute): String = when (route.type) {
    AudioRoute.Type.SPEAKER -> stringResource(R.string.route_speaker)
    AudioRoute.Type.WIRED -> stringResource(R.string.route_wired)
    AudioRoute.Type.USB -> route.name.ifBlank { stringResource(R.string.route_usb) }
    AudioRoute.Type.BLUETOOTH -> route.name
}

@Composable
fun CalibrationControl(valueMs: Double, route: AudioRoute, onChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.calibration_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.calibration_value, valueMs.roundToInt()), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onChange(0.0) }, enabled = valueMs != 0.0) { Text(stringResource(R.string.calibration_reset)) }
        }
        Slider(
            value = valueMs.toFloat(),
            onValueChange = { onChange((it / 5).roundToInt() * 5.0) },
            valueRange = -300f..300f,
        )
        Text(
            stringResource(R.string.calibration_subtitle, routeLabel(route)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun linkLabel(quality: Int?): String = stringResource(
    when (quality) {
        1 -> R.string.link_low
        2 -> R.string.link_medium
        3 -> R.string.link_high
        else -> R.string.link_unknown
    },
)

@Composable
fun DiagnosticsPanel(stats: SyncStats, log: List<String>, modifier: Modifier = Modifier) {
    val f = stats.follower
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleSmall)
        DiagnosticRow(R.string.diag_phase, f.phase.name)
        DiagnosticRow(R.string.diag_error, f.errorMs?.let { "%+.1f ms".format(it) } ?: "–")
        DiagnosticRow(R.string.diag_speed, "%.4f×".format(f.speed))
        DiagnosticRow(R.string.diag_offset, f.offsetMs?.let { "%+.1f ms".format(it) } ?: "–")
        DiagnosticRow(
            R.string.diag_rtt,
            if (stats.minRttMs != null && stats.medianRttMs != null) {
                "%.1f / %.1f / %.1f ms (%d)".format(stats.minRttMs, stats.medianRttMs, stats.p90RttMs ?: 0.0, stats.clockSamples)
            } else {
                "–"
            },
        )
        DiagnosticRow(
            R.string.diag_clock_spread,
            if (stats.clockSpreadMs != null && stats.clockAgeS != null) {
                "±%.2f ms (%.0f s ago)".format(stats.clockSpreadMs, stats.clockAgeS)
            } else {
                "–"
            },
        )
        DiagnosticRow(R.string.diag_clock_skew, stats.clockSkewPpm?.let { "%+.1f ppm".format(it) } ?: "–")
        DiagnosticRow(R.string.diag_start_latency, "%.1f ms".format(f.startLatencyMs))
        DiagnosticRow(R.string.diag_resyncs, f.hardResyncs.toString())
        DiagnosticRow(R.string.diag_link, linkLabel(stats.linkQuality))
        if (log.isNotEmpty()) {
            Text(
                stringResource(R.string.diag_log),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            log.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun DiagnosticRow(label: Int, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Color.Unspecified)
    }
}
