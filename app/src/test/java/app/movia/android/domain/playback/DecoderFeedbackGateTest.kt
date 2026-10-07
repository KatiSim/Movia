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
}
