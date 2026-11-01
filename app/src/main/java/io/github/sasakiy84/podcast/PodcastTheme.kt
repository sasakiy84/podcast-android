package io.github.sasakiy84.podcast

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource

@Composable
internal fun PodcastTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
): Unit {
    // Share the chosen C05 inks with the launcher icon, including the forced-dark player.
    val copper = colorResource(R.color.galena_copper)
    val cream = colorResource(R.color.galena_background)
    val paleCopper = colorResource(R.color.galena_facet)
    val darkBackground = colorResource(R.color.galena_dark_background)
    val colors =
        if (dark)
            darkColorScheme(
                primary = paleCopper,
                onPrimary = Color(0xFF3B1C0E),
                primaryContainer = Color(0xFF63331F),
                onPrimaryContainer = Color(0xFFFFDBC5),
                inversePrimary = copper,
                secondary = Color(0xFFDFC1A8),
                onSecondary = Color(0xFF3B281C),
                secondaryContainer = Color(0xFF4A3426),
                onSecondaryContainer = Color(0xFFF6DDC8),
                tertiary = Color(0xFFE7BA96),
                onTertiary = Color(0xFF422B19),
                tertiaryContainer = Color(0xFF5D4028),
                onTertiaryContainer = Color(0xFFFFDCC1),
                background = darkBackground,
                onBackground = cream,
                surface = darkBackground,
                onSurface = cream,
                surfaceVariant = Color(0xFF443228),
                onSurfaceVariant = Color(0xFFCEB9A8),
                surfaceTint = paleCopper,
                inverseSurface = cream,
                inverseOnSurface = Color(0xFF30231D),
                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),
                outline = Color(0xFFA58B77),
                outlineVariant = Color(0xFF564235),
                scrim = Color.Black,
                surfaceBright = Color(0xFF46352B),
                surfaceDim = darkBackground,
                surfaceContainerLowest = Color(0xFF120C09),
                surfaceContainerLow = Color(0xFF211711),
                surfaceContainer = Color(0xFF2C2019),
                surfaceContainerHigh = Color(0xFF38291F),
                surfaceContainerHighest = Color(0xFF443228),
            )
        else
            lightColorScheme(
                primary = copper,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFF4D8C2),
                onPrimaryContainer = Color(0xFF4A2415),
                inversePrimary = paleCopper,
                secondary = Color(0xFF7D5846),
                onSecondary = Color.White,
                secondaryContainer = Color(0xFFF2DCCB),
                onSecondaryContainer = Color(0xFF4D3024),
                tertiary = Color(0xFF8E5B3B),
                onTertiary = Color.White,
                tertiaryContainer = Color(0xFFF5DEC9),
                onTertiaryContainer = Color(0xFF3A2415),
                background = cream,
                onBackground = Color(0xFF30231D),
                surface = cream,
                onSurface = Color(0xFF30231D),
                surfaceVariant = Color(0xFFEAD4C1),
                onSurfaceVariant = Color(0xFF6A5143),
                surfaceTint = copper,
                inverseSurface = Color(0xFF38291F),
                inverseOnSurface = cream,
                error = Color(0xFFB3261E),
                onError = Color.White,
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),
                outline = Color(0xFF907568),
                outlineVariant = Color(0xFFD7BEAA),
                scrim = Color.Black,
                surfaceBright = Color(0xFFFFF8F2),
                surfaceDim = Color(0xFFE7D5C5),
                surfaceContainerLowest = Color(0xFFFFFBF7),
                surfaceContainerLow = Color(0xFFFFF6ED),
                surfaceContainer = Color(0xFFF8E8DA),
                surfaceContainerHigh = Color(0xFFF7E6D7),
                surfaceContainerHighest = Color(0xFFF6E5D5),
            )
    MaterialTheme(colorScheme = colors, content = content)
}
