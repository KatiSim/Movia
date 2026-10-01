package app.movia.android.domain.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRequestProfileTest {
    private fun native(headers: Map<String,String> = mapOf("Cookie" to "source_session=ok")) = StreamCandidate(
        stableStreamId="legacy:21:fixture",provider="hdrezka",url="https://media.example/video.m3u8",headers=headers,
        transportMetadata=mapOf("legacy_engine" to "3.466"))
    @Test fun webCookieFallbackRequiresTheExactOrigin() {
        assertTrue(StreamRequestProfile.sameOrigin("https://Media.example/embed", "https://media.example:443/video.m3u8"))
        for(other in listOf("http://media.example/a", "https://media.example:444/a", "https://other.example/a", "file:///a"))
            assertFalse(other, StreamRequestProfile.sameOrigin("https://media.example/embed", other))
        assertFalse(StreamRequestProfile.sameOrigin("invalid", "invalid"))
    }
    @Test fun originalProviderCookieReachesItsMediaOrigin() {
        val source=native();val profile=StreamRequestProfile.from(source,source.url)
        assertEquals("source_session=ok",profile.headersFor("https://media.example:443/segment.ts")["Cookie"])
        assertTrue(profile.publicNetworkOnly)
    }
    @Test fun providerCookieNeverReachesAnotherOrigin() {
        val source=native();val profile=StreamRequestProfile.from(source,source.url)
        for(url in listOf("https://other.example/segment.ts","http://media.example/segment.ts","https://media.example:444/segment.ts"))
            assertFalse(profile.headersFor(url).keys.any { it.equals("Cookie",true) })
    }
    @Test fun providerCookieAndOriginNeverReachTheLocalGateway() {
        val source=native(mapOf("Cookie" to "source=ok","Referer" to "https://provider.example/"))
        val profile=StreamRequestProfile.from(source,"http://127.0.0.1:8888/stream")
        assertFalse(profile.publicNetworkOnly)
        assertFalse(profile.headersFor("http://127.0.0.1:8888/stream").keys.any { it.equals("Cookie",true)||it.equals("Referer",true) })
    }
    @Test fun injectedCookieAndApplicationAuthorizationAreRejected() {
        val source=native(mapOf("Cookie" to "bad\r\nAuthorization: private","Authorization" to "Bearer private"))
        val headers=StreamRequestProfile.from(source,source.url).headersFor(source.url)
        assertFalse(headers.keys.any { it.equals("Cookie",true)||it.equals("Authorization",true) })
    }
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
