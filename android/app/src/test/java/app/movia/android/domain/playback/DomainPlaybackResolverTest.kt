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
import kotlinx.coroutines.runBlocking

class DomainPlaybackResolverTest {

    private fun resolvedCandidate(
        id: String,
        mediaId: String = "42",
        url: String = "https://cdn.example/$id.mp4",
        sourceId: String? = "source-$id",
        voice: String = "Кубик в Кубе",
        quality: String = "1080p",
    ) = StreamCandidate(
        stableStreamId = id,
        provider = "provider",
        url = url,
        voice = voice,
        quality = quality,
        sourceId = sourceId,
        catalogMediaId = mediaId,
        canonicalTitle = "The Film",
        canonicalYear = 2025,
        canonicalMediaType = "movie",
    )

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
    fun strictBestRequiresAllExplicitVoiceAndQualityFilters() {
        val voiceOnly = StreamCandidate(
            stableStreamId = "voice", provider = "p", url = "http://voice",
            voice = "Кубик в Кубе", quality = "1080p", language = "ru",
        )
        val qualityOnly = StreamCandidate(
            stableStreamId = "quality", provider = "p", url = "http://quality",
            voice = "LostFilm", quality = "720p", language = "ru",
        )
        val context = StreamRankingContext(
            requestedVoice = "Кубик в Кубе",
            requestedQuality = "720p",
        )

        assertTrue(StreamRanker.strictBestGroup(listOf(voiceOnly, qualityOnly), context).isEmpty())
    }

    @Test
    fun relaxedBetterWeightsExplicitVoiceAndQualityEquallyThenUsesCompatibility() {
        val voiceOnly = StreamCandidate(
            stableStreamId = "voice", provider = "p", url = "http://voice",
            voice = "Кубик в Кубе", quality = "480p", language = "en", isTrailer = true,
            advertisement = StreamAdvertisement(raw = "ad"), healthScore = 0.99,
        )
        val qualityOnly = StreamCandidate(
            stableStreamId = "quality", provider = "p", url = "http://quality",
            voice = "LostFilm", quality = "720p", language = "ru", healthScore = 0.50,
        )
        val selected = StreamRanker.selectBest(
            listOf(voiceOnly, qualityOnly),
            requestedVoice = "Кубик в Кубе",
            requestedQuality = "720p",
        )

        assertEquals("quality", selected?.stableStreamId)
    }

    @Test
    fun betterGroupPreservesEncounterOrderOnEqualCompatibilityScore() {
        val first = StreamCandidate(
            stableStreamId = "first", provider = "p", url = "http://first",
            voice = "Original", quality = "1080p", language = "ru",
        )
        val second = first.copy(stableStreamId = "second", url = "http://second")

        assertEquals(
            listOf("first", "second"),
            StreamRanker.betterGroup(listOf(first, second), StreamRankingContext()).map { it.stableStreamId },
        )
    }

    @Test
    fun fallbackOrderStartsWithRelaxedBetterGroup() {
        val weak = StreamCandidate(
            stableStreamId = "weak", provider = "p", url = "http://weak",
            voice = "Original", quality = "480p", language = "en", isTrailer = true,
        )
        val compatible = StreamCandidate(
            stableStreamId = "compatible", provider = "p", url = "http://compatible",
            voice = "Original", quality = "1080p", language = "ru",
        )

        assertEquals(
            "compatible",
            StreamRanker.fallbackOrder(listOf(weak, compatible), StreamRankingContext()).first().stableStreamId,
        )
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
            logicalSourceId = "logical-source",
            providerItemId = "provider-item",
            infoHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            sourceTypeId = 17,
            contentTypeId = 3,
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
            resolutionWidth = 1920,
            resolutionHeight = 1080,
            unavailableQuality = false,
            isTrailer = false,
            downloadUrl = "https://provider.example/download",
            downloadHeaders = mapOf("Referer" to "https://provider.example/"),
            skipIntervals = listOf(StreamSkipInterval(1_000L, 2_000L)),
            advertisement = StreamAdvertisement(raw = "ad-marker", metadata = mapOf("kind" to "intro")),
            reloadData = "{\"method\":\"GET\",\"path\":\"/reload\"}",
            catalogMediaId = "catalog-1",
            canonicalTitle = "The Show",
            canonicalOriginalTitle = "The Show Original",
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
    fun coldSeededP2pDoesNotBeatEquivalentDirectCandidate() {
        val direct = StreamCandidate(
            stableStreamId = "direct-cold-tie",
            provider = "Collaps",
            url = "https://cdn.example.test/master.m3u8",
            voice = "Дубляж",
            quality = "1080p",
            transport = "hls",
            healthScore = 0.5,
            startupLatencyMs = null,
        )
        val p2p = StreamCandidate(
            stableStreamId = "p2p-cold-tie",
            provider = "Rutor",
            url = "https://p2p.example.test/candidate",
            voice = "Дубляж",
            quality = "1080p",
            transport = "torrent_p2p",
            seeders = 50,
            healthScore = 0.5,
            startupLatencyMs = null,
        )

        assertEquals("direct-cold-tie", StreamRanker.rankCandidates(listOf(p2p, direct)).first().stableStreamId)
    }

    @Test
    fun measuredFastP2pCanStillBeatUnknownDirectAtEqualHealth() {
        val direct = StreamCandidate(
            stableStreamId = "direct-unknown",
            provider = "Collaps",
            url = "https://cdn.example.test/master.m3u8",
            voice = "Дубляж",
            quality = "1080p",
            transport = "hls",
            healthScore = 0.5,
            startupLatencyMs = null,
        )
        val p2p = StreamCandidate(
            stableStreamId = "p2p-measured",
            provider = "Rutor",
            url = "https://p2p.example.test/candidate",
            voice = "Дубляж",
            quality = "1080p",
            transport = "torrent_p2p",
            seeders = 3,
            healthScore = 0.5,
            startupLatencyMs = 600L,
        )

        assertEquals("p2p-measured", StreamRanker.rankCandidates(listOf(direct, p2p)).first().stableStreamId)
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

    @Test
    fun resolverQueriesIdentityBeforeTitleAndStopsAfterUsableIdentityResult() = runBlocking {
        val calls = mutableListOf<String>()
        val backend = object : PlaybackResolverBackend {
            override suspend fun resolveByIdentity(
                request: PlaybackRequest,
                forceRefresh: Boolean,
            ) = PlaybackResolverBackendResponse(candidates = listOf(resolvedCandidate("identity"))).also {
                calls += "identity"
            }

            override suspend fun resolveByTitle(
                request: PlaybackRequest,
                forceRefresh: Boolean,
            ) = PlaybackResolverBackendResponse(candidates = listOf(resolvedCandidate("title"))).also {
                calls += "title"
            }
        }

        val result = DomainPlaybackResolver.resolveStreamsWithBackend(
            request = PlaybackRequest(
                mediaId = "42",
                title = "The Film",
                year = 2025,
                mediaType = ContentType.MOVIE,
            ),
            backend = backend,
        )

        assertEquals(listOf("identity"), calls)
        assertEquals("identity", (result as PlaybackResolverResult.Success).candidates.single().stableStreamId)
    }

    @Test
    fun freshDiscoveredCandidateReplacesStaleInitialLocatorForSameVariant() = runBlocking {
        val request = PlaybackRequest(
            mediaId = "42",
            title = "The Film",
            year = 2025,
            mediaType = ContentType.MOVIE,
        )
        val stale = resolvedCandidate(
            id = "stable-collaps",
            url = "https://cdn.example/master.m3u8?t=old",
            voice = "Original",
        )
        val fresh = stale.copy(
            url = "https://cdn.example/master.m3u8?t=fresh",
            audioTrackIndex = 7,
            sourceTypeId = 9,
        )
        val backend = object : PlaybackResolverBackend {
            override suspend fun resolveByIdentity(
                request: PlaybackRequest,
                forceRefresh: Boolean,
            ) = PlaybackResolverBackendResponse(candidates = listOf(fresh))

            override suspend fun resolveByTitle(
                request: PlaybackRequest,
                forceRefresh: Boolean,
            ) = PlaybackResolverBackendResponse()
        }

        val result = DomainPlaybackResolver.resolveStreamsWithBackend(
            request = request,
            initialCandidates = listOf(stale),
            backend = backend,
        ) as PlaybackResolverResult.Success

        assertEquals(1, result.candidates.size)
        assertEquals("https://cdn.example/master.m3u8?t=fresh", result.candidates.single().url)
        assertEquals(7, result.candidates.single().audioTrackIndex)
        assertEquals(9, result.candidates.single().sourceTypeId)
    }

    @Test
    fun resolverUsesTitleOnlyAsBoundedFallbackWhenIdentityHasNoUsableCandidates() = runBlocking {
        val calls = mutableListOf<String>()
        val backend = object : PlaybackResolverBackend {
            override suspend fun resolveByIdentity(
                request: PlaybackRequest,
                forceRefresh: Boolean,
            ) = PlaybackResolverBackendResponse(
                candidates = listOf(resolvedCandidate("wrong", mediaId = "other")),
            ).also { calls += "identity" }

            override suspend fun resolveByTitle(
                request: PlaybackRequest,
                forceRefresh: Boolean,
            ) = PlaybackResolverBackendResponse(
                candidates = listOf(resolvedCandidate("title")),
            ).also { calls += "title" }
        }

        val result = DomainPlaybackResolver.resolveStreamsWithBackend(
            request = PlaybackRequest(
                mediaId = "42",
                title = "The Film",
                year = 2025,
                mediaType = ContentType.MOVIE,
            ),
            backend = backend,
        )

        assertEquals(listOf("identity", "title"), calls)
        assertEquals("title", (result as PlaybackResolverResult.Success).candidates.single().stableStreamId)
    }

    @Test
    fun reloadMatchesRotatedUrlByLogicalIdentityAndRequestedVariant() {
        val request = PlaybackRequest(
            mediaId = "42",
            title = "The Film",
            year = 2025,
            mediaType = ContentType.MOVIE,
        )
        val previous = resolvedCandidate("old", url = "https://cdn.example/temporary-a.mp4")
            .copy(reloadSupported = true)
        val rotated = resolvedCandidate(
            "new",
            url = "https://cdn.example/temporary-b.mp4",
            sourceId = "source-old",
        )

        assertTrue(DomainPlaybackResolver.matchesReloadIdentity(previous, rotated, request))
        assertFalse(
            DomainPlaybackResolver.matchesReloadIdentity(
                previous,
                rotated.copy(voice = "LostFilm"),
                request,
            ),
        )
    }
}
