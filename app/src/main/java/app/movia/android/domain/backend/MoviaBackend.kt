package app.movia.android.domain.backend

import app.movia.android.BuildConfig
import java.net.URI

/** Catalog/control plane is configurable independently from the optional local P2P gateway. */
object MoviaBackend {
    const val localGatewayOrigin = "http://127.0.0.1:8888"
    val apiOrigin: String by lazy {
        BackendEndpointPolicy.validate(BuildConfig.MOVIA_API_BASE_URL)
            ?: error("Invalid Movia API origin")
    }
}

internal object BackendEndpointPolicy {
    fun validate(raw: String): String? = runCatching {
        if (raw.length > 512 || raw.any { it.isWhitespace() || it.code < 32 }) return null
        val uri = URI(raw)
        val host = uri.host?.lowercase() ?: return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null ||
            uri.rawPath !in listOf("", "/")) return null
        if (host == "127.0.0.1") {
            if (uri.scheme != "http" || uri.port != 8888) return null
        } else {
            if (uri.scheme != "https" || uri.port !in listOf(-1, 443)) return null
            if (!host.contains('.') || !host.any { it in 'a'..'z' } ||
                host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                host.endsWith(".internal") || host.contains(':')) return null
        }
        raw.removeSuffix("/")
    }.getOrNull()
}
