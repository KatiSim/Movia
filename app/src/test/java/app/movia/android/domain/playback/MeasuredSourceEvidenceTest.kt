package app.movia.android.domain.playback

import app.movia.android.domain.model.StreamOption
import org.junit.Assert.*
import org.junit.Test

class MeasuredSourceEvidenceTest {
    private fun option() = StreamOption(voice = "Studio", quality = "Не указано",
        url = "https://cdn.example/film.mp4", sourceId = "src:one", streamId = "provider-item:v2:scope:leaf")
    private fun proof() = MeasuredSourceEvidence("src:one", "VERIFIED", "MEDIA3_SUCCESS", "576p", listOf("576p"))
    private fun candidate(id: String, quality: String = "720p", decoded: Boolean = false) = StreamCandidate(
        stableStreamId = id, provider = "Test", url = "https://cdn.example/$id",
        voice = "Studio", quality = quality, language = "ru",
        transportMetadata = if (decoded) mapOf(
            "playback_verification_status" to "VERIFIED",
            "playback_verification_method" to "MEDIA3_SUCCESS",
            "playback_decoded" to "true") else emptyMap())

    @Test fun measuredQualityPreservesConcreteIdentityAndProviderClaim() {
        val measured = option().withMeasuredSourceEvidence(proof())
        assertEquals("576p", measured.quality)
        assertEquals(576, measured.resolutionHeight)
        assertEquals(option().streamId, measured.streamId)
        assertEquals("Не указано", measured.transportMetadata["provider_reported_quality"])
    }
    @Test fun differentIndexedSourceCannotSupplyMeasuredQuality() {
        assertEquals(option(), option().withMeasuredSourceEvidence(proof().copy(sourceId = "src:other")))
    }
    @Test fun manifestInspectionCannotClaimDecodedQuality() {
        assertEquals(option(), option().withMeasuredSourceEvidence(proof().copy(method = "HLS_MANIFEST")))
    }
    @Test fun failedEvidenceDoesNotRenameSourceQuality() {
        assertEquals(option(), option().withMeasuredSourceEvidence(proof().copy(status = "FAILED")))
    }
    @Test fun adaptiveContainerDoesNotBecomeLastActiveHeight() {
        val source = option().copy(quality = "Auto")
        val measured = source.withMeasuredSourceEvidence(proof().copy(actualQualities = listOf("360p", "576p", "720p")))
        assertEquals("Auto", measured.quality)
        assertNull(measured.resolutionHeight)
    }
    @Test fun unknownMeasurementStaysUnknown() {
        val measured = option().withMeasuredSourceEvidence(proof().copy(actualQuality = "Auto"))
        assertEquals("Не указано", measured.quality)
        assertNull(measured.resolutionHeight)
    }
    @Test fun provenVoiceBranchBeatsUnmeasuredPreferredResolution() {
        val proven = candidate("proven", "576p", true)
        val unmeasured = candidate("unknown", "1080p")
        assertEquals(proven, StreamRanker.selectBest(listOf(unmeasured, proven), "Studio", "Auto"))
    }
    @Test fun explicitQualityKeepsAvailableUnmeasuredAlternative() {
        val proven = candidate("proven", "576p", true)
        val wanted = candidate("wanted", "1080p")
        assertEquals(wanted, StreamRanker.selectBest(listOf(proven, wanted), "Studio", "1080p"))
    }
    @Test fun provenAlternateVoiceCannotOverrideExplicitVoice() {
        val proven = candidate("proven", decoded = true).copy(voice = "Original")
        val wanted = candidate("wanted")
        assertEquals(wanted, StreamRanker.selectBest(listOf(proven, wanted), "Studio", "Auto"))
    }
    @Test fun failedSourceIsKeptInInputButNotChosenForPlayback() {
        val failed = candidate("bad", decoded = true).copy(transportMetadata = mapOf("playback_verification_status" to "COOLDOWN"))
        val fallback = candidate("fallback")
        val input = listOf(failed, fallback)
        assertEquals(fallback, StreamRanker.selectBest(input, "Studio", "Auto"))
        assertEquals(2, input.size)
    }
    @Test fun fallbackDoesNotPreferProofOfTheWrongExplicitQuality() {
        val proven = candidate("proven", "576p", true)
        val wanted = candidate("wanted", "1080p")
        val fallback = StreamRanker.fallbackOrder(listOf(proven, wanted),
            StreamRankingContext(requestedVoice = "Studio", requestedQuality = "1080p"))
        assertEquals(wanted, fallback.first())
        assertTrue(fallback.contains(proven))
    }
    @Test fun fallbackRetainsEveryHealthyConcreteLeaf() {
        val proven = candidate("proven", "576p", true)
        val alternate = candidate("alternate", "1080p")
        val fallback = StreamRanker.fallbackOrder(listOf(alternate, proven), StreamRankingContext(requestedVoice = "Studio"))
        assertEquals(proven, fallback.first())
        assertEquals(setOf("proven", "alternate"), fallback.map { it.stableStreamId }.toSet())
    }
}
