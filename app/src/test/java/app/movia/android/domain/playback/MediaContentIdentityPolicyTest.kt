package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType
import org.junit.Assert.*
import org.junit.Test

class MediaContentIdentityPolicyTest {
    private val feature = PlaybackRequest("158","Interstellar",ContentType.MOVIE,year=2014,expectedDurationMs=169*60_000L)
    @Test fun minutePlaceholderIsRejectedForAKnownFeature() {
        assertTrue(MediaContentIdentityPolicy.durationMismatch(feature,60_075))
        assertFalse(MediaContentIdentityPolicy.durationMismatch(feature,10_143_947))
    }
    @Test fun genuineShortFilmIsAcceptedWithoutAMinimumLengthRule() {
        assertFalse(MediaContentIdentityPolicy.durationMismatch(feature.copy(expectedDurationMs=60_000),60_075))
    }
    @Test fun unknownDurationsRemainUnknown() {
        assertFalse(MediaContentIdentityPolicy.durationMismatch(feature.copy(expectedDurationMs=null),60_075))
        assertFalse(MediaContentIdentityPolicy.durationMismatch(feature,-1))
    }
    @Test fun seriesAndTrailersDoNotInheritMovieDurationRules() {
        assertFalse(MediaContentIdentityPolicy.durationMismatch(feature.copy(mediaType=ContentType.SERIES,seasonNumber=1,episodeNumber=1),60_075))
        assertFalse(MediaContentIdentityPolicy.durationMismatch(feature.copy(isTrailer=true),60_075))
    }
    @Test fun exactEpisodeRuntimeRejectsPlaceholderAndAllowsRealShortEpisode() {
        val episode=feature.copy(mediaType=ContentType.SERIES,seasonNumber=1,episodeNumber=2)
        assertTrue(MediaContentIdentityPolicy.durationMismatch(episode,60_075,2_892_955))
        assertFalse(MediaContentIdentityPolicy.durationMismatch(episode,2_892_955,2_892_955))
        assertFalse(MediaContentIdentityPolicy.durationMismatch(episode,60_075,60_000))
        assertFalse(MediaContentIdentityPolicy.durationMismatch(episode,60_075,null))
    }

    @Test fun episodeRuntimeEvidenceMustMatchCatalogAndCoordinates() {
        val request=PlaybackRequest("159","Breaking Bad",ContentType.SERIES,seasonNumber=1,episodeNumber=2)
        val evidence=StreamCandidate(stableStreamId="verified",provider="HDRezka",url="https://cdn.example/episode.m3u8",
            catalogMediaId="159",seasonNumber=1,episodeNumber=2,transportMetadata=mapOf(
                "hdrezka_episode_verified" to "true","expected_episode_duration_ms" to "2892955"))
        assertEquals(2892955L,MediaContentIdentityPolicy.episodeDuration(request,listOf(evidence)))
        assertNull(MediaContentIdentityPolicy.episodeDuration(request,listOf(evidence.copy(episodeNumber=1))))
        assertNull(MediaContentIdentityPolicy.episodeDuration(request,listOf(evidence.copy(catalogMediaId="42"))))
        assertNull(MediaContentIdentityPolicy.episodeDuration(request,listOf(evidence.copy(transportMetadata=emptyMap()))))
        assertNull(MediaContentIdentityPolicy.episodeDuration(request,listOf(evidence,evidence.copy(
            transportMetadata=evidence.transportMetadata+("expected_episode_duration_ms" to "60000")))))
    }
}
