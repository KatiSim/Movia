package app.movia.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoviaArtworkTest {
    @Test
    fun relativeTmdbPathUsesSizedImageUrl() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            normalizeMoviaArtworkUrl("/poster.jpg"),
        )
    }

    @Test
    fun absoluteAndProtocolRelativeUrlsArePreserved() {
        assertEquals(
            "https://cdn.example/poster.jpg",
            normalizeMoviaArtworkUrl(" https://cdn.example/poster.jpg "),
        )
        assertEquals(
            "//cdn.example/poster.jpg",
            normalizeMoviaArtworkUrl("//cdn.example/poster.jpg"),
        )
    }

    @Test
    fun blankUrlHasNoArtworkRequest() {
        assertNull(normalizeMoviaArtworkUrl("  "))
        assertNull(normalizeMoviaArtworkUrl(null))
    }
}
