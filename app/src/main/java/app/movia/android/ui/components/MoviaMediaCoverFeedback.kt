package app.movia.android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaSurfaceSecondary

const val MoviaMediaGlowCoreAlpha = 0.425f
const val MoviaMediaGlowMidAlpha = 0.1875f
const val MoviaMediaCoverMotionDurationMs = 220

/** Shared interaction state for every tappable media cover. */
data class MoviaMediaCoverFeedback(
    val scale: Float,
    val glowAlpha: Float,
    val onClick: () -> Unit,
)

@Composable
fun rememberMoviaMediaCoverFeedback(
    onClick: () -> Unit,
): MoviaMediaCoverFeedback {
    val trigger = rememberMoviaActionTriggerState()
    val motion = rememberMoviaPlaybackActionMotion(trigger)
    val glowAlpha = rememberMoviaNeonFeedbackAlpha(
        triggerState = trigger,
        durationMs = MoviaMediaCoverMotionDurationMs,
    )
    val animatedOnClick = rememberMoviaAnimatedAction(
        triggerState = trigger,
        delayMs = MoviaMediaCoverMotionDurationMs.toLong(),
        onClick = onClick,
    )
    return MoviaMediaCoverFeedback(
        scale = motion.surfaceScale,
        glowAlpha = glowAlpha,
        onClick = animatedOnClick,
    )
}

/**
 * Visual-only cover layer: poster geometry stays unchanged; only artwork scales and a blurred
 * amber radial glow appears behind it. Text/metadata around the cover never move.
 */
@Composable
fun MoviaMediaCoverFrame(
    feedback: MoviaMediaCoverFeedback,
    modifier: Modifier = Modifier,
    shape: Shape,
    borderColor: Color = MoviaBorderSubtle,
    backgroundColor: Color = MoviaSurfaceSecondary,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(
                    radius = 10.dp,
                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                ),
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to MoviaBrandAmber.copy(alpha = MoviaMediaGlowCoreAlpha * feedback.glowAlpha),
                        0.48f to MoviaBrandAmber.copy(alpha = MoviaMediaGlowMidAlpha * feedback.glowAlpha),
                        1.00f to MoviaBrandAmber.copy(alpha = 0.00f),
                    ),
                    center = center,
                    radius = size.maxDimension * 0.62f,
                ),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = feedback.scale
                    scaleY = feedback.scale
                }
                .clip(shape)
                .background(backgroundColor)
                .border(1.dp, borderColor, shape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}
