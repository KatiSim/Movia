package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPlaybackResolverTest {

    @Test
    fun testPlaybackRequestCanonicalEpisodeKey() {
        val seriesRequest = PlaybackRequest(
            mediaId = "100",
            title = "Breaking Bad",
            mediaType = ContentType.SERIES,
            seasonNumber = 1,
            episodeNumber = 1,
        )
        assertTrue(seriesRequest.isSeries)
        assertEquals("100:s1e1", seriesRequest.canonicalEpisodeKey)

        val movieRequest = PlaybackRequest(
            mediaId = "200",
            title = "Inception",
            mediaType = ContentType.MOVIE,
        )
        assertFalse(movieRequest.isSeries)
        assertEquals("200", movieRequest.canonicalEpisodeKey)
    }

    @Test
    fun testStreamDeduplicatorNormalizesAndRemovesDuplicates() {
        val stream1 = StreamCandidate(
            stableStreamId = "stream:abc1",
            provider = "Rutor",
            url = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111",
            voice = "LostFilm",
            quality = "1080p",
            seasonNumber = 1,
            episodeNumber = 1,
            seeders = 10,
        )
        val duplicate = StreamCandidate(
            stableStreamId = "stream:abc1",
            provider = "rutor",
            url = "magnet:?xt=urn:btih:1111111111111111111111111111111111111111&tr=extra",
            voice = "lostfilm",
            quality = "1080p",
            seasonNumber = 1,
            episodeNumber = 1,
            seeders = 20,
        )
        val stream2 = StreamCandidate(
            stableStreamId = "stream:abc2",
            provider = "Rutor",
            url = "magnet:?xt=urn:btih:2222222222222222222222222222222222222222",
            voice = "Кубик в Кубе",
            quality = "720p",
            seasonNumber = 1,
            episodeNumber = 1,
            seeders = 5,
        )

        val deduplicated = StreamDeduplicator.deduplicate(listOf(stream1, duplicate, stream2))
        assertEquals(2, deduplicated.size)
        assertEquals("LostFilm", deduplicated[0].voice)
        assertEquals("Кубик в Кубе", deduplicated[1].voice)
    }

    @Test
    fun testStreamRankerSelectsExactVoiceAndQuality() {
        val c1 = StreamCandidate(stableStreamId = "1", provider = "p", url = "http://1", voice = "Дубляж", quality = "1080p")
        val c2 = StreamCandidate(stableStreamId = "2", provider = "p", url = "http://2", voice = "Кубик в Кубе", quality = "720p")
        val c3 = StreamCandidate(stableStreamId = "3", provider = "p", url = "http://3", voice = "LostFilm", quality = "1080p")

        val candidates = listOf(c1, c2, c3)

        val selected = StreamRanker.selectBest(candidates, requestedVoice = "Кубик в Кубе", requestedQuality = "720p")
        assertNotNull(selected)
        assertEquals("2", selected?.stableStreamId)
        assertEquals("Кубик в Кубе", selected?.voice)
        assertEquals("720p", selected?.quality)
    }

    @Test
    fun testStreamRankerExcludesFailedStreams() {
        val dead = StreamCandidate(stableStreamId = "dead", provider = "p", url = "http://dead", voice = "Кубик в Кубе", quality = "720p")
        val fallback = StreamCandidate(stableStreamId = "alive", provider = "p", url = "http://alive", voice = "Кубик в Кубе", quality = "1080p")

        val candidates = listOf(dead, fallback)
        val selected = StreamRanker.selectBest(
            candidates,
            requestedVoice = "Кубик в Кубе",
            requestedQuality = "720p",
            failedStreamIds = setOf("dead"),
        )
        assertNotNull(selected)
        assertEquals("alive", selected?.stableStreamId)
    }

    @Test
    fun testStreamRankerReturnsNullOnEmptyPool() {
        val selected = StreamRanker.selectBest(emptyList(), "Дубляж", "1080p")
        assertNull(selected)
    }
}
