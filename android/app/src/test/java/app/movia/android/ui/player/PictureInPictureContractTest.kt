package app.movia.android.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureInPictureContractTest {
    @Test
    fun pipIsCleanSixteenByNineWithoutDuplicateChrome() {
        val source = java.io.File("src/main/java/app/movia/android/ui/player/PictureInPictureSupport.kt").readText()

        assertTrue(source.contains("setAspectRatio(Rational(16, 9))"))
        assertTrue(source.contains("setAutoEnterEnabled(autoEnter)"))
        assertFalse(source.contains(".setTitle("))
        assertFalse(source.contains(".setSubtitle("))
        assertFalse(source.contains("setActions("))
        assertFalse(source.contains("RemoteAction"))
    }

    @Test
    fun playbackArtworkNeverFallsBackToVerticalPoster() {
        val appSource = java.io.File("src/main/java/app/movia/android/ui/MoviaApp.kt").readText()
        val agentSource = java.io.File("src/main/java/app/movia/android/agent/AgentControlRuntime.kt").readText()

        assertTrue(appSource.contains("backdrop.isNotBlank() && backdrop != content.posterUrl"))
        assertTrue(agentSource.contains("backdrop.isNotBlank() && backdrop != content.posterUrl"))
        assertFalse(appSource.contains("artworkUrl = content?.posterUrl ?: content?.backdropUrl"))
        assertFalse(agentSource.contains("artworkUrl = content.posterUrl ?: content.backdropUrl"))
    }
}
