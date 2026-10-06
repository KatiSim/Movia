package app.movia.android.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamContentEvidenceRankingTest {
    private fun candidate(id: String, transport: String, proof: Boolean = false) = StreamCandidate(
        stableStreamId = id, provider = "Example", url = "https://cdn.example/$id",
        voice = "TVShows", quality = "720p", transport = transport,
        transportMetadata = if (proof) mapOf(
            "measured_duration_ms" to "2892955", "expected_episode_duration_ms" to "2892955"
        ) else emptyMap(),
    )

    @Test fun measuredPlaylistWinsOverUnmeasuredEquivalentDirectLeaf() {
        val direct = candidate("direct", "direct")
        val playlist = candidate("playlist", "hls", proof = true)
        assertEquals(playlist, StreamRanker.rankCandidates(listOf(direct, playlist)).first())
    }

    @Test fun observedHealthStillWinsOverContentEvidence() {
        val healthy = candidate("healthy", "direct").copy(healthScore = 0.95)
        val measured = candidate("measured", "hls", proof = true)
        assertEquals(healthy, StreamRanker.rankCandidates(listOf(measured, healthy)).first())
    }

    @Test fun explicitVoiceAndQualityStillWinOverEvidence() {
        val requested = candidate("wanted", "direct").copy(voice = "Original", quality = "360p")
        val measured = candidate("measured", "hls", proof = true)
        val context = StreamRankingContext(requestedVoice = "Original", requestedQuality = "360p")
        assertEquals(requested, StreamRanker.rankCandidates(listOf(measured, requested), context = context).first())
    }

    @Test fun invalidOrContradictoryDurationDoesNotCreatePreference() {
        val direct = candidate("direct", "direct")
        for (value in listOf("0", "-1", "NaN", "60000", "1.5", "true")) {
            val invalid = candidate("invalid", "hls", proof = true).copy(
                transportMetadata = mapOf("measured_duration_ms" to value, "expected_episode_duration_ms" to "2892955")
            )
            assertEquals(direct, StreamRanker.rankCandidates(listOf(invalid, direct)).first())
        }
    }
    @Test fun explicitVoiceSurvivesAnUnknownProviderQuality() {
        val original = candidate("original", "hls", proof = true).copy(voice = "Original", quality = "Не указано")
        val other = candidate("other", "hls").copy(voice = "TVShows", quality = "720p")
        assertEquals(original, StreamRanker.selectBest(listOf(other, original), "Original", "720p"))
    }

    @Test fun recoveryTriesRequestedVoiceBeforeACompetingQuality() {
        val original = candidate("original", "hls", proof = true).copy(voice = "Original", quality = "Не указано")
        val other = candidate("other", "hls").copy(voice = "TVShows", quality = "720p")
        val context = StreamRankingContext(requestedVoice = "Original", requestedQuality = "720p")
        assertEquals(listOf(original, other), StreamRanker.fallbackOrder(listOf(other, original), context))
    }

    @Test fun failedVoiceCanFallBackWithoutRelabelingTheReplacement() {
        val original = candidate("original", "hls").copy(voice = "Original", quality = "Не указано")
        val other = candidate("other", "hls").copy(voice = "TVShows", quality = "720p")
        val chosen = StreamRanker.selectBest(listOf(original, other), "Original", "720p", failedStreamIds = setOf("original"))
        assertEquals("TVShows", chosen?.voice)
    }

}
