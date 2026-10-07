package com.songsync.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.songsync.app.R
import com.songsync.app.session.CalibrationUi
import com.songsync.app.ui.AppViewModel
import kotlin.math.roundToInt

/** Corrections spreading more than this (about 2 m of sound travel) may be distance, not delay. */
private const val DISTANCE_WARNING_SPREAD_MS = 6.0

/** What a microphone-based run does. */
enum class MicRun {
    /** Measure and correct every phone (Auto-calibrate echo). */
    CALIBRATE,
    /** Measure only (Check sync). */
    CHECK,
    /** Check, calibrate, check three times, then show the sync report. */
    REPORT,
}

/** Returns an action that asks for the microphone if needed, then starts [mode]. */
@Composable
fun rememberCalibrationStarter(vm: AppViewModel, mode: MicRun = MicRun.CALIBRATE, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val start = {
        when (mode) {
            MicRun.CALIBRATE -> vm.autoCalibrate()
            MicRun.CHECK -> vm.autoCalibrate(measureOnly = true)
            MicRun.REPORT -> vm.runSyncReport()
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else onDenied()
    }
    return remember(vm, launcher, mode) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) start() else launcher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

/** Progress while calibrating, then the per-phone results (or why it failed). */
@Composable
fun CalibrationDialog(state: CalibrationUi, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        CalibrationUi.Idle -> Unit
        is CalibrationUi.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.calibration_running_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.steps > 0) Text(stringResource(R.string.sync_report_step, state.step, state.steps))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(
                            when (state.stage) {
                                CalibrationUi.Stage.STARTING -> R.string.calibration_stage_starting
                                CalibrationUi.Stage.LISTENING -> R.string.calibration_stage_listening
                                CalibrationUi.Stage.ANALYZING -> R.string.calibration_stage_analyzing
                            },
                        ),
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
        )
        is CalibrationUi.Done -> if (state.measureOnly) SyncCheckResult(state, onDismiss) else AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.calibration_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.outcomes.forEach { outcome ->
                        val correction = outcome.correctionMs
                        Text(
                            when {
                                outcome.paused -> stringResource(R.string.calibration_outcome_paused, outcome.name)
                                outcome.rejectedMs != null ->
                                    stringResource(R.string.calibration_outcome_rejected, outcome.name, outcome.rejectedMs.roundToInt())
                                correction == null -> stringResource(R.string.calibration_outcome_missing, outcome.name)
                                outcome.isSelf -> stringResource(R.string.calibration_outcome_self, outcome.name, correction.roundToInt())
                                else -> stringResource(R.string.calibration_outcome, outcome.name, correction.roundToInt())
                            },
                        )
                    }
                    val corrections = state.outcomes.mapNotNull { it.correctionMs }
                    if (corrections.size >= 2 && corrections.max() - corrections.min() > DISTANCE_WARNING_SPREAD_MS) {
                        Text(stringResource(R.string.calibration_distance_warning), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(R.string.calibration_done_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
        is CalibrationUi.Report -> SyncReportDialog(state.text, onDismiss)
        is CalibrationUi.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.calibration_failed_title)) },
            text = { Text(stringResource(state.reason)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
    }
}

/** The automatic sync test's report: small monospace text sized for one screenshot, plus Copy. */
@Composable
private fun SyncReportDialog(text: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 8.dp),
        title = { Text(stringResource(R.string.sync_report_title)) },
        text = {
            SelectionContainer {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    softWrap = true,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("SongSync sync report", text))) }
            }) { Text(stringResource(R.string.sync_report_copy)) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** "Check sync" results: how late each phone is heard here, and the overall spread. */
@Composable
private fun SyncCheckResult(state: CalibrationUi.Done, onDismiss: () -> Unit) {
    val heard = state.outcomes.mapNotNull { it.correctionMs }
    val spread = if (heard.size >= 2) heard.max() - heard.min() else 0.0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_check_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.outcomes.forEach { outcome ->
                    val late = outcome.correctionMs
                    Text(
                        when {
                            outcome.paused -> stringResource(R.string.calibration_outcome_paused, outcome.name)
                            late == null -> stringResource(R.string.calibration_outcome_missing, outcome.name)
                            outcome.isSelf -> stringResource(R.string.sync_check_self, outcome.name)
                            else -> stringResource(R.string.sync_check_outcome, outcome.name, late)
                        },
                    )
                }
                Text(stringResource(R.string.sync_check_spread, spread), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        when {
                            spread <= 2.0 -> R.string.sync_check_excellent
                            spread <= 5.0 -> R.string.sync_check_good
                            spread <= 10.0 -> R.string.sync_check_ok
                            else -> R.string.sync_check_bad
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}
