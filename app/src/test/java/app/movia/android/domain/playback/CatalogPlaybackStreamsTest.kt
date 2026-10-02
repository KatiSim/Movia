package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.StreamOption
import org.junit.Assert.*
import org.junit.Test

class CatalogPlaybackStreamsTest {
    private fun content(streams: List<StreamOption>) = MediaContent(
        id = "42", title = "Film", type = ContentType.MOVIE, year = 2025,
        rating = 7.0, genres = emptySet(), country = "", quality = "Auto",
        durationMinutes = 100, streams = streams,
    )
    private fun source(index: Int, season: Int? = null, episode: Int? = null) = StreamOption(
        "Studio $index", "Auto", url = "https://media.example/master.m3u8", streamId = "source:$index",
        audioTrackIndex = index, headers = mapOf("Referer" to "https://provider.example/$index"),
        seasonNumber = season, episodeNumber = episode, catalogMediaId = "42",
    )

    @Test fun sameLocatorKeepsEveryVoiceAndDistinctRequestProfile() {
        val sources = listOf(source(0), source(1))
        assertEquals(sources, catalogPlaybackStreams(content(sources), MediaRef("42")))
        assertEquals(listOf(0, 1), catalogPlaybackStreams(content(sources), MediaRef("42")).map { it.audioTrackIndex })
    }

    @Test fun seriesSeedsContainOnlyTheExactRequestedEpisode() {
        val exact = source(1, 2, 3)
        val sources = listOf(source(0), source(2, 2, 4), exact, source(3, 1, 3))
        assertEquals(listOf(exact), catalogPlaybackStreams(content(sources), MediaRef("42", 2, 3)))
    }

    @Test fun movieSeedsCannotRelabelAnotherEpisodeAsTheMovie() {
        assertEquals(listOf(source(0)), catalogPlaybackStreams(content(listOf(source(0), source(1, 1, 1))), MediaRef("42")))
    }

    @Test fun wrongCardOrIncompleteEpisodeDoesNotSeedPlayback() {
        val card = content(listOf(source(0), source(1).copy(catalogMediaId = "43")))
        assertEquals(listOf(source(0)), catalogPlaybackStreams(card, MediaRef("42")))
        assertTrue(catalogPlaybackStreams(card, MediaRef("43")).isEmpty())
        assertTrue(catalogPlaybackStreams(card, MediaRef("42", 2, null)).isEmpty())
    }
}
