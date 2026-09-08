package app.movia.android.network

import app.movia.android.BuildConfig
import java.net.URI

object ControlPlaneEndpoint {
    const val LOCAL_BASE_URL = "http://127.0.0.1:8888"

    val baseUrl: String by lazy {
        normalize(BuildConfig.MOVIA_CONTROL_PLANE_URL)
    }

    internal fun normalize(raw: String?): String {
        val value = raw.orEmpty().trim().trimEnd('/')
        if (value.isBlank()) return LOCAL_BASE_URL
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            return LOCAL_BASE_URL
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty()
        if (
            host.isBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            (path.isNotBlank() && path != "/")
        ) {
            return LOCAL_BASE_URL
        }
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        if (loopback) {
            if (scheme != "http" && scheme != "https") return LOCAL_BASE_URL
        } else if (scheme != "https") {
            return LOCAL_BASE_URL
        }
        return value
    }
}
