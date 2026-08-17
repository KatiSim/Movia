package app.viora.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VioraDarkColors = darkColorScheme(
    background = Color(0xFF0C0D0F),
    surface = Color(0xFF14161A),
    surfaceVariant = Color(0xFF1B1E23),
    primary = Color(0xFFF4B64A),
    onBackground = Color(0xFFF5F6F7),
    onSurface = Color(0xFFF5F6F7),
    onSurfaceVariant = Color(0xFFA7ABB2),
)

@Composable
fun VioraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = VioraDarkColors,
        content = content,
    )
}
