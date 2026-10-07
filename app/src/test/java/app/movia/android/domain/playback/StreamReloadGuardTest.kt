package app.movia.android.domain.playback
import org.junit.Test
import org.junit.Assert.*
class StreamReloadGuardTest {
    @Test fun preparationDoesNotAuthorizeRepeatedReloadOfTheSameFailedSource() {
        val guard=StreamReloadGuard();assertTrue(guard.tryAcquire("a"));assertFalse(guard.tryAcquire("a"))
        assertTrue(guard.tryAcquire("b"));assertFalse(guard.tryAcquire("a"))
    }
    @Test fun decodedVideoRearmsOnlyItsOwnConcreteSource() {
        val guard=StreamReloadGuard();guard.tryAcquire("a");guard.tryAcquire("b");guard.onDecoded("a")
        assertTrue(guard.tryAcquire("a"));assertFalse(guard.tryAcquire("b"))
    }
    @Test fun boundedMemoryDoesNotEvictOldFailuresAndCreateAnotherLoop() {
        val guard=StreamReloadGuard(2);assertTrue(guard.tryAcquire("a"));assertTrue(guard.tryAcquire("b"))
        assertFalse(guard.tryAcquire("c"));assertFalse(guard.tryAcquire("a"));guard.reset("b");assertTrue(guard.tryAcquire("c"))
    }
    @Test fun newRequestOrExplicitChoiceStartsANewBoundedAttempt() {
        val guard=StreamReloadGuard();guard.tryAcquire("a");guard.reset("a");assertTrue(guard.tryAcquire("a"))
        guard.reset();assertTrue(guard.tryAcquire("a"));assertFalse(guard.tryAcquire(""))
    }
}
