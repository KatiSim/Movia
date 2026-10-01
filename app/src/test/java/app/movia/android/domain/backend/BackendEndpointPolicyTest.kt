package app.movia.android.domain.backend

import org.junit.Assert.*
import org.junit.Test

class BackendEndpointPolicyTest {
    @Test fun acceptsHttpsServerAndExplicitLocalGateway() {
        assertEquals("https://api.movia.example", BackendEndpointPolicy.validate("https://api.movia.example/"))
        assertEquals("http://127.0.0.1:8888", BackendEndpointPolicy.validate("http://127.0.0.1:8888"))
    }
    @Test fun rejectsCredentialsPathsQueriesAndFragments() {
        for (value in listOf("https://user:password@api.movia.example", "https://api.movia.example/admin",
            "https://api.movia.example?token=private", "https://api.movia.example#config")) {
            assertNull(value, BackendEndpointPolicy.validate(value))
        }
    }
    @Test fun rejectsInsecureAndUnintendedInternalOrigins() {
        for (value in listOf("http://api.movia.example", "https://127.0.0.1", "https://[::1]",
            "http://127.0.0.1:8899", "https://router.local", "https://api.localhost", "https://host.internal",
            "https://api.movia.example\n", "https://api.movia.example:8080", "file:///private")) {
            assertNull(value, BackendEndpointPolicy.validate(value))
        }
    }
}
