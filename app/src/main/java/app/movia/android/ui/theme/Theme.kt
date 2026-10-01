package app.movia.android.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.movia.android.R
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import java.util.Locale

private val MoviaDarkColors = darkColorScheme(
    primary = MoviaAccent,
    onPrimary = MoviaOnBrandAmber,
    primaryContainer = MoviaAccent,
    onPrimaryContainer = MoviaOnBrandAmber,
    secondary = MoviaTextSecondary,
    onSecondary = MoviaBackgroundPrimary,
    secondaryContainer = MoviaSurfaceSecondary,
    onSecondaryContainer = MoviaTextPrimary,
    tertiary = MoviaTextTertiary,
    onTertiary = MoviaTextPrimary,
    tertiaryContainer = MoviaSurfaceSecondary,
    onTertiaryContainer = MoviaTextPrimary,
    error = MoviaError,
    onError = MoviaBackgroundPrimary,
    errorContainer = MoviaSurfaceElevated,
    onErrorContainer = MoviaError,
    background = MoviaBackgroundPrimary,
    onBackground = MoviaTextPrimary,
    surface = MoviaSurfacePrimary,
    onSurface = MoviaTextPrimary,
    surfaceVariant = MoviaSurfaceSecondary,
    onSurfaceVariant = MoviaTextSecondary,
    outline = MoviaBorderSubtle,
    outlineVariant = MoviaBorderSubtle,
    inverseSurface = MoviaTextPrimary,
    inverseOnSurface = MoviaBackgroundPrimary,
    inversePrimary = MoviaAccent,
    surfaceDim = MoviaBackgroundPrimary,
    surfaceBright = MoviaSurfaceElevated,
    surfaceContainerLowest = MoviaBackgroundPrimary,
    surfaceContainerLow = MoviaSurfacePrimary,
    surfaceContainer = MoviaSurfacePrimary,
    surfaceContainerHigh = MoviaSurfaceSecondary,
    surfaceContainerHighest = MoviaSurfaceElevated,
    surfaceTint = MoviaAccent,
    scrim = MoviaOverlayDark,
)

val MoviaFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_regular, weight = FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, weight = FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, weight = FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_bold, weight = FontWeight.Bold),
)

private fun moviaTextStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
) = TextStyle(
    fontFamily = MoviaFontFamily,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

private val MoviaTypography = Typography(
    displayLarge = moviaTextStyle(24, 30, FontWeight.Bold),
    displayMedium = moviaTextStyle(24, 30, FontWeight.Bold),
    displaySmall = moviaTextStyle(22, 28, FontWeight.Bold),
    headlineLarge = moviaTextStyle(24, 30, FontWeight.Bold),
    headlineMedium = moviaTextStyle(22, 28, FontWeight.Bold),
    headlineSmall = moviaTextStyle(20, 26, FontWeight.SemiBold),
    titleLarge = moviaTextStyle(22, 28, FontWeight.Bold),
    titleMedium = moviaTextStyle(18, 24, FontWeight.SemiBold),
    titleSmall = moviaTextStyle(16, 22, FontWeight.SemiBold),
    bodyLarge = moviaTextStyle(16, 24, FontWeight.Normal),
    bodyMedium = moviaTextStyle(14, 20, FontWeight.Normal),
    bodySmall = moviaTextStyle(12, 16, FontWeight.Normal),
    labelLarge = moviaTextStyle(14, 20, FontWeight.SemiBold),
    labelMedium = moviaTextStyle(12, 16, FontWeight.Medium),
    labelSmall = moviaTextStyle(11, 14, FontWeight.Medium),
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
    // Movia uses one cinematic dark palette, with optional accessible contrast.
    val darkBars = true
    androidx.compose.runtime.SideEffect { MoviaColorPolicy.highContrast = highContrast }

    val view = LocalView.current
    DisposableEffect(view, darkBars) {
        val window = view.context.findActivity()?.window
        if (window != null) {
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }

    MaterialTheme(
        colorScheme = if (highContrast) MoviaDarkColors.copy(
            onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFE3E8F2),
            secondary = androidx.compose.ui.graphics.Color(0xFFE3E8F2),
            tertiary = androidx.compose.ui.graphics.Color(0xFFCDD5E5),
            outline = androidx.compose.ui.graphics.Color(0xFF7B8598),
            outlineVariant = androidx.compose.ui.graphics.Color(0xFF7B8598),
        ) else MoviaDarkColors,
        typography = MoviaTypography,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = MoviaFontFamily),
            content = content,
        )
    }
}
