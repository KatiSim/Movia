package app.movia.android.domain.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryBudgetTest {
    @Test fun largeInventoryCannotIncreaseAutomaticAttemptCount() {
        val budget = PlaybackRecoveryBudget()
        val decisions = (0 until 1000).map { budget.tryAcquire(it.toLong()) }
        assertEquals(6,decisions.count { it })
    }

    @Test fun elapsedFailureWindowExhaustsBudgetBeforeAttemptLimit() {
        val budget = PlaybackRecoveryBudget()
        assertTrue(budget.tryAcquire(100))
        assertTrue(budget.tryAcquire(60_099))
        assertFalse(budget.tryAcquire(60_100))
    }

    @Test fun firstAttemptStartsWindowRatherThanConstructionTime() {
        val budget = PlaybackRecoveryBudget()
        assertFalse(budget.isExhausted(1_000_000))
        assertTrue(budget.tryAcquire(1_000_000))
        assertFalse(budget.isExhausted(1_000_001))
    }

    @Test fun newUserRequestOrDecodedFrameCanResetFailureEpisode() {
        val budget = PlaybackRecoveryBudget(maxAttempts=1)
        assertTrue(budget.tryAcquire(100))
        assertFalse(budget.tryAcquire(101))
        budget.reset()
        assertTrue(budget.tryAcquire(100_000))
    }
}
