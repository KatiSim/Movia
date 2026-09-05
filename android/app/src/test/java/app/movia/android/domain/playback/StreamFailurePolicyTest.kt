package app.movia.android.domain.playback

import java.net.ConnectException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamFailurePolicyTest {
    private fun candidate(url: String = "https://cdn.example/a.mp4") = StreamCandidate(
        stableStreamId = "stream:a",
        provider = "provider",
        url = url,
        voice = "ru",
        quality = "1080p",
    )

    @Test
    fun networkFailureBecomesProblemOnlyOnThirdFailureForSameLocator() {
        val tracker = StreamProblemTracker()
        val stream = candidate()

        assertFalse(tracker.shouldMarkProblem(stream, StreamFailureClass.NETWORK))
        assertFalse(tracker.shouldMarkProblem(stream, StreamFailureClass.NETWORK))
        assertTrue(tracker.shouldMarkProblem(stream, StreamFailureClass.NETWORK))
        assertEquals(3, tracker.networkFailureCount(stream))
    }

    @Test
    fun differentRotatedLocatorHasIndependentNetworkCount() {
        val tracker = StreamProblemTracker()
        val first = candidate("https://cdn.example/a.mp4")
        val rotated = candidate("https://cdn.example/b.mp4")

        repeat(2) { assertFalse(tracker.shouldMarkProblem(first, StreamFailureClass.NETWORK)) }
        assertFalse(tracker.shouldMarkProblem(rotated, StreamFailureClass.NETWORK))
        assertEquals(2, tracker.networkFailureCount(first))
        assertEquals(1, tracker.networkFailureCount(rotated))
    }

    @Test
    fun nonNetworkFailureIsProblemImmediately() {
        assertTrue(StreamProblemTracker().shouldMarkProblem(candidate(), StreamFailureClass.NON_NETWORK))
    }

    @Test
    fun clearResetsTransientNetworkCount() {
        val tracker = StreamProblemTracker()
        val stream = candidate()
        repeat(2) { tracker.shouldMarkProblem(stream, StreamFailureClass.NETWORK) }
        tracker.clear(stream)
        assertEquals(0, tracker.networkFailureCount(stream))
        assertFalse(tracker.shouldMarkProblem(stream, StreamFailureClass.NETWORK))
    }

    @Test
    fun classifierFindsNestedConnectExceptionAndStartupTimeout() {
        val wrapped = IllegalStateException("outer", ConnectException("offline"))
        assertEquals(StreamFailureClass.NETWORK, StreamFailureClassifier.fromThrowable(wrapped))
        assertEquals(StreamFailureClass.NETWORK, StreamFailureClassifier.fromReason("STARTUP_TIMEOUT"))
        assertEquals(StreamFailureClass.NON_NETWORK, StreamFailureClassifier.fromReason("PREPARE_FAILED"))
    }
}
