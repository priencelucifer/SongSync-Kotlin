package com.songsync.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackColors = darkColorScheme(
    primary = Color(0xFFC7B8FF),
    onPrimary = Color(0xFF2B1A6E),
    primaryContainer = Color(0xFF4430A8),
    onPrimaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFFC9C3DC),
    secondaryContainer = Color(0xFF484459),
    tertiary = Color(0xFFFFB0C8),
    background = Color(0xFF0F0D16),
    surface = Color(0xFF0F0D16),
    surfaceContainer = Color(0xFF1C1A24),
    surfaceContainerHigh = Color(0xFF26242F),
)

/** Status colours used by the sync indicators; kept out of the M3 scheme on purpose. */
object SyncColors {
    val good = Color(0xFF7BE0A0)
    val warn = Color(0xFFFFC46B)
    val bad = Color(0xFFFF8A80)
}

/** Always dark: it is a party/music app and matches the original look. */
@Composable
fun SongSyncTheme(content: @Composable () -> Unit) {
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(LocalContext.current)
    } else {
        FallbackColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
