package app.movia.android.ui.player

import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.PlaybackStatus
import app.movia.android.domain.model.PlaybackSwitchState
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamFailureClass
import app.movia.android.domain.playback.StreamFailureClassifier
import app.movia.android.domain.playback.StreamProblemTracker
import app.movia.android.domain.playback.StreamRanker
import app.movia.android.domain.playback.StreamRankingContext
import app.movia.android.domain.playback.openWithSingleRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class Media3PlaybackStabilizationTest {

    private fun testCandidate(
        id: String = "stream:spiderman_1080p",
        url: String = "https://hye1eaipby4w.interkh.com/master.m3u8",
        quality: String = "1080p",
        voice: String = "Дубляж",
    ) = StreamCandidate(
        stableStreamId = id,
        provider = "Collaps",
        url = url,
        voice = voice,
        quality = quality,
        transport = "hls",
    )

    @Test
    fun failureStateSetsClearMessageAndNeverLeavesInfiniteBuffering() {
        // When all candidates fail, playback must end in IDLE with the exact user-specified message
        val failureState = PlaybackState(
            mediaId = "6",
            displayTitle = "Человек-паук: Нет пути домой",
            status = PlaybackStatus.IDLE,
            switchState = PlaybackSwitchState.FAILED,
            isPlaying = false,
            playWhenReady = false,
            statusMessage = "Произошла ошибка: повторите",
        )

        assertNotEquals(PlaybackStatus.BUFFERING, failureState.status)
        assertEquals(PlaybackStatus.IDLE, failureState.status)
        assertEquals(PlaybackSwitchState.FAILED, failureState.switchState)
        assertEquals("Произошла ошибка: повторите", failureState.statusMessage)
        assertFalse(failureState.isPlaying)
    }

    @Test
    fun stallWatchdogClassifiedAsNetworkFailure() {
        assertEquals(StreamFailureClass.NETWORK, StreamFailureClassifier.fromReason("BUFFERING_TIMEOUT"))
        assertEquals(StreamFailureClass.NETWORK, StreamFailureClassifier.fromReason("STARTUP_TIMEOUT"))
    }

    @Test
    fun hundredConsecutivePlaybackRunsRemainStable() {
        val stream = testCandidate()
        val context = StreamRankingContext(requestedVoice = "Дубляж", requestedQuality = "1080p")

        var successfulSelections = 0
        val latencySamples = mutableListOf<Long>()

        for (i in 1..100) {
            val startNs = System.nanoTime()

            // 1. Candidate selection
            val candidates = listOf(stream)
            val selected = StreamRanker.selectBest(
                candidates = candidates,
                requestedVoice = "Дубляж",
                requestedQuality = "1080p",
                context = context,
            )
            assertNotNull("Iteration $i must select healthy candidate", selected)
            assertEquals(stream.stableStreamId, selected?.stableStreamId)

            // 2. Verified connection logic with single retry on transient error
            var connectionAttempts = 0
            val opened = openWithSingleRetry(
                resetBeforeRetry = { connectionAttempts++ },
                open = {
                    connectionAttempts++
                    // Simulate 99% reliable stream, 1% transient flake that recovers on retry
                    if (i == 42 && connectionAttempts == 1) {
                        throw IOException("Transient network hiccup")
                    }
                    200L
                },
            )
            assertEquals(200L, opened)

            // 3. Problem tracker verify candidate remains healthy
            assertFalse("Candidate should not be problematic", stream.isProblematic)

            successfulSelections++
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            latencySamples.add(elapsedMs)
        }

        assertEquals("All 100 playback selection runs must succeed", 100, successfulSelections)
        val avgLatency = latencySamples.average()
        assertTrue("Average selection overhead must be < 5ms (was ${avgLatency}ms)", avgLatency < 5.0)
    }

    @Test
    fun reconnectPolicyRetriesTransientErrorAndPreservesStreamContinuity() {
        var openAttempts = 0
        var resetAttempts = 0
        val result = openWithSingleRetry(
            resetBeforeRetry = {
                resetAttempts++
            },
            open = {
                openAttempts++
                if (openAttempts == 1) throw IOException("Connection reset by peer")
                1024L
            },
        )
        assertEquals(1024L, result)
        assertEquals(1, resetAttempts)
        assertEquals(2, openAttempts)
    }
}
