package com.songsync.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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

/** Returns an action that asks for the microphone if needed, then starts auto-calibration. */
@Composable
fun rememberCalibrationStarter(vm: AppViewModel, onDenied: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.autoCalibrate() else onDenied()
    }
    return remember(vm, launcher) {
        {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (granted) vm.autoCalibrate() else launcher.launch(Manifest.permission.RECORD_AUDIO)
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
        is CalibrationUi.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.calibration_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.outcomes.forEach { outcome ->
                        val correction = outcome.correctionMs
                        Text(
                            when {
                                correction == null -> stringResource(R.string.calibration_outcome_missing, outcome.name)
                                outcome.isSelf -> stringResource(R.string.calibration_outcome_self, outcome.name, correction.roundToInt())
                                else -> stringResource(R.string.calibration_outcome, outcome.name, correction.roundToInt())
                            },
                        )
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
        is CalibrationUi.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.calibration_failed_title)) },
            text = { Text(stringResource(state.reason)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        )
    }
}
