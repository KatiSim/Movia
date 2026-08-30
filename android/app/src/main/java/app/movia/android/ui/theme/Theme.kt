package app.movia.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.util.Locale

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
    outline = MoviaBorderSubtle,
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
    outline = MoviaBorderSubtle,
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
    val isDark = when (themeMode.uppercase(Locale.ROOT)) {
        "SYSTEM" -> isSystemInDarkTheme()
        else -> true
    }
    val colors = if (isDark) MoviaDarkColors else MoviaLightColors

    val view = LocalView.current
    DisposableEffect(view, isDark) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
        onDispose { }
    }

    MaterialTheme(colorScheme = colors, content = content)
}
