package io.github.sasakiy84.podcast

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
internal fun PodcastTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit): Unit {
    val accent = Color(0xFF007E9E)
    val playerColors = darkColorScheme(onSurface = Color(0xFFF4F5F6), onSurfaceVariant = Color(0xFF979CA3), outlineVariant = Color(0xFF34383C), primary = Color(0xFF55CEE9), onPrimary = Color(0xFF003640), background = Color(0xFF08090A), surface = Color(0xFF08090A), surfaceContainer = Color(0xFF1A1C1F), surfaceContainerLow = Color(0xFF111316), secondaryContainer = Color(0xFF17343D), onSecondaryContainer = Color(0xFFC9EFF7))
    val colors = if (dark) playerColors else lightColorScheme(onSurface = Color(0xFF1B1E22), onSurfaceVariant = Color(0xFF666D75), outlineVariant = Color(0xFFDDE1E5), primary = accent, onPrimary = Color.White, background = Color.White, surface = Color.White, surfaceContainer = Color(0xFFF0F3F6), surfaceContainerLow = Color(0xFFF6F8FA), secondaryContainer = Color(0xFFE0F4F8), onSecondaryContainer = Color(0xFF164957))
    MaterialTheme(colorScheme = colors, content = content)
}
