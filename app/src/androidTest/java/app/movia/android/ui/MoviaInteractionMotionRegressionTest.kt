package app.movia.android.ui

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.MotionDurationScale
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.movia.android.ui.components.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Compose effects without an Activity or any device screen. */
@RunWith(AndroidJUnit4::class)
class MoviaInteractionMotionRegressionTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun onClear() {}
        override fun insertTopDown(index: Int, instance: Unit) {}
        override fun insertBottomUp(index: Int, instance: Unit) {}
        override fun remove(index: Int, count: Int) {}
        override fun move(from: Int, to: Int, count: Int) {}
    }
    private class Scene {
        val clock = BroadcastFrameClock()
        val disabledSystemMotion = object : MotionDurationScale { override val scaleFactor = 0f }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + clock + disabledSystemMotion)
        val recomposer = Recomposer(scope.coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        val active = mutableStateOf(false)
        var frames = 0L
        var clicks = 0
        lateinit var action: () -> Unit
        lateinit var trigger: MoviaActionTriggerState
        lateinit var gear: MoviaGearMotion
        lateinit var heart: MoviaIconMotionState
        lateinit var bell: MoviaIconMotionState
        lateinit var play: MoviaPlaybackActionMotion
        lateinit var compact: MoviaCompactActionMotion
        suspend fun start() {
            scope.launch { recomposer.runRecomposeAndApplyChanges() }
            withContext(Dispatchers.Main) {
                composition.setContent {
                    trigger = rememberMoviaActionTriggerState()
                    gear = rememberMoviaGearMotion(trigger)
                    heart = rememberMoviaIconMotion(MoviaIconMotionKind.HEART, active.value)
                    bell = rememberMoviaIconMotion(MoviaIconMotionKind.BELL, false, trigger.token, false)
                    play = rememberMoviaPlaybackActionMotion(trigger)
                    compact = rememberMoviaCompactActionMotion(trigger)
                    action = rememberMoviaAnimatedAction(trigger, 500L) { clicks++ }
                }
            }
            repeat(3) { frame() }
        }
        suspend fun frame() {
            delay(10)
            withContext(Dispatchers.Main) {
                Snapshot.sendApplyNotifications()
                frames += 16_000_000L
                clock.sendFrame(frames)
            }
            delay(5)
        }
        suspend fun close() {
            withContext(Dispatchers.Main) { composition.dispose(); recomposer.close() }
            scope.cancel()
        }
    }
    private fun scene(test: suspend Scene.() -> Unit) = runBlocking {
        val scene = Scene()
        try { scene.start(); scene.test() } finally { scene.close() }
    }
    @Test fun originalFeedbackAnimatesWhenSystemAnimatorScaleIsZero() = scene {
        withContext(Dispatchers.Main) { active.value = true; action() }
        var maxHeart = 1f
        var maxBellRotation = 0f
        var maxGearRotation = 0f
        var minPlay = 1f
        var minCompact = 1f
        repeat(42) {
            frame()
            withContext(Dispatchers.Main) {
                maxHeart = maxOf(maxHeart, heart.scale)
                maxBellRotation = maxOf(maxBellRotation, kotlin.math.abs(bell.rotationZ))
                maxGearRotation = maxOf(maxGearRotation, gear.rotationZ)
                minPlay = minOf(minPlay, play.surfaceScale)
                minCompact = minOf(minCompact, compact.scale)
            }
        }
        assertTrue("Heart never pulsed", maxHeart > 1.2f)
        assertTrue("Bell never rang", maxBellRotation > 15f)
        assertTrue("Gear never turned", maxGearRotation > 40f)
        assertTrue("Play button never reacted", minPlay < 0.99f)
        assertTrue("Secondary button never reacted", minCompact < 0.99f)
        withContext(Dispatchers.Main) {
            assertEquals(1f, heart.scale, 0.001f)
            assertEquals(0f, bell.rotationZ, 0.001f)
            assertEquals(60f, gear.rotationZ, 0.001f)
            assertEquals(1f, play.surfaceScale, 0.001f)
        }
    }
    @Test fun navigationWaitsForFeedbackAndRapidTapsDoNotDuplicateAction() = scene {
        withContext(Dispatchers.Main) { action(); action(); assertEquals(0, clicks) }
        repeat(8) { frame() }
        withContext(Dispatchers.Main) {
            assertEquals("Screen changed before feedback", 0, clicks)
            assertEquals("Rapid tap restarted action", 1, trigger.token)
        }
        delay(550)
        withContext(Dispatchers.Main) { assertEquals(1, clicks) }
        withContext(Dispatchers.Main) { action() }
        delay(550)
        withContext(Dispatchers.Main) { assertEquals("Control stayed locked after action", 2, clicks) }
    }
    @Test fun leavingCompositionCancelsPendingNavigation() = scene {
        withContext(Dispatchers.Main) { action(); composition.dispose() }
        delay(600)
        withContext(Dispatchers.Main) { assertEquals("Disposed screen navigated later", 0, clicks) }
    }
}
