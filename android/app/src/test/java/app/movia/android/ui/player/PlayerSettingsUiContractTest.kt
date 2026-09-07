package app.movia.android.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSettingsUiContractTest {
    @Test
    fun settingsExposeOnlyReleaseVoiceAndVideoQualityNotRawEmbeddedTracks() {
        val source = File("src/main/java/app/movia/android/ui/player/PlayerScreen.kt").readText()
        assertTrue(source.contains("PlayerSettingsSectionLabel(\"КАЧЕСТВО ВИДЕО\")"))
        assertTrue(source.contains("PlayerSettingsSectionLabel(\"ОЗВУЧКА РЕЛИЗА\")"))
        assertFalse(source.contains("ВСТРОЕННЫЕ АУДИОДОРОЖКИ"))
        assertFalse(source.contains("embeddedAudioOptions = embeddedAudioOptions"))
        assertFalse(source.contains("onEmbeddedAudioSelected ="))
    }
    @Test
    fun englishVoiceIsNotOfferedAnywhereInPlaybackUi() {
        val profile = File("src/main/java/app/movia/android/ui/profile/ProfileScreen.kt").readText()
        val settings = File("src/main/java/app/movia/android/ui/settings/PlaybackSettingsScreen.kt").readText()
        val catalog = File("src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt").readText()
        val details = File("src/main/java/app/movia/android/ui/details/DetailsScreen.kt").readText()
        assertFalse(profile.contains("\"Original\""))
        assertFalse(settings.contains("\"Original\""))
        assertFalse(catalog.contains("listOf<String?>(null, \"Русский\", \"Original\")"))
        assertTrue(catalog.contains("listOf<String?>(null, \"Русский\", \"Украинский\")"))
        assertFalse(details.contains("\"Оригинал\""))
        assertFalse(details.contains("StreamQualityAudioSheet"))
        assertFalse(details.contains("val qualities = listOf(\"1080p\", \"720p\", \"480p\", \"4K\")"))
    }

}
