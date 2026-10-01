package app.movia.android.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic spacing scale. Base unit = 4dp.
 */
object MoviaSpacing {
    val xxs = 4.dp
    val xs = 8.dp
    val sm = 12.dp
    val md = 16.dp
    val screen = 20.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 40.dp
    val huge = 48.dp
}

/**
 * Shared radii. Avoid one-off radii in screen code.
 */
object MoviaRadius {
    val small = 8.dp
    val medium = 12.dp
    val card = 16.dp
    val large = 20.dp
    val sheet = 24.dp
    val pill = 1000.dp

    // Compatibility aliases for existing components.
    val chip = medium
    val control = 18.dp
    val panel = large
    val hero = large
    val nav = 28.dp
}

/**
 * Semantic type scale. Compose still uses the platform/system font.
 */
object MoviaType {
    val displayLarge = 24.sp
    val titleLarge = 22.sp
    val titleMedium = 18.sp
    val bodyLarge = 16.sp
    val bodyMedium = 14.sp
    val bodySmall = 12.sp
    val labelLarge = 14.sp
    val labelMedium = 12.sp
    val caption = 11.sp

    // Compatibility aliases.
    val hero = displayLarge
    val screen = titleLarge
    val large = titleLarge
    val section = titleMedium
    val card = bodySmall
    val body = bodyMedium
    val meta = labelMedium
    val micro = caption
    val nav = caption
}

val MoviaMinimumTouch = 48.dp
