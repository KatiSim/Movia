package app.movia.android.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRequestProfileTest {
    @Test
    fun profileUsesCandidateHeadersAndUserAgentWithoutHostnameInference() {
        val candidate = StreamCandidate(
            stableStreamId = "stream:one",
            provider = "provider",
            url = "https://cdn.example/video.m3u8",
            userAgent = "candidate-agent",
            headers = mapOf(
                "Referer" to "https://authorized.example/",
                "Origin" to "https://authorized.example",
                "Cookie" to "must-not-cross-boundary",
                "X-Provider-Secret" to "must-not-cross-boundary",
            ),
        )

        val profile = StreamRequestProfile.from(candidate, candidate.url)

        assertEquals("candidate-agent", profile.userAgent)
        assertEquals("https://authorized.example/", profile.headers["Referer"])
        assertEquals("https://authorized.example", profile.headers["Origin"])
        assertFalse(profile.headers.keys.any { it.equals("cookie", ignoreCase = true) })
        assertFalse(profile.headers.containsKey("X-Provider-Secret"))
    }

    @Test
    fun loopbackGatewayDoesNotReceiveExternalOriginHeaders() {
        val candidate = StreamCandidate(
            stableStreamId = "stream:p2p",
            provider = "torrent",
            url = "magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            headers = mapOf(
                "Referer" to "https://provider.example/",
                "Origin" to "https://provider.example",
            ),
        )

        val profile = StreamRequestProfile.from(
            candidate,
            "http://127.0.0.1:8888/stream?magnet=encoded&format=raw",
        )

        assertTrue(profile.headers.keys.none { it.equals("referer", ignoreCase = true) })
        assertTrue(profile.headers.keys.none { it.equals("origin", ignoreCase = true) })
        assertEquals("*/*", profile.headers["Accept"])
    }
}
