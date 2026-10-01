package app.movia.android.domain.playback

import java.net.URI

/**
 * The request contract consumed by Media3 for one logical stream.
 *
 * Application credentials are excluded. Original provider cookies may only
 * reach the exact public media origin for which the native engine supplied them.
 */
data class StreamRequestProfile(
    val userAgent: String = DEFAULT_STREAM_USER_AGENT,
    val headers: Map<String, String> = emptyMap(),
    val publicNetworkOnly: Boolean = false,
    private val providerCookie: String? = null,
    private val providerCookieOrigin: String? = null,
) {
    /** Remove browser-origin headers when a stream is consumed by our gateway. */
    fun headersFor(uri: String): Map<String, String> {
        val safe = headers.toMutableMap()
        safe.entries.removeAll { it.key.equals("cookie", ignoreCase = true) }
        if (providerCookie != null && !isLoopbackUri(uri) && origin(uri) == providerCookieOrigin) {
            safe["Cookie"] = providerCookie
        }
        if (isLoopbackUri(uri)) {
            safe.entries.removeAll { it.key.equals("referer", ignoreCase = true) ||
                it.key.equals("origin", ignoreCase = true) }
        }
        return safe
    }

    companion object {
        fun from(candidate: StreamCandidate, consumedUri: String): StreamRequestProfile {
            val candidateHeaders = sanitizeHeaders(candidate.headers)
            val headerUserAgent = candidateHeaders.entries
                .firstOrNull { it.key.equals("user-agent", ignoreCase = true) }
                ?.value
            val effectiveUserAgent = sanitizeUserAgent(candidate.userAgent)
                ?: headerUserAgent
                ?: DEFAULT_STREAM_USER_AGENT

            val profile = StreamRequestProfile(
                userAgent = effectiveUserAgent,
                headers = candidateHeaders.filterKeys {
                    !it.equals("user-agent", ignoreCase = true)
                },
                publicNetworkOnly = candidate.transportMetadata["legacy_engine"] == "3.466" && !isLoopbackUri(consumedUri),
                providerCookie = if (candidate.transportMetadata["legacy_engine"] == "3.466") {
                    candidate.headers.entries.firstOrNull { it.key.equals("cookie", true) }?.value?.takeIf {
                        it.isNotBlank() && it.length <= 8192 && '\r' !in it && '\n' !in it && '\u0000' !in it
                    }
                } else null,
                providerCookieOrigin = origin(candidate.url),
            )
            return profile.copy(headers = profile.headersFor(consumedUri).filterKeys { !it.equals("cookie", true) })
        }

        internal fun sameOrigin(first: String, second: String): Boolean {
            val value = origin(first) ?: return false
            return value == origin(second)
        }

        private fun origin(value: String): String? = runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase() ?: return@runCatching null
            if (scheme !in setOf("http", "https") || uri.rawUserInfo != null) return@runCatching null
            val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
            "$scheme://$host:$port"
        }.getOrNull()

        internal const val DEFAULT_STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

        private const val MAX_HEADER_NAME_LENGTH = 128
        private const val MAX_HEADER_VALUE_LENGTH = 2048

        private val allowedHeaderNames = setOf(
            "accept",
            "accept-language",
            "cache-control",
            "content-type",
            "if-modified-since",
            "if-none-match",
            "origin",
            "range",
            "referer",
            "sec-fetch-dest",
            "sec-fetch-mode",
            "sec-fetch-site",
            "user-agent",
            "x-requested-with",
        )

        private fun sanitizeUserAgent(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            return value.takeIf {
                it.isNotBlank() &&
                    it.length <= MAX_HEADER_VALUE_LENGTH &&
                    !it.contains('\r') &&
                    !it.contains('\n')
            }
        }

        private fun sanitizeHeaders(raw: Map<String, String>): Map<String, String> {
            val result = linkedMapOf<String, String>()
            raw.forEach { (rawName, rawValue) ->
                val name = rawName.trim()
                val value = rawValue.trim()
                if (name.length !in 1..MAX_HEADER_NAME_LENGTH ||
                    value.isBlank() ||
                    value.length > MAX_HEADER_VALUE_LENGTH ||
                    name.lowercase() !in allowedHeaderNames ||
                    name.any { it == '\r' || it == '\n' } ||
                    value.any { it == '\r' || it == '\n' }
                ) return@forEach
                result[name] = value
            }
            if (result.none { it.key.equals("accept", ignoreCase = true) }) {
                result["Accept"] = "*/*"
            }
            return result
        }

        private fun isLoopbackUri(value: String): Boolean {
            val host = runCatching { URI(value).host?.lowercase()?.removeSurrounding("[", "]") }.getOrNull()
                ?: return false
            return host == "localhost" || host == "::1" || host == "127.0.0.1" ||
                host.startsWith("127.")
        }
    }
}
