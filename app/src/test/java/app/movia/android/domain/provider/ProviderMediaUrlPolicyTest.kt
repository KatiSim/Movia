package app.movia.android.domain.provider

import org.junit.Assert.*
import org.junit.Test

class ProviderMediaUrlPolicyTest {
    @Test fun validExternalTransportsAreRetained() {
        listOf("https://cdn.example/movie.mp4?token=one", "http://cdn.example/master.m3u8",
            "magnet:?xt=urn:btih:" + "a".repeat(40) + "&tr=https%3A%2F%2Ftracker.example").forEach {
            assertTrue(it, ProviderMediaUrlPolicy.isMediaUrl(it))
        }
    }

    @Test fun providerCannotReadAppFilesOrExecuteScripts() {
        listOf("file:///data/data/app.movia.android/files/token", "javascript:alert(1)",
            "content://app.movia.android/private", "https://user:pass@cdn.example/movie.mp4",
            "https://cdn.example/movie.mp4\nInjected: true").forEach {
            assertFalse(it, ProviderMediaUrlPolicy.isMediaUrl(it))
        }
    }

    @Test fun privateAndAlternateNumericAddressesAreRejected() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.1.1", "169.254.1.2",
            "100.64.1.1", "localhost", "localhost.", "api.localhost", "2130706433",
            "0177.0.0.1", "0x7f.0.0.1", "[::1]", "[fc00::1]", "[::ffff:127.0.0.1]").forEach {
            assertFalse(it, ProviderMediaUrlPolicy.isMediaUrl("https://$it/movie.mp4"))
        }
    }

    @Test fun malformedLocatorsAreRejected() {
        listOf("", "https:///movie.mp4", "magnet:?xt=urn:btih:invalid",
            "https://cdn.example:0/movie.mp4", "https://cdn.example:99999/movie.mp4").forEach {
            assertFalse(it, ProviderMediaUrlPolicy.isMediaUrl(it))
        }
    }
}
