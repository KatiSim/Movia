package app.movia.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MoviaDarkColors = darkColorScheme(
    primary = MoviaBrandAmber,
    onPrimary = MoviaOnBrandAmber,
    primaryContainer = MoviaBrandAmber,
    onPrimaryContainer = MoviaOnBrandAmber,
    // Secondary is deliberately the semantic accent-text role. In dark mode it
    // matches accent-base; in light mode it switches to the darker cognac token.
    secondary = MoviaDarkAccentText,
    onSecondary = MoviaOnBrandAmber,
    secondaryContainer = MoviaDarkSurfaceElevated,
    onSecondaryContainer = MoviaDarkTextPrimary,
    tertiary = MoviaDarkTextMuted,
    onTertiary = MoviaDarkTextPrimary,
    tertiaryContainer = MoviaDarkSurfaceElevated,
    onTertiaryContainer = MoviaDarkTextPrimary,
    error = MoviaDarkAccentText,
    onError = MoviaOnBrandAmber,
    errorContainer = MoviaDarkSurfaceElevated,
    onErrorContainer = MoviaDarkTextPrimary,
    background = MoviaDarkSurfaceCanvas,
    onBackground = MoviaDarkTextPrimary,
    surface = MoviaDarkSurfaceCard,
    onSurface = MoviaDarkTextPrimary,
    surfaceVariant = MoviaDarkSurfaceElevated,
    onSurfaceVariant = MoviaDarkTextSecondary,
    outline = MoviaDarkTextMuted,
    outlineVariant = MoviaBorderSubtle,
    inverseSurface = MoviaDarkTextPrimary,
    inverseOnSurface = MoviaDarkSurfaceCard,
    inversePrimary = MoviaBrandAmber,
    surfaceDim = MoviaDarkSurfaceCanvas,
    surfaceBright = MoviaDarkSurfaceElevated,
    surfaceContainerLowest = MoviaDarkSurfaceCanvas,
    surfaceContainerLow = MoviaDarkSurfaceCard,
    surfaceContainer = MoviaDarkSurfaceCard,
    surfaceContainerHigh = MoviaDarkSurfaceElevated,
    surfaceContainerHighest = MoviaDarkSurfaceElevated,
    surfaceTint = MoviaBrandAmber,
    scrim = MoviaDarkSurfaceCanvas,
)

private val MoviaLightColors = lightColorScheme(
    primary = MoviaBrandAmber,
    onPrimary = MoviaOnBrandAmber,
    primaryContainer = MoviaBrandAmber,
    onPrimaryContainer = MoviaOnBrandAmber,
    secondary = MoviaLightAccentText,
    onSecondary = MoviaOnBrandAmber,
    secondaryContainer = MoviaLightSurfaceElevated,
    onSecondaryContainer = MoviaLightTextPrimary,
    tertiary = MoviaLightTextMuted,
    onTertiary = MoviaLightTextPrimary,
    tertiaryContainer = MoviaLightSurfaceElevated,
    onTertiaryContainer = MoviaLightTextPrimary,
    error = MoviaLightAccentText,
    onError = MoviaOnBrandAmber,
    errorContainer = MoviaLightSurfaceElevated,
    onErrorContainer = MoviaLightTextPrimary,
    background = MoviaLightSurfaceCanvas,
    onBackground = MoviaLightTextPrimary,
    surface = MoviaLightSurfaceCard,
    onSurface = MoviaLightTextPrimary,
    surfaceVariant = MoviaLightSurfaceElevated,
    onSurfaceVariant = MoviaLightTextSecondary,
    outline = MoviaLightTextMuted,
    outlineVariant = MoviaBorderSubtle,
    inverseSurface = MoviaLightTextPrimary,
    inverseOnSurface = MoviaLightSurfaceCard,
    inversePrimary = MoviaBrandAmber,
    surfaceDim = MoviaLightSurfaceElevated,
    surfaceBright = MoviaLightSurfaceCard,
    surfaceContainerLowest = MoviaLightSurfaceCard,
    surfaceContainerLow = MoviaLightSurfaceCard,
    surfaceContainer = MoviaLightSurfaceCard,
    surfaceContainerHigh = MoviaLightSurfaceElevated,
    surfaceContainerHighest = MoviaLightSurfaceElevated,
    surfaceTint = MoviaBrandAmber,
    scrim = MoviaLightTextPrimary,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun MoviaTheme(
    themeMode: String = "DARK",
    highContrast: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Movia v1 is intentionally dark-only. Keep the preference parameters and the dormant
    // light token palette so a future light mode can be re-enabled without rewriting UI
    // components; runtime rendering always resolves to Cinematic Gold dark semantics.
    @Suppress("UNUSED_VARIABLE")
    val preserveFutureThemeContract = themeMode to highContrast
    val colors = MoviaDarkColors

    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                // Dark canvas => light system icons in both status and navigation bars.
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
