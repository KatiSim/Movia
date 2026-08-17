package app.viora.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VioraDarkColors = darkColorScheme(
    primary = VioraBrandAmber,
    onPrimary = VioraOnBrandAmber,
    primaryContainer = Color(0xFF5D4100),
    onPrimaryContainer = Color(0xFFFFDFA3),
    secondary = Color(0xFFD2C7B5),
    onSecondary = Color(0xFF342D22),
    secondaryContainer = Color(0xFF3B342A),
    onSecondaryContainer = Color(0xFFEFE3D1),
    tertiary = Color(0xFFC5CEC5),
    onTertiary = Color(0xFF2A322B),
    tertiaryContainer = Color(0xFF303831),
    onTertiaryContainer = Color(0xFFE1E9E1),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = VioraDarkBackground,
    onBackground = VioraDarkOnSurface,
    surface = VioraDarkSurface,
    onSurface = VioraDarkOnSurface,
    surfaceVariant = VioraDarkSurfaceContainer,
    onSurfaceVariant = VioraDarkOnSurfaceVariant,
    outline = VioraDarkOutline,
    outlineVariant = VioraDarkOutlineVariant,
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF303034),
    inversePrimary = VioraLightBrandForeground,
    surfaceDim = VioraDarkBackground,
    surfaceBright = Color(0xFF36373C),
    surfaceContainerLowest = Color(0xFF08090B),
    surfaceContainerLow = VioraDarkSurfaceContainerLow,
    surfaceContainer = VioraDarkSurfaceContainer,
    surfaceContainerHigh = VioraDarkSurfaceContainerHigh,
    surfaceContainerHighest = VioraDarkSurfaceContainerHighest,
    surfaceTint = VioraBrandAmber,
    scrim = Color.Black,
)

private val VioraDarkHighContrast = darkColorScheme(
    primary = Color(0xFFFFC75A),
    onPrimary = Color.Black,
    primaryContainer = VioraBrandAmber,
    onPrimaryContainer = VioraOnBrandAmber,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = Color.White,
    tertiary = Color.White,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF303030),
    onTertiaryContainer = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF08090B),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF17181C),
    onSurfaceVariant = Color.White,
    outline = Color.White,
    outlineVariant = Color(0xFFB8B8B8),
    surfaceContainerLow = Color(0xFF0D0E10),
    surfaceContainer = Color(0xFF17181C),
    surfaceContainerHigh = Color(0xFF242529),
    surfaceContainerHighest = Color(0xFF303136),
    surfaceTint = Color(0xFFFFC75A),
)

private val VioraLightColors = lightColorScheme(
    primary = VioraLightBrandForeground,
    onPrimary = Color.White,
    primaryContainer = VioraBrandAmber,
    onPrimaryContainer = VioraOnBrandAmber,
    secondary = Color(0xFF5F574A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE6D9),
    onSecondaryContainer = Color(0xFF211B12),
    tertiary = Color(0xFF58625A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE7DE),
    onTertiaryContainer = Color(0xFF182019),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = VioraLightBackground,
    onBackground = VioraLightOnSurface,
    surface = VioraLightSurface,
    onSurface = VioraLightOnSurface,
    surfaceVariant = VioraLightSurfaceContainer,
    onSurfaceVariant = VioraLightOnSurfaceVariant,
    outline = VioraLightOutline,
    outlineVariant = VioraLightOutlineVariant,
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
    inversePrimary = VioraBrandAmber,
    surfaceDim = Color(0xFFD8D9DE),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = VioraLightSurfaceContainerLow,
    surfaceContainer = VioraLightSurfaceContainer,
    surfaceContainerHigh = VioraLightSurfaceContainerHigh,
    surfaceContainerHighest = VioraLightSurfaceContainerHighest,
    surfaceTint = VioraLightBrandForeground,
    scrim = Color.Black,
)

private val VioraLightHighContrast = lightColorScheme(
    primary = VioraLightBrandForegroundHighContrast,
    onPrimary = Color.White,
    primaryContainer = VioraBrandAmber,
    onPrimaryContainer = VioraOnBrandAmber,
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5E5E7),
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E5E7),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFE5E5E7),
    onSurfaceVariant = Color.Black,
    outline = Color.Black,
    outlineVariant = Color(0xFF5A5A5A),
    surfaceContainerLow = Color(0xFFF8F8F9),
    surfaceContainer = Color(0xFFEFEFF1),
    surfaceContainerHigh = Color(0xFFE5E5E7),
    surfaceContainerHighest = Color(0xFFD9D9DC),
    surfaceTint = VioraLightBrandForegroundHighContrast,
)

@Composable
fun VioraTheme(
    themeMode: String = "DARK",
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "LIGHT" -> false
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    val colors = when {
        dark && highContrast -> VioraDarkHighContrast
        dark -> VioraDarkColors
        highContrast -> VioraLightHighContrast
        else -> VioraLightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
