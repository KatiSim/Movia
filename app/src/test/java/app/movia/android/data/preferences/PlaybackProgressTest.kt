package app.movia.android.data.preferences

import app.movia.android.domain.model.PlaybackProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {
    @Test
    fun fractionReflectsPlaybackPosition() {
        val progress = PlaybackProgress(title = "Test", positionMs = 25_000L, durationMs = 100_000L)
        assertEquals(0.25f, progress.fraction, 0.0001f)
    }

    @Test
    fun fractionClampsInvalidPositions() {
        assertEquals(1f, PlaybackProgress("Test", 150L, 100L).fraction, 0f)
        assertEquals(0f, PlaybackProgress("Test", 10L, 0L).fraction, 0f)
    }
}
