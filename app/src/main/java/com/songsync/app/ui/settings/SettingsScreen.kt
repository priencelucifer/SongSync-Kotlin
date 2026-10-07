package com.songsync.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songsync.app.BuildConfig
import com.songsync.app.R
import com.songsync.app.data.SettingsStore
import com.songsync.app.ui.AppViewModel
import com.songsync.app.ui.components.CalibrationControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val savedName by vm.deviceName.collectAsStateWithLifecycle()
    val maxBitrate by vm.maxBitrateKbps.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val calibration by vm.calibration.collectAsStateWithLifecycle()
    val route by vm.audioRoute.collectAsStateWithLifecycle()
    var name by rememberSaveable(savedName) { mutableStateOf(savedName) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val resources = LocalResources.current

    // Save a changed name however the user leaves the screen.
    val latestName by rememberUpdatedState(name)
    DisposableEffect(Unit) {
        onDispose { if (latestName.isNotBlank() && latestName != savedName) vm.setDeviceName(latestName) }
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            Section(R.string.settings_device_name) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(SettingsStore.MAX_NAME_LENGTH) },
                    singleLine = true,
                    supportingText = { Text(stringResource(R.string.settings_device_name_hint)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (name.isNotBlank()) vm.setDeviceName(name)
                        focus.clearFocus()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Section(R.string.settings_quality) {
                val options = listOf(96, 160, 320)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, kbps ->
                        SegmentedButton(
                            selected = maxBitrate == kbps,
                            onClick = { vm.setMaxBitrate(kbps) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(R.string.quality_kbps, kbps)) }
                    }
                }
                Hint(R.string.settings_quality_hint)
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                CalibrationControl(calibration, route, vm::setCalibration)
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_reset_latency)) },
                supportingContent = { Text(stringResource(R.string.settings_reset_latency_hint)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_refresh), contentDescription = null) },
                modifier = Modifier.clickable { confirmReset = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_diagnostics)) },
                supportingContent = { Text(stringResource(R.string.settings_diagnostics_hint)) },
                leadingContent = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) },
                trailingContent = { Switch(checked = diagnostics, onCheckedChange = vm::setDiagnostics) },
                modifier = Modifier.clickable { vm.setDiagnostics(!diagnostics) },
            )
            HorizontalDivider()
            Section(R.string.settings_about) {
                Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Hint(R.string.settings_license)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_latency)) },
            text = { Text(stringResource(R.string.settings_reset_latency_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetLearnedLatency()
                    onMessage(resources.getString(R.string.settings_reset_done))
                }) { Text(stringResource(R.string.calibration_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
