package app.movia.android.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamIdentityTest {
    private fun option(
        url: String,
        quality: String = "720p",
        voice: String = "Кубик в Кубе",
        infoHash: String = "AABBCCDDEEFF00112233445566778899AABBCCDD",
        fileIndex: Int = 1,
        sourceTypeId: Int? = null,
        videoTrackIndex: Int? = null,
        audioTrackIndex: Int? = null,
    ) = StreamOption(
        voice = voice,
        quality = quality,
        url = url,
        source = "rutor",
        infoHash = infoHash,
        fileIndex = fileIndex,
        sourceTypeId = sourceTypeId,
        videoTrackIndex = videoTrackIndex,
        audioTrackIndex = audioTrackIndex,
    )

    @Test
    fun mirrorsOfSameVariantRemainOneFallbackGroup() {
        val first = option("http://mirror-1/stream")
        val second = option("http://mirror-2/stream")
        assertTrue(first.sameRequestedVariant(second, seasonOverride = 1, episodeOverride = 1))
    }

    @Test
    fun differentQualityOrVoiceIsNotSameFallbackVariant() {
        val base = option("http://mirror-1/stream")
        assertTrue(
            !base.sameRequestedVariant(
                option("http://mirror-2/stream", quality = "1080p"),
                seasonOverride = 1,
                episodeOverride = 1,
            ),
        )
        assertTrue(
            !base.sameRequestedVariant(
                option("http://mirror-3/stream", voice = "LostFilm"),
                seasonOverride = 1,
                episodeOverride = 1,
            ),
        )
    }

    @Test
    fun canonicalIdDistinguishesReleasesWithSameQualityAndVoice() {
        val first = option("http://release-a/stream", infoHash = "AABBCCDDEEFF00112233445566778899AABBCCDD")
        val second = option("http://release-b/stream", infoHash = "11223344556677889900AABBCCDDEEFF11223344")
        assertNotEquals(
            first.canonicalStreamId(1, 1),
            second.canonicalStreamId(1, 1),
        )
    }

    @Test
    fun hdrezkaAndVoidboostUseVerifiedNumericPathDedupeIdentity() {
        for (sourceType in listOf(2, 28)) {
            val first = option(
                "https://mirror-a.example/signed/100/200/video.mp4?token=one",
                sourceTypeId = sourceType,
            )
            val second = option(
                "https://mirror-b.example/other/100/200/video.mp4?token=two",
                sourceTypeId = sourceType,
            )
            assertEquals(first.canonicalLocator(), second.canonicalLocator())
            assertEquals(first.variantIdentity(), second.variantIdentity())
        }
    }

    @Test
    fun filmixUsesVerifiedFinalTwoPathSegmentsForDedupe() {
        val first = option(
            "https://cdn-a.example/s/opaque-a/folder/video.mp4?expires=1",
            sourceTypeId = 3,
        )
        val second = option(
            "https://cdn-b.example/s/opaque-b/folder/video.mp4?expires=2",
            sourceTypeId = 3,
        )
        assertEquals("folder/video.mp4", first.canonicalLocator())
        assertEquals(first.variantIdentity(), second.variantIdentity())
    }

    @Test
    fun unrelatedHttpSourceTypesKeepExactLocator() {
        val first = option("https://cdn.example/video.mp4?token=one", sourceTypeId = 49)
        val second = option("https://cdn.example/video.mp4?token=two", sourceTypeId = 49)
        assertNotEquals(first.canonicalLocator(), second.canonicalLocator())
        assertNotEquals(first.variantIdentity(), second.variantIdentity())
    }

    @Test
    fun canonicalIdIsStableForSameIdentity() {
        val original = option("http://release/stream")
        val reconstructed = option("http://release/stream")
        assertEquals(original.canonicalStreamId(1, 1), reconstructed.canonicalStreamId(1, 1))
        assertEquals("stream:", original.withCanonicalStreamId(1, 1).streamId.take(7))
    }
    @Test
    fun mediaProbeTrackIndexesArePartOfVariantIdentity() {
        val first = option(
            "https://media.example/master.mpd",
            sourceTypeId = 32,
            videoTrackIndex = 0,
            audioTrackIndex = 0,
        )
        val second = option(
            "https://media.example/master.mpd",
            sourceTypeId = 32,
            videoTrackIndex = 0,
            audioTrackIndex = 1,
        )
        assertNotEquals(first.variantIdentity(), second.variantIdentity())
        assertNotEquals(first.canonicalStreamId(), second.canonicalStreamId())
        assertTrue(!first.sameRequestedVariant(second))
    }

    @Test
    fun unspecifiedTrackIndexRemainsCompatibilityWildcard() {
        val probed = option(
            "https://media.example/master.mpd",
            sourceTypeId = 32,
            videoTrackIndex = 1,
            audioTrackIndex = 2,
        )
        val unprobed = option("https://media.example/master.mpd", sourceTypeId = 32)
        assertTrue(probed.sameRequestedVariant(unprobed))
    }


    private fun native() = option("https://cdn.example/old.m3u8", quality = "Не указано")
        .copy(logicalSourceId = "logical-source:v2:one", catalogMediaId = "42", canonicalYear = 2025,
            canonicalMediaType = ContentType.MOVIE, transport = "hls")

    @Test fun nativeMeasuredQualityAndVoiceLabelsDoNotChangeIdentity() {
        val before = native()
        val after = before.copy(url = "https://other.example/new.m3u8", quality = "480p", voice = "Renamed", language = "ja")
        assertEquals(before.logicalSourceIdentity(), after.logicalSourceIdentity())
        assertEquals(before.canonicalStreamId(), after.canonicalStreamId())
    }
    @Test fun nativeIdentityNeverBorrowsAnotherCatalogOrEpisode() {
        val base = native()
        for (other in listOf(base.copy(catalogMediaId = "43"), base.copy(canonicalYear = 2024),
            base.copy(seasonNumber = 1, episodeNumber = 2), base.copy(isTrailer = true))) {
            assertNotEquals(base.logicalSourceIdentity(), other.logicalSourceIdentity())
        }
    }
    @Test fun nativeTracksFilesAndRenditionsRemainSeparate() {
        val base = native()
        for (other in listOf(base.copy(audioTrackIndex = 0), base.copy(audioTrackIndex = 1),
            base.copy(videoTrackIndex = 1), base.copy(fileIndex = 2), base.copy(filePath = "other.mp4"),
            base.copy(logicalSourceId = "logical-source:v2:other"), base.copy(transport = "dash"))) {
            assertNotEquals(base.logicalSourceIdentity(), other.logicalSourceIdentity())
        }
    }
    @Test fun legacyProviderLevelIdsCannotEraseVariantDimensions() {
        val base = native().copy(logicalSourceId = null, providerId = "provider-wide")
        assertNotEquals(base.logicalSourceIdentity(), base.copy(quality = "1080p").logicalSourceIdentity())
        assertNotEquals(base.logicalSourceIdentity(), base.copy(voice = "Other").logicalSourceIdentity())
    }
    @Test fun explicitPublicIdSurvivesMeasuredMetadata() {
        val base = native().copy(streamId = "provider-item:v2:one")
        assertEquals(base.canonicalStreamId(), base.copy(quality = "480p", url = "https://cdn.example/new.m3u8").canonicalStreamId())
    }
}
