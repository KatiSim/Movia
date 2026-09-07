package app.movia.android.domain.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStartupBudgetTest {
    @Test
    fun resolverAndProbeLeaveMedia3TimeInsideTenSecondTarget() {
        assertEquals(10_000L, PLAYBACK_READY_TARGET_MS)
        assertEquals(10_000L, PLAYBACK_RECOVERY_TARGET_MS)
        assertEquals(3_000L, PLAYBACK_DISCOVERY_ROUTE_MS)
        assertEquals(6_000L, PLAYBACK_RESOLVER_TOTAL_MS)
        assertTrue(PLAYBACK_DISCOVERY_ROUTE_MS * 2 <= PLAYBACK_RESOLVER_TOTAL_MS)
        assertTrue(PLAYBACK_RESOLVER_TOTAL_MS < PLAYBACK_READY_TARGET_MS)
        assertEquals(1_000L, playbackMediaProbeBudgetMs(4_000L))
        assertEquals(500L, playbackMediaProbeBudgetMs(2_500L))
        assertEquals(0L, playbackMediaProbeBudgetMs(1_500L))
    }

    @Test
    fun remainingBudgetUsesAbsoluteRequestDeadline() {
        assertEquals(10_000L, remainingPlaybackReadyBudgetMs(startedAtMs = 1_000L, nowMs = 1_000L))
        assertEquals(4_000L, remainingPlaybackReadyBudgetMs(startedAtMs = 1_000L, nowMs = 7_000L))
        assertEquals(0L, remainingPlaybackReadyBudgetMs(startedAtMs = 1_000L, nowMs = 12_500L))
    }

    @Test
    fun postReadyRecoveryBudgetIsBoundedIndependently() {
        assertEquals(10_000L, remainingPlaybackRecoveryBudgetMs(startedAtMs = 5_000L, nowMs = 5_000L))
        assertEquals(4_000L, remainingPlaybackRecoveryBudgetMs(startedAtMs = 5_000L, nowMs = 11_000L))
        assertEquals(0L, remainingPlaybackRecoveryBudgetMs(startedAtMs = 5_000L, nowMs = 15_500L))
    }

    @Test
    fun playbackSessionGuardsRecoveryAndFallbackWithAbsoluteDeadline() {
        val source = File("src/main/java/app/movia/android/ui/player/PlaybackSession.kt").readText()
        assertTrue(source.contains("beginReadyBudget(generation)"))
        assertTrue(source.contains("Absolute READY deadline fired"))
        assertTrue(source.contains("minOf(RELOAD_TIMEOUT_MS, remainingAttemptBudgetMs())"))
        assertTrue(source.contains("beginRecoveryBudget(generation)"))
        assertTrue(source.contains("Absolute recovery deadline fired"))
        assertTrue(source.contains("RECOVERY_DEADLINE_\$reason"))
        assertTrue(source.contains("READY_DEADLINE_\$reason"))
        assertTrue(source.contains("val remainingMs = remainingAttemptBudgetMs()"))
        assertTrue(source.contains("recoveryJob?.cancel()"))
        assertTrue(source.contains("recoveryJob = null"))
        assertTrue(source.contains("playbackMediaProbeBudgetMs(remainingReadyBudgetMs())"))
    }
}
