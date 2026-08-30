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
    ) = StreamOption(
        voice = voice,
        quality = quality,
        url = url,
        source = "rutor",
        infoHash = infoHash,
        fileIndex = fileIndex,
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
    fun canonicalIdIsStableForSameIdentity() {
        val original = option("http://release/stream")
        val reconstructed = option("http://release/stream")
        assertEquals(original.canonicalStreamId(1, 1), reconstructed.canonicalStreamId(1, 1))
        assertEquals("stream:", original.withCanonicalStreamId(1, 1).streamId.take(7))
    }
}
