package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.StreamAdvertisement
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.StreamSkipInterval
import app.movia.android.domain.model.StreamSubtitle
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

    @Test
    fun streamInfoMetadataSurvivesCandidateBoundary() {
        val option = StreamOption(
            voice = "Кубик в Кубе",
            quality = "1080p",
            seeders = 42,
            url = "magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&so=3",
            source = "zona-provider",
            streamId = "stream:stable",
            providerItemId = "provider-item",
            infoHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            fileIndex = 3,
            filePath = "Show/S03E05.mkv",
            seasonNumber = 3,
            episodeNumber = 5,
            language = "ru",
            codec = "h264",
            userAgent = "Zona-compatible-UA",
            headers = mapOf("Referer" to "https://provider.example/"),
            subtitles = listOf(StreamSubtitle("https://provider.example/sub.vtt")),
            hasInternalSubtitles = true,
            videoTrackIndex = 0,
            audioTrackIndex = 1,
            durationMs = 3_600_000L,
            sizeBytes = 4_000_000_000L,
            reloadSupported = true,
            sourceId = "source-1",
            providerId = "provider-1",
            providerContentId = "content-1",
            transport = "torrent_p2p",
            transportMetadata = mapOf("gateway" to "local"),
            resolution = "1920x1080",
            unavailableQuality = false,
            isTrailer = false,
            downloadUrl = "https://provider.example/download",
            downloadHeaders = mapOf("Referer" to "https://provider.example/"),
            skipIntervals = listOf(StreamSkipInterval(1_000L, 2_000L)),
            advertisement = StreamAdvertisement(raw = "ad-marker", metadata = mapOf("kind" to "intro")),
            reloadData = "{\"method\":\"GET\",\"path\":\"/reload\"}",
            catalogMediaId = "catalog-1",
            canonicalTitle = "The Show",
            canonicalYear = 2025,
            canonicalMediaType = ContentType.SERIES,
            healthScore = 0.91,
            startupLatencyMs = 850L,
            recentFailureCount = 1,
            providerReliability = 0.88,
        )

        val restored = StreamCandidate.fromStreamOption(option).toStreamOption()
        assertEquals(option, restored)
    }

    @Test
    fun healthyP2pCanOutrankUnhealthyDirectWithoutProviderPriority() {
        val direct = StreamCandidate(
            stableStreamId = "direct",
            provider = "provider-a",
            url = "https://provider-a.example/video.m3u8",
            voice = "Original",
            quality = "1080p",
            transport = "hls",
            healthScore = 0.15,
            startupLatencyMs = 4_000L,
        )
        val p2p = StreamCandidate(
            stableStreamId = "p2p",
            provider = "provider-b",
            url = "magnet:?xt=urn:btih:BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
            voice = "Original",
            quality = "1080p",
            transport = "torrent_p2p",
            seeders = 12,
            healthScore = 0.95,
            startupLatencyMs = 600L,
        )

        assertEquals("p2p", StreamRanker.rankCandidates(listOf(direct, p2p)).first().stableStreamId)
    }
}
