package com.songsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.songsync.app.ui.AppRoot
import com.songsync.app.ui.AppViewModel
import com.songsync.app.ui.theme.SongSyncTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SongSyncTheme { AppRoot(viewModel) }
        }
    }
}
