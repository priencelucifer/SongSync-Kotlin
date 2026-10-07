package com.songsync.app.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import com.songsync.app.R
import com.songsync.app.util.NearbyPermissions

/**
 * Makes sure Nearby can work before hosting/joining: explains and requests the permissions,
 * sends the user to settings if they were permanently denied, and asks for Location to be
 * switched on where old Android versions need it.
 */
class PermissionGate internal constructor() {
    internal var pending by mutableStateOf<(() -> Unit)?>(null)
    internal var dialog by mutableStateOf<Dialog?>(null)
    internal lateinit var check: () -> Unit

    internal enum class Dialog { RATIONALE, BLOCKED, LOCATION_OFF }

    /** Runs [action] once everything Nearby needs is in place. */
    fun run(action: () -> Unit) {
        pending = action
        check()
    }
}

@Composable
fun rememberPermissionGate(): PermissionGate {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val gate = remember { PermissionGate() }

    fun proceed() {
        if (NearbyPermissions.locationServicesOff(context)) {
            gate.dialog = PermissionGate.Dialog.LOCATION_OFF
        } else {
            gate.pending?.invoke()
            gate.pending = null
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val missing = NearbyPermissions.missing(context)
        when {
            missing.isEmpty() -> proceed()
            activity != null && missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) } ->
                gate.dialog = PermissionGate.Dialog.BLOCKED
            else -> gate.pending = null
        }
    }

    gate.check = {
        if (NearbyPermissions.missing(context).isEmpty()) proceed() else gate.dialog = PermissionGate.Dialog.RATIONALE
    }

    when (gate.dialog) {
        PermissionGate.Dialog.RATIONALE -> GateDialog(
            title = stringResource(R.string.permission_title),
            text = stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.permission_rationale
                else R.string.permission_rationale_legacy,
            ),
            confirm = stringResource(R.string.continue_action),
            onConfirm = {
                gate.dialog = null
                launcher.launch(NearbyPermissions.toRequest.toTypedArray())
            },
            onDismiss = {
                gate.dialog = null
                gate.pending = null
            },
        )
        PermissionGate.Dialog.BLOCKED -> GateDialog(
            title = stringResource(R.string.permission_title),
            text = stringResource(R.string.permission_blocked),
            confirm = stringResource(R.string.permission_open_settings),
            onConfirm = {
                gate.dialog = null
                gate.pending = null
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onDismiss = {
                gate.dialog = null
                gate.pending = null
            },
        )
        PermissionGate.Dialog.LOCATION_OFF -> GateDialog(
            title = stringResource(R.string.location_off_title),
            text = stringResource(R.string.location_off_text),
            confirm = stringResource(R.string.permission_open_settings),
            onConfirm = {
                gate.dialog = null
                gate.pending = null
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            onDismiss = {
                gate.dialog = null
                gate.pending = null
            },
        )
        null -> Unit
    }
    return gate
}

@Composable
private fun GateDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.not_now)) } },
    )
}
