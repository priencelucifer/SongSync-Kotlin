package com.songsync.app.ui.join

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.songsync.app.R
import com.songsync.app.net.DiscoveredHost
import com.songsync.app.session.SessionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JoinScreen(state: SessionManager.State, onJoin: (DiscoveredHost) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.join_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is SessionManager.State.Connecting -> Waiting(stringResource(R.string.join_connecting, state.host.name), hint = null)
                is SessionManager.State.Discovering ->
                    if (state.hosts.isEmpty()) {
                        Waiting(stringResource(R.string.join_searching), hint = stringResource(R.string.join_hint))
                    } else {
                        Column {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            LazyColumn {
                                items(state.hosts, key = { it.endpointId }) { host ->
                                    ListItem(
                                        headlineContent = { Text(host.name) },
                                        supportingContent = { Text(stringResource(R.string.join_tap)) },
                                        leadingContent = {
                                            Box(
                                                Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(painterResource(R.drawable.ic_host), contentDescription = null)
                                            }
                                        },
                                        modifier = Modifier.clickable { onJoin(host) },
                                    )
                                }
                            }
                        }
                    }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Waiting(text: String, hint: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}
