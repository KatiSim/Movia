package app.viora.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VioraDarkColors = darkColorScheme(
    background = Color(0xFF0C0D0F),
    surface = Color(0xFF14161A),
    surfaceVariant = Color(0xFF1B1E23),
    primary = Color(0xFFF4B343),
    onPrimary = Color(0xFF241800),
    onBackground = Color(0xFFF5F6F7),
    onSurface = Color(0xFFF5F6F7),
    onSurfaceVariant = Color(0xFFC5C5C5),
)

private val VioraDarkHighContrast = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF0B0C0E),
    surfaceVariant = Color(0xFF17191D),
    primary = Color(0xFFFFC75A),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color.White,
)

private val VioraLightColors = lightColorScheme(
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAED),
    primary = Color(0xFF8A5700),
    onPrimary = Color.White,
    onBackground = Color(0xFF141519),
    onSurface = Color(0xFF141519),
    onSurfaceVariant = Color(0xFF555A63),
)

private val VioraLightHighContrast = lightColorScheme(
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Color(0xFFE0E0E0),
    primary = Color(0xFF6A3F00),
    onPrimary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    onSurfaceVariant = Color.Black,
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
    MaterialTheme(
        colorScheme = colors,
        content = content,
    )
}
