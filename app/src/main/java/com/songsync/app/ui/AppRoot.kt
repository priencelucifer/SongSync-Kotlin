package com.songsync.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songsync.app.R
import com.songsync.app.session.SessionManager.State
import com.songsync.app.ui.components.rememberPermissionGate
import com.songsync.app.ui.home.HomeScreen
import com.songsync.app.ui.join.JoinScreen
import com.songsync.app.ui.session.ClientScreen
import com.songsync.app.ui.session.HostPlayerScreen
import com.songsync.app.ui.session.HostScreen
import com.songsync.app.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class Route { HOME, JOIN, HOST, CLIENT, SETTINGS }

@Composable
fun AppRoot(vm: AppViewModel) {
    val state by vm.sessionState.collectAsStateWithLifecycle()
    val deviceName by vm.deviceName.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmLeave by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val gate = rememberPermissionGate()
    val showMessage: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }

    LaunchedEffect(vm) {
        vm.messages.collect { message -> snackbar.showSnackbar(resources.getString(message.text, *message.args)) }
    }

    val inSession = state is State.Hosting || state is State.Joined || state is State.Reconnecting
    // Registered first so screen-level handlers (player, settings, join) take precedence.
    BackHandler(enabled = inSession && !showSettings) { confirmLeave = true }

    val route = when {
        showSettings -> Route.SETTINGS
        state is State.Idle -> Route.HOME
        state is State.Discovering || state is State.Connecting -> Route.JOIN
        state is State.Hosting -> Route.HOST
        else -> Route.CLIENT
    }
    if (route != Route.HOST && playerExpanded && !showSettings) playerExpanded = false

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = route,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(150)) },
            label = "route",
        ) { target ->
            when (target) {
                Route.HOME -> HomeScreen(
                    deviceName = deviceName,
                    onHost = { gate.run(vm::startHosting) },
                    onJoin = { gate.run(vm::startDiscovery) },
                    onSettings = { showSettings = true },
                )
                Route.JOIN -> JoinScreen(state, onJoin = vm::join, onBack = vm::stopDiscovery)
                Route.HOST -> HostScreen(
                    vm = vm,
                    onOpenPlayer = { playerExpanded = true },
                    onSettings = { showSettings = true },
                    onLeave = { confirmLeave = true },
                    onMessage = showMessage,
                )
                Route.CLIENT -> {
                    val host = when (val s = state) {
                        is State.Joined -> s.host
                        is State.Reconnecting -> s.host
                        else -> null
                    }
                    if (host != null) {
                        ClientScreen(
                            vm = vm,
                            host = host,
                            reconnecting = state is State.Reconnecting,
                            onLeave = { confirmLeave = true },
                            onSettings = { showSettings = true },
                        )
                    }
                }
                Route.SETTINGS -> SettingsScreen(vm, onBack = { showSettings = false }, onMessage = showMessage)
            }
        }

        AnimatedVisibility(
            visible = route == Route.HOST && playerExpanded,
            enter = slideInVertically(tween(300)) { it } + fadeIn(tween(300)),
            exit = slideOutVertically(tween(250)) { it } + fadeOut(tween(250)),
        ) {
            HostPlayerScreen(vm, onCollapse = { playerExpanded = false })
        }

        SnackbarHost(
            snackbar,
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = if (route == Route.HOST && !playerExpanded) 72.dp else 0.dp),
        )
    }

    if (confirmLeave) {
        val isHost = state is State.Hosting
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(if (isHost) R.string.leave_host_title else R.string.leave_client_title)) },
            text = { Text(stringResource(if (isHost) R.string.leave_host_text else R.string.leave_client_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    playerExpanded = false
                    vm.leave()
                }) { Text(stringResource(if (isHost) R.string.end else R.string.action_leave)) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
