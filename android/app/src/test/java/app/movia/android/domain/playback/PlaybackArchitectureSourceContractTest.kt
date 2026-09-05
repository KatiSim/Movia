package app.movia.android.domain.playback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArchitectureSourceContractTest {
    private fun source(relative: String): String = File(relative).readText()
    private fun assertContains(haystack: String, needle: String) = assertTrue(haystack.contains(needle))

    @Test
    fun uiUsesRegistryOwnedPlaybackSession() {
        val moviaApp = source("src/main/java/app/movia/android/ui/MoviaApp.kt")
        assertContains(moviaApp, "MoviaPlaybackRegistry.obtain(context.applicationContext)")
        assertFalse(moviaApp.contains("PlaybackSession(context.applicationContext)"))
        assertFalse(moviaApp.contains("onDispose { playbackSession.release() }"))
    }

    @Test
    fun cacheLookupsDoNotPerformImplicitFullNetworkFetches() {
        val repository = source("src/main/java/app/movia/android/data/catalog/CatalogRepository.kt")
        val titleStart = repository.indexOf("override fun findByTitle(title: String)")
        val idStart = repository.indexOf("override fun findById(id: String)", titleStart)
        val fullStart = repository.indexOf("override fun findFullById", idStart)
        assertTrue(titleStart >= 0 && idStart > titleStart && fullStart > idStart)
        val cacheOnlyBlock = repository.substring(titleStart, fullStart)
        assertFalse(cacheOnlyBlock.contains("findFullBy"))
        assertFalse(cacheOnlyBlock.contains("httpGet("))
    }

    @Test
    fun visiblePlayerCacheLookupOccursBeforeNoImplicitNetworkBoundary() {
        val player = source("src/main/java/app/movia/android/ui/player/PlayerScreen.kt")
        assertContains(player, "DemoCatalogRepository.findByTitle(baseTitle)")
        val repository = source("src/main/java/app/movia/android/data/catalog/CatalogRepository.kt")
        assertContains(repository, "UI composition and player surface attachment must")
    }

    @Test
    fun streamSettingsUseSessionVariantsNotCatalogOrContainerTrackLabels() {
        val player = source("src/main/java/app/movia/android/ui/player/PlayerScreen.kt")
        assertContains(player, "val sessionStreams by session.streamOptions.collectAsState()")
        assertContains(player, "val contentStreams = sessionStreams.filter { it.url.isNotBlank() }")
        assertContains(player, "session.switchToStream(matchedStream, session.state.value.currentPositionMs)")
        assertFalse(player.contains("val contentStreams = mediaContent?.streams.orEmpty()"))
        assertFalse(player.contains("selectAudio(newVoice)"))
        assertFalse(player.contains("selectQuality(newQuality)"))
    }

    @Test
    fun uiPreservesProviderOrderForResolver() {
        val moviaApp = source("src/main/java/app/movia/android/ui/MoviaApp.kt")
        assertContains(moviaApp, "val knownStreams = content.streams.filter")
        assertContains(moviaApp, "candidateStreamOptions = knownStreams")
        assertFalse(moviaApp.contains("val sortedStreams ="))
    }
}
