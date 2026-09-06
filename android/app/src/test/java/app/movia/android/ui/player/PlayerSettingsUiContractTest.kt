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
}
