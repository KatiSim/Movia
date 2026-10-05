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
}
