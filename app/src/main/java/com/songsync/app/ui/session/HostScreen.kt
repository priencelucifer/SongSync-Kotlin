package com.songsync.app.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songsync.app.R
import com.songsync.app.data.model.TrackSource
import com.songsync.app.session.NowPlaying
import com.songsync.app.session.PeerState
import com.songsync.app.session.PeerUi
import com.songsync.app.ui.AppViewModel
import com.songsync.app.ui.components.TrackArtwork
import com.songsync.app.ui.components.TrackRow
import com.songsync.app.ui.components.artistOrUnknown
import com.songsync.app.ui.components.linkLabel
import com.songsync.app.ui.components.MicRun
import com.songsync.app.ui.components.rememberCalibrationStarter
import com.songsync.app.ui.components.rememberPlaybackPosition
import com.songsync.app.ui.player.PlayerContent
import com.songsync.app.ui.theme.SyncColors
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostScreen(
    vm: AppViewModel,
    onOpenPlayer: () -> Unit,
    onSettings: () -> Unit,
    onLeave: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val search by vm.search.collectAsStateWithLifecycle()
    val source by vm.searchSource.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val peers by vm.peers.collectAsStateWithLifecycle()
    val locked by vm.groupLocked.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val resources = LocalResources.current

    val startCalibration = rememberCalibrationStarter(vm) {
        onMessage(resources.getString(R.string.calibration_mic_denied))
    }
    val startSyncCheck = rememberCalibrationStarter(vm, MicRun.CHECK) {
        onMessage(resources.getString(R.string.calibration_mic_denied))
    }
    val startSyncReport = rememberCalibrationStarter(vm, MicRun.REPORT) {
        onMessage(resources.getString(R.string.calibration_mic_denied))
    }

    // After process death the text field is restored but the ViewModel is new.
    LaunchedEffect(Unit) { vm.onQueryChange(query) }

    fun dismissKeyboard() {
        keyboard?.hide()
        focus.clearFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.host_title)) },
                actions = {
                    IconButton(onClick = { showDevices = true }) {
                        BadgedBox(badge = { if (peers.size > 1) Badge { Text("${peers.size - 1}") } }) {
                            Icon(painterResource(R.drawable.ic_group), contentDescription = stringResource(R.string.devices_title))
                        }
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.menu_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_sync_test)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.playSyncTest()
                                    onOpenPlayer()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.calibrate_button)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    startCalibration()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_check_button)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    startSyncCheck()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_report_button)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_graphic_eq), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    startSyncReport()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_lock_group)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
                                trailingIcon = { Checkbox(checked = locked, onCheckedChange = null) },
                                onClick = { vm.setGroupLocked(!locked) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onSettings()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.menu_end_group)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_logout), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onLeave()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            MiniPlayer(
                nowPlaying = nowPlaying,
                position = vm::positionMs,
                duration = vm::durationMs,
                onToggle = { vm.setPlaying(!nowPlaying.playing) },
                onClick = onOpenPlayer,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    vm.onQueryChange(it)
                },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            vm.onQueryChange("")
                        }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.search_clear))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { dismissKeyboard() }),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = source == TrackSource.JIOSAAVN,
                    onClick = { vm.setSearchSource(TrackSource.JIOSAAVN) },
                    label = { Text(stringResource(R.string.source_jiosaavn)) },
                )
                FilterChip(
                    selected = source == TrackSource.YOUTUBE,
                    onClick = { vm.setSearchSource(TrackSource.YOUTUBE) },
                    label = { Text(stringResource(R.string.source_youtube)) },
                )
            }
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (search.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            when {
                query.isBlank() -> Hint(stringResource(R.string.search_empty))
                search.results.isEmpty() && search.failed -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.search_failed), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = vm::retrySearch) { Text(stringResource(R.string.retry)) }
                }
                search.results.isEmpty() && !search.loading -> Hint(stringResource(R.string.search_no_results))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(search.results, key = { it.key }, contentType = { "track" }) { track ->
                        TrackRow(
                            track = track,
                            onClick = {
                                dismissKeyboard()
                                vm.playNow(track)
                            },
                        ) {
                            IconButton(onClick = {
                                vm.enqueue(track)
                                onMessage(resources.getString(R.string.added_to_queue, track.title))
                            }) {
                                Icon(painterResource(R.drawable.ic_queue_add), contentDescription = stringResource(R.string.add_to_queue))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDevices) {
        DevicesSheet(peers, locked, onLockChange = vm::setGroupLocked, onDismiss = { showDevices = false })
    }
}

/** The host's expanded player, slid over the search screen. */
@Composable
fun HostPlayerScreen(vm: AppViewModel, onCollapse: () -> Unit, onMessage: (String) -> Unit) {
    BackHandler(onBack = onCollapse)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().windowInsetsPadding(WindowInsets.navigationBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onCollapse) {
                    Icon(painterResource(R.drawable.ic_arrow_down), contentDescription = stringResource(R.string.player_collapse))
                }
                Text(
                    stringResource(R.string.player_now_playing),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(48.dp))
            }
            PlayerContent(vm, isHost = true, hostName = "", onMessage = onMessage)
        }
    }
}

@Composable
private fun MiniPlayer(
    nowPlaying: NowPlaying,
    position: () -> Long,
    duration: () -> Long,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val track = nowPlaying.track ?: nowPlaying.loading ?: return
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
            MiniProgress(nowPlaying.playing, position, duration)
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackArtwork(track.artworkUrl, 44.dp, corner = 6.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (nowPlaying.track == null) stringResource(R.string.player_loading, track.title) else artistOrUnknown(track),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (nowPlaying.track != null) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            painterResource(if (nowPlaying.playing) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = stringResource(if (nowPlaying.playing) R.string.action_pause else R.string.action_play),
                        )
                    }
                }
            }
        }
    }
}

/** Isolated so only this thin bar recomposes as the song plays. */
@Composable
private fun MiniProgress(playing: Boolean, position: () -> Long, duration: () -> Long) {
    val current = rememberPlaybackPosition(playing, position)
    val total = duration().coerceAtLeast(1)
    LinearProgressIndicator(
        progress = { (current.toFloat() / total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(2.dp),
        drawStopIndicator = {},
    )
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesSheet(peers: List<PeerUi>, locked: Boolean, onLockChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.devices_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        peers.forEach { peer -> PeerRow(peer) }
        if (peers.size <= 1) {
            Text(
                stringResource(R.string.devices_alone),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.menu_lock_group)) },
            supportingContent = { Text(stringResource(R.string.devices_lock_hint)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_lock), contentDescription = null) },
            trailingContent = { Switch(checked = locked, onCheckedChange = onLockChange) },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PeerRow(peer: PeerUi) {
    val (stateText, color) = when (peer.state) {
        PeerState.READY -> stringResource(R.string.peer_ready) to SyncColors.good
        PeerState.LOADING -> stringResource(R.string.peer_loading) to SyncColors.warn
        PeerState.FAILED -> stringResource(R.string.peer_failed) to SyncColors.bad
        PeerState.ON_HOLD -> stringResource(R.string.peer_hold) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val details = listOfNotNull(
        stateText,
        peer.rttMs?.let { stringResource(R.string.peer_rtt, it) },
        peer.linkQuality?.let { linkLabel(it) },
        if (peer.differentFile) stringResource(R.string.peer_different_file) else null,
        if (peer.bluetoothOutput) stringResource(R.string.peer_bluetooth_output) else null,
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(if (peer.isSelf) stringResource(R.string.devices_this_phone, peer.name) else peer.name) },
        supportingContent = { Text(details) },
        leadingContent = {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color),
            )
        },
        trailingContent = peer.syncErrorMs?.let { error ->
            {
                Text(
                    stringResource(R.string.sync_error_ms, abs(error)),
                    color = when {
                        abs(error) <= 10 -> SyncColors.good
                        abs(error) <= 30 -> SyncColors.warn
                        else -> SyncColors.bad
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}
