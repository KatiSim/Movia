package app.movia.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.currentCoroutineContext
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

enum class MoviaIconMotionKind { HEART, BELL, INFO }

class MoviaIconMotionState internal constructor(
    private val scaleState: androidx.compose.runtime.FloatState,
    private val rotationState: androidx.compose.runtime.FloatState,
    val transformOrigin: TransformOrigin,
) {
    val scale: Float get() = scaleState.floatValue
    val rotationZ: Float get() = rotationState.floatValue
}

/**
 * App-owned micro-interactions for Movia's semantic favorite and waiting-release icons.
 *
 * Important: timing is advanced from the frame clock manually rather than through
 * Compose animation specs. This keeps these short 150–280 ms feedback motions
 * responsive to Android's global Animator duration scale while still only
 * transforming the glyph layer (no layout animation).
 */
@Composable
fun rememberMoviaIconMotion(
    kind: MoviaIconMotionKind,
    active: Boolean,
    trigger: Int = 0,
    animateStateChanges: Boolean = true,
): MoviaIconMotionState {
    val scaleState = remember { mutableFloatStateOf(1f) }
    var scale by scaleState
    val rotationState = remember { mutableFloatStateOf(0f) }
    var rotation by rotationState
    var initialized by remember { mutableStateOf(false) }
    var previousActive by remember { mutableStateOf(active) }
    var previousTrigger by remember { mutableIntStateOf(trigger) }

    LaunchedEffect(active, trigger, kind, animateStateChanges) {
        if (!initialized) {
            initialized = true
            previousActive = active
            previousTrigger = trigger
            scale = 1f
            rotation = 0f
            return@LaunchedEffect
        }

        val stateChanged = animateStateChanges && active != previousActive
        val explicitTrigger = trigger != previousTrigger
        previousActive = active
        previousTrigger = trigger

        if (!stateChanged && !explicitTrigger) return@LaunchedEffect
        val activating = if (stateChanged) active else true

        coroutineScope {
            launch {
                val frames = if (activating) {
                    when (kind) {
                        MoviaIconMotionKind.HEART -> listOf(
                            MotionKeyframe(0, 1f),
                            MotionKeyframe(78, 0.84f),
                            MotionKeyframe(173, 1.50f),
                            MotionKeyframe(258, 1f),
                            MotionKeyframe(328, 0.96f),
                            MotionKeyframe(408, 1.25f),
                            MotionKeyframe(500, 1f),
                        )
                        MoviaIconMotionKind.BELL -> listOf(
                            MotionKeyframe(0, 1f),
                            MotionKeyframe(90, 0.84f),
                            MotionKeyframe(210, 1.07f),
                            MotionKeyframe(350, 0.99f),
                            MotionKeyframe(500, 1f),
                        )
                        MoviaIconMotionKind.INFO -> listOf(
                            MotionKeyframe(0, 1f),
                            MotionKeyframe(115, 0.90f),
                            MotionKeyframe(283, 1.16f),
                            MotionKeyframe(395, 0.98f),
                            MotionKeyframe(500, 1f),
                        )
                    }
                } else {
                    listOf(
                        MotionKeyframe(0, 1f),
                        MotionKeyframe(233, 0.94f),
                        MotionKeyframe(500, 1f),
                    )
                }
                animateMoviaKeyframes(frames) { scale = it }
            }

            launch {
                if (kind == MoviaIconMotionKind.BELL && activating) {
                    animateMoviaKeyframes(
                        listOf(
                            MotionKeyframe(0, 0f),
                            MotionKeyframe(70, 30f),
                            MotionKeyframe(150, -27f),
                            MotionKeyframe(240, 20f),
                            MotionKeyframe(330, -13f),
                            MotionKeyframe(415, 6f),
                            MotionKeyframe(500, 0f),
                        ),
                    ) { rotation = it }
                } else {
                    rotation = 0f
                }
            }
        }
    }

    return remember(kind) { MoviaIconMotionState(
        scaleState = scaleState,
        rotationState = rotationState,
        transformOrigin = if (kind == MoviaIconMotionKind.BELL) {
            TransformOrigin(0.5f, 0.12f)
        } else {
            TransformOrigin.Center
        },
    ) }
}

private data class MotionKeyframe(
    val timeMs: Int,
    val value: Float,
)

private suspend fun animateMoviaKeyframes(
    keyframes: List<MotionKeyframe>,
    onValue: (Float) -> Unit,
) {
    if (keyframes.size < 2) {
        keyframes.firstOrNull()?.let { onValue(it.value) }
        return
    }

    val durationMs = keyframes.last().timeMs.coerceAtLeast(1)
    val durationScale = currentCoroutineContext()[MotionDurationScale]
    if (durationScale?.scaleFactor == 0f) { onValue(keyframes.first().value); return }
    val startNanos = withFrameNanos { it }
    onValue(keyframes.first().value)

    while (true) {
        if (durationScale?.scaleFactor == 0f) { onValue(keyframes.first().value); return }
        val frameNanos = withFrameNanos { it }
        val elapsedMs = ((frameNanos - startNanos) / (1_000_000f * (durationScale?.scaleFactor ?: 1f).coerceAtLeast(0.01f)))
            .coerceIn(0f, durationMs.toFloat())

        val upperIndex = keyframes.indexOfFirst { elapsedMs <= it.timeMs }
            .let { if (it == -1) keyframes.lastIndex else it }
        val lowerIndex = (upperIndex - 1).coerceAtLeast(0)
        val lower = keyframes[lowerIndex]
        val upper = keyframes[upperIndex]

        val value = if (upper.timeMs == lower.timeMs) {
            upper.value
        } else {
            val fraction = ((elapsedMs - lower.timeMs) / (upper.timeMs - lower.timeMs).toFloat())
                .coerceIn(0f, 1f)
            lower.value + (upper.value - lower.value) * fraction
        }
        onValue(value)

        if (elapsedMs >= durationMs) break
    }

    onValue(keyframes.last().value)
}
