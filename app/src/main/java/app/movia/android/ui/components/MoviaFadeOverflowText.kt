package app.movia.android.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Applies a subtle trailing alpha mask only when bounded text actually overflows. */
@Composable
fun MoviaFadeOverflowText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = 1,
    fadeWidth: Dp = 20.dp,
    onOverflowChanged: ((Boolean) -> Unit)? = null,
) {
    MoviaFadeOverflowText(
        text = AnnotatedString(text),
        modifier = modifier,
        style = style,
        maxLines = maxLines,
        fadeWidth = fadeWidth,
        onOverflowChanged = onOverflowChanged,
    )
}

@Composable
fun MoviaFadeOverflowText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default,
    maxLines: Int = 1,
    fadeWidth: Dp = 20.dp,
    onOverflowChanged: ((Boolean) -> Unit)? = null,
) {
    var overflowed by remember(text, maxLines) { mutableStateOf(false) }
    var fadeTopPx by remember(text, maxLines) { mutableStateOf(0f) }
    var fadeBottomPx by remember(text, maxLines) { mutableStateOf(0f) }
    val fadePx = with(LocalDensity.current) { fadeWidth.toPx() }
    Text(
        text = text,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflowed && size.width > 0f) {
                    val start = (size.width - fadePx).coerceAtLeast(0f)
                    val top = fadeTopPx.coerceIn(0f, size.height)
                    val bottom = fadeBottomPx.coerceIn(top, size.height)
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.White, Color.Transparent),
                            startX = start,
                            endX = size.width,
                        ),
                        topLeft = Offset(start, top),
                        size = Size(size.width - start, bottom - top),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Clip,
        onTextLayout = { result ->
            val isOverflowed = result.didOverflowWidth || result.didOverflowHeight
            overflowed = isOverflowed
            if (result.lineCount > 0) {
                val lastVisibleLine = (result.lineCount - 1).coerceAtMost(maxLines - 1)
                fadeTopPx = result.getLineTop(lastVisibleLine)
                fadeBottomPx = result.getLineBottom(lastVisibleLine)
            }
            onOverflowChanged?.invoke(isOverflowed)
        },
    )
}
