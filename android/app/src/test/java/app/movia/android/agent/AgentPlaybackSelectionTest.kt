package app.movia.android.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AgentPlaybackSelectionTest {
    @Test
    fun savedPreferencesGuideRankingButAreNotStrictOperationRequirements() {
        val intent = resolveMediaPlayVariantIntent(
            explicitQuality = null,
            explicitVoice = null,
            exactStreamQuality = null,
            exactStreamVoice = null,
            titleQuality = "1080p",
            titleVoice = "AlexFilm",
            globalQuality = "720p",
            globalVoice = "Auto",
        )

        assertEquals("1080p", intent.preferredQuality)
        assertEquals("AlexFilm", intent.preferredVoice)
        assertNull(intent.requiredQuality)
        assertNull(intent.requiredVoice)
    }

    @Test
    fun explicitVariantSelectionRemainsStrict() {
        val intent = resolveMediaPlayVariantIntent(
            explicitQuality = "720p",
            explicitVoice = "LostFilm",
            exactStreamQuality = "1080p",
            exactStreamVoice = "AlexFilm",
            titleQuality = "1080p",
            titleVoice = "AlexFilm",
            globalQuality = "Auto",
            globalVoice = "Auto",
        )

        assertEquals("720p", intent.preferredQuality)
        assertEquals("LostFilm", intent.preferredVoice)
        assertEquals("720p", intent.requiredQuality)
        assertEquals("LostFilm", intent.requiredVoice)
    }

    @Test
    fun exactStreamStillSuppliesRankingPreferenceWithoutInventingStrictVoiceOrQuality() {
        val intent = resolveMediaPlayVariantIntent(
            explicitQuality = null,
            explicitVoice = null,
            exactStreamQuality = "4K",
            exactStreamVoice = "Original",
            titleQuality = "1080p",
            titleVoice = "Дубляж",
            globalQuality = "Auto",
            globalVoice = "Auto",
        )

        assertEquals("4K", intent.preferredQuality)
        assertEquals("Original", intent.preferredVoice)
        assertNull(intent.requiredQuality)
        assertNull(intent.requiredVoice)
    }
    @Test
    fun explicitSeriesMediaIdCanStartWithoutWarmCatalogCard() {
        val content = minimalExplicitSeriesPlaybackContent(
            mediaId = "217",
            requestedTitle = null,
            season = 1,
            episode = 1,
        )

        assertNotNull(content)
        assertEquals("217", content?.id)
        assertEquals("217", content?.title)
        assertEquals(app.movia.android.domain.model.ContentType.SERIES, content?.type)
    }

    @Test
    fun minimalExplicitSeriesFallbackRejectsIncompleteIdentity() {
        assertNull(minimalExplicitSeriesPlaybackContent("217", null, 1, null))
        assertNull(minimalExplicitSeriesPlaybackContent("217", null, null, 1))
        assertNull(minimalExplicitSeriesPlaybackContent(null, "Футурама", 1, 1))
    }

}
