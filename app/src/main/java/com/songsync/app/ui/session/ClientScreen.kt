package com.songsync.app.ui.session

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.songsync.app.R
import com.songsync.app.net.DiscoveredHost
import com.songsync.app.ui.AppViewModel
import com.songsync.app.ui.player.PlayerContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(vm: AppViewModel, host: DiscoveredHost, reconnecting: Boolean, onLeave: () -> Unit, onSettings: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(if (reconnecting) R.string.client_reconnecting else R.string.client_title, host.name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = stringResource(R.string.settings))
                    }
                    IconButton(onClick = onLeave) {
                        Icon(painterResource(R.drawable.ic_logout), contentDescription = stringResource(R.string.action_leave))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (reconnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            PlayerContent(vm, isHost = false, hostName = host.name)
        }
    }
}
