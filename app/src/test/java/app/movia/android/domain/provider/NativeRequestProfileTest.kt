package app.movia.android.domain.provider

import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamRequestProfile
import org.junit.Assert.*
import org.junit.Test

class NativeRequestProfileTest {
    private fun source() = StreamCandidate(
        stableStreamId = "movia:one", provider = "Native", providerId = "movia:hdrezka",
        url = "http://stream.voidboost.one/master.m3u8",
        headers = mapOf("Cookie" to "source_session=fixture", "Referer" to "https://provider.example/"),
        userAgent = "Movia-source-fixture",
    )

    @Test fun ownedProviderUsesPublicMediaTransportWithoutReferenceMarker() {
        val candidate = source()
        val profile = StreamRequestProfile.from(candidate, candidate.url)
        assertTrue(profile.publicNetworkOnly)
        assertEquals("Movia-source-fixture", profile.userAgent)
        assertEquals("source_session=fixture", profile.headersFor(candidate.url)["Cookie"])
        assertFalse(profile.headersFor("https://other.example/a").keys.any { it.equals("Cookie", true) })
    }

    @Test fun localGatewayDoesNotReceiveProviderCredentials() {
        val profile = StreamRequestProfile.from(source(), "http://127.0.0.1:8888/stream")
        assertFalse(profile.publicNetworkOnly)
        assertFalse(profile.headersFor("http://127.0.0.1:8888/stream").keys.any {
            it.equals("Cookie", true) || it.equals("Referer", true)
        })
    }
}
