package app.movia.android.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ControlPlaneEndpointTest {
    @Test
    fun `default and local loopback stay valid`() {
        assertEquals(
            ControlPlaneEndpoint.LOCAL_BASE_URL,
            ControlPlaneEndpoint.normalize("http://127.0.0.1:8888/"),
        )
        assertEquals(
            "http://localhost:8888",
            ControlPlaneEndpoint.normalize("http://localhost:8888"),
        )
    }

    @Test
    fun `remote control plane requires https`() {
        assertEquals(
            "https://staging.movia.example",
            ControlPlaneEndpoint.normalize("https://staging.movia.example/"),
        )
        assertEquals(
            ControlPlaneEndpoint.LOCAL_BASE_URL,
            ControlPlaneEndpoint.normalize("http://staging.movia.example"),
        )
    }

    @Test
    fun `invalid or credentialed endpoint fails safe to local`() {
        listOf(
            "",
            "not a url",
            "ftp://staging.movia.example",
            "https://user:pass@staging.movia.example",
            "https://staging.movia.example?token=x",
            "https://staging.movia.example#fragment",
            "https://staging.movia.example/api",
        ).forEach { value ->
            assertEquals(
                "value=$value",
                ControlPlaneEndpoint.LOCAL_BASE_URL,
                ControlPlaneEndpoint.normalize(value),
            )
        }
    }
}
