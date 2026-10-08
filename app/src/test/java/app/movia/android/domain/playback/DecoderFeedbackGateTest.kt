package app.movia.android.domain.playback

import org.junit.Assert.*
import org.junit.Test

class DecoderFeedbackGateTest {
    @Test fun duplicateFramesWithinOnePreparationAreNotRepeatedFeedback() {
        val gate=DecoderFeedbackGate()
        assertFalse(gate.claimFirstFrame())
        gate.prepare(100)
        assertTrue(gate.claimFirstFrame())
        assertFalse(gate.claimFirstFrame())
    }
    @Test fun RecoveryWithinTheSamePlaybackGenerationCanReportANewFrame() {
        val gate=DecoderFeedbackGate()
        gate.prepare(100);assertTrue(gate.claimFirstFrame())
        gate.prepare(300);assertTrue(gate.claimFirstFrame())
    }
    @Test fun preparationLatencyDoesNotReusePreviousSuccessfulStartup() {
        val gate=DecoderFeedbackGate()
        gate.prepare(100);assertEquals(10L,gate.latencyMs(110))
        gate.prepare(1_000);assertEquals(500L,gate.latencyMs(1_500))
    }
    @Test fun delayedAcknowledgementCannotBelongToAnotherPreparation() {
        val gate=DecoderFeedbackGate()
        gate.prepare(100);val old=gate.attemptId()
        gate.prepare(200)
        assertFalse(gate.isCurrent(old));assertTrue(gate.isCurrent(gate.attemptId()))
    }

    @Test fun playingWithoutRenderedFrameStillRequiresStartupRecovery() {
        val gate=DecoderFeedbackGate();gate.prepare(100)
        assertTrue(gate.shouldRecoverStartup(true));assertFalse(gate.hasRenderedFrame())
    }
    @Test fun pauseDoesNotCountAsFailedVideoStartup() {
        val gate=DecoderFeedbackGate();gate.prepare(100)
        assertFalse(gate.shouldRecoverStartup(false));assertTrue(gate.shouldRecoverStartup(true))
    }
    @Test fun renderedFrameSuppressesOnlyItsOwnPreparationTimeout() {
        val gate=DecoderFeedbackGate();gate.prepare(100);gate.onRenderedFrame()
        assertFalse(gate.shouldRecoverStartup(true));assertTrue(gate.hasRenderedFrame())
        gate.prepare(200);assertTrue(gate.shouldRecoverStartup(true));assertFalse(gate.hasRenderedFrame())
    }
    @Test fun strayFrameBeforeAnyPreparationDoesNotStartOrSatisfyAWatchdog() {
        val gate=DecoderFeedbackGate();gate.onRenderedFrame()
        assertFalse(gate.hasRenderedFrame());assertFalse(gate.shouldRecoverStartup(true))
    }

    @Test fun frameDuringPendingReloadSupersedesTheOriginalFailure() {
        val gate=DecoderFeedbackGate();gate.prepare(100)
        val attempt=gate.attemptId();val version=gate.renderedFrameVersion()
        assertTrue(gate.recoveryIsCurrent(attempt,version))
        gate.onRenderedFrame()
        assertFalse(gate.recoveryIsCurrent(attempt,version))
    }
    @Test fun segmentFailureAfterAnEarlierFrameMayRecoverUntilAnotherFrameOrPreparation() {
        val gate=DecoderFeedbackGate();gate.prepare(100);gate.onRenderedFrame()
        val attempt=gate.attemptId();val version=gate.renderedFrameVersion()
        assertTrue(gate.recoveryIsCurrent(attempt,version))
        gate.prepare(200)
        assertFalse(gate.recoveryIsCurrent(attempt,version))
    }
    @Test fun decoderStartupTimeoutHasTransientRetryIntent() {
        assertEquals(StreamFailureClass.NETWORK, StreamFailureClassifier.fromReason("DECODER_STARTUP_TIMEOUT"))
    }
}
