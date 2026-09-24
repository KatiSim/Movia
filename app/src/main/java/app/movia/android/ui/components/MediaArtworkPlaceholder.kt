package app.movia.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import app.movia.android.ui.theme.MoviaHeroPlaceholderEnd
import app.movia.android.ui.theme.MoviaHeroPlaceholderStart
import app.movia.android.ui.theme.MoviaPosterPlaceholder

enum class MediaArtworkPlaceholderStyle {
    HERO,
    POSTER,
}

/**
 * Single source of truth for media artwork when no real artwork source exists.
 *
 * POSTER is deliberately a flat neutral block: no logo, icon, text, badge, shimmer,
 * gradient, or demo artwork. HERO may use the approved restrained dark gradient.
 * Callers own shape/border/aspect-ratio and may overlay real controls (for example Play).
 */
@Composable
fun MediaArtworkPlaceholder(
    modifier: Modifier = Modifier,
    style: MediaArtworkPlaceholderStyle = MediaArtworkPlaceholderStyle.POSTER,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val backgroundModifier = when (style) {
        MediaArtworkPlaceholderStyle.HERO -> Modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    MoviaHeroPlaceholderStart,
                    MoviaHeroPlaceholderEnd,
                ),
            ),
        )
        MediaArtworkPlaceholderStyle.POSTER -> Modifier.background(MoviaPosterPlaceholder)
    }

    Box(
        modifier = modifier.then(backgroundModifier),
        content = content,
    )
}
