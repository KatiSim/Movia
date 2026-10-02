package app.movia.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Visual state for primary playback actions. Values are layer-only; layout stays unchanged. */
class MoviaPlaybackActionMotion internal constructor(private val state: androidx.compose.runtime.FloatState) {
    val surfaceScale: Float get() = state.floatValue
}

class MoviaCompactActionMotion internal constructor(private val state: androidx.compose.runtime.FloatState) {
    val scale: Float get() = state.floatValue
}

/** Visual state for settings gear feedback. */
class MoviaGearMotion internal constructor(private val scaleState: androidx.compose.runtime.FloatState, private val rotationState: androidx.compose.runtime.FloatState) {
    val scale: Float get() = scaleState.floatValue
    val rotationZ: Float get() = rotationState.floatValue
}

/**
 * Explicit action trigger. Motion no longer depends on Material InteractionSource or pointer
 * dispatch: if the click action itself works, this trigger runs first on the same click path.
 */
class MoviaActionTriggerState internal constructor() {
    var token by mutableIntStateOf(0)
        private set

    internal fun trigger() {
        token += 1
    }
}

@Composable
fun rememberMoviaActionTriggerState(): MoviaActionTriggerState =
    remember { MoviaActionTriggerState() }

/**
 * Starts the local motion synchronously from the real onClick, then lets the source composable
 * stay on-screen for [delayMs] before navigation/playback changes the screen.
 */
@Composable
fun rememberMoviaAnimatedAction(
    triggerState: MoviaActionTriggerState,
    delayMs: Long,
    onClick: () -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val latestOnClick by rememberUpdatedState(onClick)
    var pending by remember { mutableStateOf(false) }

    return remember(triggerState, delayMs, scope) {
        {
            if (!pending) {
                triggerState.trigger()
                pending = true
                scope.launch {
                    try {
                        if (delayMs > 0) delay(delayMs)
                        latestOnClick()
                    } finally {
                        pending = false
                    }
                }
            }
        }
    }
}

/**
 * Primary playback tap impulse, guaranteed from the click path.
 * The whole button reacts as one object; the play glyph never scales/translates independently:
 * 0..70 ms: 1 -> .96; 70..145 ms: .96 -> 1.03; 145..220 ms: settle to 1.
 *
 * Manual frame-clock timing restores the original app-owned interaction feedback.
 */
@Composable
fun rememberMoviaPlaybackActionMotion(
    triggerState: MoviaActionTriggerState,
): MoviaPlaybackActionMotion {
    val surfaceState = remember { mutableFloatStateOf(1f) }
    var surfaceScale by surfaceState
    val token = triggerState.token

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        animateMoviaActionKeyframes(
            listOf(
                ActionKeyframe(0, 1f),
                ActionKeyframe(70, 0.96f),
                ActionKeyframe(145, 1.03f),
                ActionKeyframe(220, 1f),
            ),
        ) { surfaceScale = it }
    }

    return remember { MoviaPlaybackActionMotion(surfaceState) }
}

/**
 * Transient visual confirmation for system controls. The visible 1dp border moves from
 * neutral -> Movia amber -> neutral without changing the control/touch geometry.
 */
@Composable
fun rememberMoviaSystemFeedbackAlpha(
    triggerState: MoviaActionTriggerState,
): Float {
    var alpha by remember { mutableFloatStateOf(0f) }
    val token = triggerState.token

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        animateMoviaActionKeyframes(
            listOf(
                ActionKeyframe(0, 0f),
                ActionKeyframe(35, 1f),
                ActionKeyframe(150, 1f),
                ActionKeyframe(260, 0f),
            ),
        ) { alpha = it }
    }
    return alpha
}

/**
 * Unified icon tap color feedback. The glyph itself flashes Movia Amber for exactly 500 ms.
 * This replaces transient amber borders on icon controls.
 */
@Composable
fun rememberMoviaIconTapAlpha(
    triggerState: MoviaActionTriggerState,
): Float {
    var alpha by remember { mutableFloatStateOf(0f) }
    val token = triggerState.token

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        animateMoviaActionKeyframes(
            listOf(
                ActionKeyframe(0, 0f),
                ActionKeyframe(60, 1f),
                ActionKeyframe(250, 1f),
                ActionKeyframe(500, 0f),
            ),
        ) { alpha = it }
    }
    return alpha
}

/**
 * Back-arrow manipulation: only the arrow glyph moves; the 48dp outer control stays fixed.
 * 1 -> 1.12 -> .94 -> 1 over 500 ms.
 */
@Composable
fun rememberMoviaBackIconScale(
    triggerState: MoviaActionTriggerState,
): Float {
    val scaleState = remember { mutableFloatStateOf(1f) }
    var scale by scaleState
    val token = triggerState.token

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        animateMoviaActionKeyframes(
            listOf(
                ActionKeyframe(0, 1f),
                ActionKeyframe(125, 1.12f),
                ActionKeyframe(290, 0.94f),
                ActionKeyframe(500, 1f),
            ),
        ) { scale = it }
    }
    return scale
}

/**
 * Compact whole-control tap for secondary actions such as «Выбрать серию».
 * Deliberately quieter than the primary playback CTA: 1 -> .97 -> 1.015 -> 1 in 180 ms.
 */
@Composable
fun rememberMoviaCompactActionMotion(
    triggerState: MoviaActionTriggerState,
): MoviaCompactActionMotion {
    val scaleState = remember { mutableFloatStateOf(1f) }
    var scale by scaleState
    val token = triggerState.token

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        animateMoviaActionKeyframes(
            listOf(
                ActionKeyframe(0, 1f),
                ActionKeyframe(55, 0.97f),
                ActionKeyframe(115, 1.015f),
                ActionKeyframe(180, 1f),
            ),
        ) { scale = it }
    }
    return remember { MoviaCompactActionMotion(scaleState) }
}

/**
 * Short amber neon flash for navigational affordances. This is an app-owned frame-clock
 * animation remains visible independently of Android's global Animator duration scale.
 */
@Composable
fun rememberMoviaNeonFeedbackAlpha(
    triggerState: MoviaActionTriggerState,
    durationMs: Int = 420,
): Float {
    var alpha by remember { mutableFloatStateOf(0f) }
    val token = triggerState.token
    val duration = durationMs.coerceAtLeast(120)
    val rise = (duration * 0.12f).toInt().coerceAtLeast(24)
    val hold = (duration * 0.36f).toInt().coerceAtLeast(rise + 1)

    LaunchedEffect(token, duration) {
        if (token == 0) return@LaunchedEffect
        animateMoviaActionKeyframes(
            listOf(
                ActionKeyframe(0, 0f),
                ActionKeyframe(rise, 1f),
                ActionKeyframe(hold, 0.92f),
                ActionKeyframe(duration, 0f),
            ),
        ) { alpha = it }
    }
    return alpha
}

/**
 * Settings mechanical engage, guaranteed from the click path:
 * rotation 0 -> -8 -> +66 -> +60deg; scale 1 -> .92 -> 1.04 -> 1 over 500 ms.
 * Each subsequent click advances another 60deg instead of snapping the previous final angle.
 */
@Composable
fun rememberMoviaGearMotion(
    triggerState: MoviaActionTriggerState,
): MoviaGearMotion {
    val scaleState = remember { mutableFloatStateOf(1f) }
    var scale by scaleState
    val rotationState = remember { mutableFloatStateOf(0f) }
    var rotation by rotationState
    val token = triggerState.token

    LaunchedEffect(token) {
        if (token == 0) return@LaunchedEffect
        val baseRotation = rotation
        coroutineScope {
            launch {
                animateMoviaActionKeyframes(
                    listOf(
                        ActionKeyframe(0, 1f),
                        ActionKeyframe(98, 0.92f),
                        ActionKeyframe(340, 1.04f),
                        ActionKeyframe(500, 1f),
                    ),
                ) { scale = it }
            }
            launch {
                animateMoviaActionKeyframes(
                    listOf(
                        ActionKeyframe(0, baseRotation),
                        ActionKeyframe(98, baseRotation - 8f),
                        ActionKeyframe(340, baseRotation + 66f),
                        ActionKeyframe(500, baseRotation + 60f),
                    ),
                ) { rotation = it }
            }
        }
    }

    return remember { MoviaGearMotion(scaleState, rotationState) }
}

private data class ActionKeyframe(
    val timeMs: Int,
    val value: Float,
)

private suspend fun animateMoviaActionKeyframes(
    keyframes: List<ActionKeyframe>,
    onValue: (Float) -> Unit,
) {
    if (keyframes.size < 2) {
        keyframes.firstOrNull()?.let { onValue(it.value) }
        return
    }

    val durationMs = keyframes.last().timeMs.coerceAtLeast(1)
    val startNanos = withFrameNanos { it }
    onValue(keyframes.first().value)

    while (true) {
        val frameNanos = withFrameNanos { it }
        val elapsedMs = ((frameNanos - startNanos) / 1_000_000f)
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
