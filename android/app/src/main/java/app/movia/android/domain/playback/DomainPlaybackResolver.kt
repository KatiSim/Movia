package app.movia.android.domain.playback

import android.util.Log
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.StreamAdvertisement
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.StreamSkipInterval
import app.movia.android.domain.model.StreamSubtitle
import app.movia.android.domain.model.sameRequestedVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

sealed class PlaybackResolverResult {
    data class Success(val candidates: List<StreamCandidate>) : PlaybackResolverResult()
    data class NoSource(val reason: String = "Источники не найдены") : PlaybackResolverResult()
    data class Error(val message: String, val cause: Throwable? = null) : PlaybackResolverResult()
}

/** Response returned by one bounded backend discovery route. */
data class PlaybackResolverBackendResponse(
    val candidates: List<StreamCandidate> = emptyList(),
    val errorCode: String? = null,
)

/**
 * Backend seam for resolver tests and for keeping transport concerns out of
 * candidate selection. The production implementation is the loopback HTTP
 * client below; tests can provide deterministic responses without a network.
 */
interface PlaybackResolverBackend {
    suspend fun resolveByIdentity(
        request: PlaybackRequest,
        forceRefresh: Boolean,
    ): PlaybackResolverBackendResponse

    suspend fun resolveByTitle(
        request: PlaybackRequest,
        forceRefresh: Boolean,
    ): PlaybackResolverBackendResponse
}

object DomainPlaybackResolver {
    private const val TAG = "DomainPlaybackResolver"
    private const val BASE_BACKEND_URL = "http://127.0.0.1:8888"
    private const val DISCOVERY_TIMEOUT_MS = 15_000L

    private val httpBackend = object : PlaybackResolverBackend {
        override suspend fun resolveByIdentity(
            request: PlaybackRequest,
            forceRefresh: Boolean,
        ): PlaybackResolverBackendResponse = queryBackendStreamEndpoint(
            request.mediaId,
            request.seasonNumber,
            request.episodeNumber,
            forceRefresh,
        )

        override suspend fun resolveByTitle(
            request: PlaybackRequest,
            forceRefresh: Boolean,
        ): PlaybackResolverBackendResponse = queryBackendResolveEndpoint(
            request.title,
            request.year,
            request.mediaType,
            request.seasonNumber,
            request.episodeNumber,
            forceRefresh,
        )
    }

    private fun normalizedTitle(value: String): String = value.trim().lowercase()
        .replace('ё', 'е')
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun isStructurallyPlayableUrl(rawUrl: String): Boolean {
        val value = rawUrl.trim()
        if (value.isBlank()) return false
        if (value.startsWith("magnet:?", ignoreCase = true)) {
            val hash = Regex("(?:^|[?&])xt=urn:btih:([^&\\s]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.getOrNull(1).orEmpty()
            return Regex("^[0-9A-Fa-f]{40}$").matches(hash) ||
                Regex("^[A-Z2-7]{32}$", RegexOption.IGNORE_CASE).matches(hash)
        }
        val lower = value.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return value.substringAfter("://", "")
                .substringBefore('/')
                .substringBefore('?')
                .substringBefore('#')
                .isNotBlank()
        }
        return lower.startsWith("file://") && value.substringAfter("://", "").isNotBlank()
    }

    private fun parseHeaders(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        val allowed = setOf(
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
        val result = linkedMapOf<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val headerValue = value.optString(name).trim()
            if (name.trim().lowercase() in allowed &&
                headerValue.isNotBlank() &&
                headerValue.length <= 2048 &&
                !headerValue.contains("\r") &&
                !headerValue.contains("\n")
            ) {
                result[name] = headerValue
            }
        }
        return result
    }

    private fun parseSubtitles(value: JSONArray?): List<StreamSubtitle> {
        if (value == null) return emptyList()
        val result = mutableListOf<StreamSubtitle>()
        for (i in 0 until minOf(value.length(), 16)) {
            val obj = value.optJSONObject(i) ?: continue
            val url = obj.optString("url").ifBlank {
                obj.optString("uri").ifBlank { obj.optString("src") }
            }.trim()
            if (!url.startsWith("http://", ignoreCase = true) &&
                !url.startsWith("https://", ignoreCase = true)
            ) continue
            result.add(
                StreamSubtitle(
                    url = url,
                    language = obj.optString("language").ifBlank { obj.optString("lang", "ru") },
                    label = obj.optString("label").ifBlank { obj.optString("name", "Русские") },
                    mimeType = obj.optString("mime_type").ifBlank {
                        obj.optString("mimeType", "text/vtt")
                    },
                )
            )
        }
        return result
    }

    private fun parseStringMap(value: JSONObject?): Map<String, String> {
        if (value == null) return emptyMap()
        val result = linkedMapOf<String, String>()
        val keys = value.keys()
        while (keys.hasNext()) {
            val key = keys.next().trim()
            val item = value.optString(key).trim()
            if (key.isBlank() || item.isBlank() || key.length > 128 || item.length > 2048) continue
            if (key.contains('\n') || key.contains('\r') || item.contains('\n') || item.contains('\r')) continue
            if (Regex("(?i)(token|authorization|cookie|password|secret|signature|private[_-]?key)").containsMatchIn(key)) continue
            result[key] = item
        }
        return result
    }

    private fun parseSkipIntervals(value: JSONArray?): List<StreamSkipInterval> {
        if (value == null) return emptyList()
        val result = mutableListOf<StreamSkipInterval>()
        for (i in 0 until minOf(value.length(), 32)) {
            val item = value.optJSONObject(i) ?: continue
            val start = item.optLong("startMs", item.optLong("start_ms", item.optLong("start", -1L)))
            val end = item.optLong("endMs", item.optLong("end_ms", item.optLong("end", -1L)))
            if (start >= 0L && end >= start && end <= 86_400_000L) {
                result += StreamSkipInterval(start, end)
            }
        }
        return result
    }

    private fun parseAdvertisement(value: Any?): StreamAdvertisement? {
        if (value == null || value == JSONObject.NULL) return null
        val raw = when (value) {
            is JSONObject -> value.toString()
            else -> value.toString()
        }.trim()
        if (raw.isBlank() || raw.length > 4096) return null
        return if (value is JSONObject) {
            StreamAdvertisement(raw = raw, metadata = parseStringMap(value))
        } else {
            StreamAdvertisement(raw = raw)
        }
    }

    private fun parseSafeReloadData(value: Any?): String? {
        if (value == null || value == JSONObject.NULL) return null
        val raw = when (value) {
            is JSONObject -> value.toString()
            is JSONArray -> value.toString()
            else -> value.toString()
        }.trim()
        if (raw.isBlank() || raw.length > 4096) return null
        if (Regex("(?i)(token|authorization|cookie|password|secret|signature|private[_-]?key)").containsMatchIn(raw)) return null
        return raw
    }

    private fun firstString(obj: JSONObject, vararg keys: String): String? =
        keys.asSequence().map { obj.optString(it).trim() }.firstOrNull { it.isNotBlank() }

    private fun transportFor(url: String, declared: String?): String {
        if (!declared.isNullOrBlank()) return declared.trim()
        return when {
            url.startsWith("magnet:", ignoreCase = true) -> "torrent_p2p"
            url.contains("/stream?", ignoreCase = true) -> "local_gateway"
            url.contains(".m3u8", ignoreCase = true) -> "hls"
            url.contains(".mpd", ignoreCase = true) -> "dash"
            else -> "direct"
        }
    }

    private fun resolutionDimension(value: String?, width: Boolean): Int? {
        val raw = value?.trim().orEmpty()
        val match = Regex("(\\d{2,5})\\s*[xх×]\\s*(\\d{2,5})").find(raw) ?: return null
        return match.groupValues[if (width) 1 else 2].toIntOrNull()
    }

    private fun parseCandidateArray(
        arr: JSONArray?,
        season: Int?,
        episode: Int?,
    ): List<StreamCandidate> {
        if (arr == null) return emptyList()
        val result = mutableListOf<StreamCandidate>()
        for (i in 0 until arr.length()) {
            val sObj = arr.optJSONObject(i) ?: continue
            val url = firstString(sObj, "url", "playback_url", "stream_url").orEmpty()
            val source = sObj.optString("source").takeIf { it.isNotBlank() } ?: "resolver"
            if (!isStructurallyPlayableUrl(url)) continue
            if (url.contains("archive.org") || url.contains("themoviedb.org")) continue
            val subtitles = parseSubtitles(
                sObj.optJSONArray("subtitle_list")
                    ?: sObj.optJSONArray("subtitleList")
                    ?: sObj.optJSONArray("subtitles")
            )
            val headers = parseHeaders(
                sObj.optJSONObject("headers")
                    ?: sObj.optJSONObject("http_headers")
                    ?: sObj.optJSONObject("httpHeaders")
            )
            val userAgent = sObj.optString("user_agent").takeIf { it.isNotBlank() }
                ?: sObj.optString("userAgent").takeIf { it.isNotBlank() }
            val videoTrackIndex = sObj.optInt("video_track_index", -1).takeIf { it >= 0 }
                ?: sObj.optInt("videoTrackIndex", -1).takeIf { it >= 0 }
            val audioTrackIndex = sObj.optInt("audio_track_index", -1).takeIf { it >= 0 }
                ?: sObj.optInt("audioTrackIndex", -1).takeIf { it >= 0 }
            val durationMs = sObj.optLong("duration_ms", 0L).takeIf { it > 0L }
                ?: sObj.optLong("duration", 0L).takeIf { it > 0L }
            val sizeBytes = sObj.optLong("size_bytes", 0L).takeIf { it > 0L }
                ?: sObj.optLong("size", 0L).takeIf { it > 0L }
            val resolution = firstString(sObj, "resolution", "video_resolution", "videoResolution")
            val transport = transportFor(url, firstString(sObj, "transport"))
            val option = StreamOption(
                voice = firstString(sObj, "voice", "translation") ?: "Не указано",
                quality = firstString(sObj, "quality") ?: "Не указано",
                seeders = sObj.optInt("seeders", sObj.optInt("seeds", 0)),
                url = url,
                source = source,
                streamId = sObj.optString("stream_id").takeIf { it.isNotBlank() }
                    ?: sObj.optString("streamId").takeIf { it.isNotBlank() }.orEmpty(),
                logicalSourceId = firstString(sObj, "logical_source_id", "logicalSourceId"),
                providerItemId = sObj.optString("provider_item_id").takeIf { it.isNotBlank() }
                    ?: sObj.optString("providerItemId").takeIf { it.isNotBlank() },
                sourceTypeId = sObj.optInt("source_type_id", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("video_source_type_id", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("videoSourceTypeId", -1).takeIf { it >= 0 },
                contentTypeId = sObj.optInt("content_type_id", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("video_content_type_id", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("videoContentTypeId", -1).takeIf { it >= 0 },
                sourceId = firstString(sObj, "source_id", "sourceId"),
                providerId = firstString(sObj, "provider_id", "providerId"),
                providerContentId = firstString(sObj, "provider_content_id", "providerContentId"),
                infoHash = sObj.optString("info_hash").takeIf { it.isNotBlank() }
                    ?: sObj.optString("infoHash").takeIf { it.isNotBlank() },
                fileIndex = sObj.optInt("file_index", -1).takeIf { it >= 0 }
                    ?: sObj.optInt("fileIndex", -1).takeIf { it >= 0 },
                filePath = sObj.optString("file_path").takeIf { it.isNotBlank() }
                    ?: sObj.optString("filePath").takeIf { it.isNotBlank() },
                seasonNumber = sObj.optInt("season", -1).takeIf { it > 0 } ?: season,
                episodeNumber = sObj.optInt("episode", -1).takeIf { it > 0 } ?: episode,
                mimeType = sObj.optString("mime_type").takeIf { it.isNotBlank() }
                    ?: sObj.optString("mimeType").takeIf { it.isNotBlank() },
                drmScheme = sObj.optString("drm_scheme").takeIf { it.isNotBlank() }
                    ?: sObj.optString("drmScheme").takeIf { it.isNotBlank() },
                drmLicenseUrl = sObj.optString("license_url").takeIf { it.isNotBlank() }
                    ?: sObj.optString("drm_license_url").takeIf { it.isNotBlank() }
                    ?: sObj.optString("drmLicenseUrl").takeIf { it.isNotBlank() },
                language = sObj.optString("language", "ru"),
                codec = sObj.optString("codec").takeIf { it.isNotBlank() },
                userAgent = userAgent,
                headers = headers,
                subtitles = subtitles,
                hasInternalSubtitles = sObj.optBoolean("is_use_internal_subtitles", false) ||
                    sObj.optBoolean("isUseInternalSubtitles", false),
                videoTrackIndex = videoTrackIndex,
                audioTrackIndex = audioTrackIndex,
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                reloadSupported = sObj.optBoolean("reloadSupported", false) ||
                    sObj.optBoolean("reload_supported", false) ||
                    sObj.has("reload_data") || sObj.has("reloadData"),
                transport = transport,
                transportMetadata = parseStringMap(
                    sObj.optJSONObject("transport_metadata")
                        ?: sObj.optJSONObject("transportMetadata")
                ),
                resolution = resolution,
                resolutionWidth = sObj.optInt("resolution_width", -1).takeIf { it > 0 }
                    ?: sObj.optInt("resolutionWidth", -1).takeIf { it > 0 }
                    ?: resolutionDimension(resolution, width = true),
                resolutionHeight = sObj.optInt("resolution_height", -1).takeIf { it > 0 }
                    ?: sObj.optInt("resolutionHeight", -1).takeIf { it > 0 }
                    ?: resolutionDimension(resolution, width = false),
                unavailableQuality = sObj.optBoolean("unavailable_quality", false) ||
                    sObj.optBoolean("unavailableQuality", false),
                isTrailer = sObj.optBoolean("is_trailer", false) ||
                    sObj.optBoolean("isTrailer", false),
                downloadUrl = firstString(sObj, "download_url", "downloadUrl")
                    ?.takeIf(::isStructurallyPlayableUrl),
                downloadHeaders = parseStringMap(
                    sObj.optJSONObject("download_headers")
                        ?: sObj.optJSONObject("downloadHeaders")
                ),
                skipIntervals = parseSkipIntervals(
                    sObj.optJSONArray("skip_intervals")
                        ?: sObj.optJSONArray("skipIntervals")
                ),
                advertisement = parseAdvertisement(sObj.opt("advertisement") ?: sObj.opt("ad")),
                reloadData = parseSafeReloadData(sObj.opt("reload_data") ?: sObj.opt("reloadData")),
                catalogMediaId = firstString(sObj, "catalog_media_id", "catalogMediaId"),
                canonicalTitle = firstString(sObj, "canonical_title", "canonicalTitle"),
                canonicalOriginalTitle = firstString(
                    sObj,
                    "canonical_original_title",
                    "canonicalOriginalTitle",
                ),
                canonicalYear = sObj.optInt("canonical_year", -1).takeIf { it > 0 }
                    ?: sObj.optInt("canonicalYear", -1).takeIf { it > 0 },
                canonicalMediaType = firstString(sObj, "canonical_media_type", "canonicalMediaType")
                    ?.trim()
                    ?.lowercase()
                    ?.let { raw ->
                        when (raw) {
                            "tv", "series", "serial", "tv_series", "limited_series" -> ContentType.SERIES
                            "tv_channel", "channel" -> ContentType.TV
                            else -> ContentType.MOVIE
                        }
                    },
                healthScore = sObj.optDouble("health_score", sObj.optDouble("healthScore", 0.5))
                    .coerceIn(0.0, 1.0),
                startupLatencyMs = when {
                    sObj.has("startup_latency_ms") && !sObj.isNull("startup_latency_ms") ->
                        sObj.optLong("startup_latency_ms", 0L).coerceAtLeast(0L)
                    sObj.has("startupLatencyMs") && !sObj.isNull("startupLatencyMs") ->
                        sObj.optLong("startupLatencyMs", 0L).coerceAtLeast(0L)
                    else -> null
                },
                recentFailureCount = sObj.optInt("recent_failure_count", sObj.optInt("recentFailureCount", 0))
                    .coerceAtLeast(0),
                providerReliability = sObj.optDouble("provider_reliability", Double.NaN)
                    .takeUnless { it.isNaN() }?.coerceIn(0.0, 1.0),
            )
            result.add(StreamCandidate.fromStreamOption(option, season, episode))
        }
        return result
    }

    private suspend fun queryBackendStreamEndpoint(
        mediaId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
    ): PlaybackResolverBackendResponse = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(mediaId, "UTF-8")
            val sParam = if (season != null) "?season=$season" else ""
            val eParam = if (episode != null) "${if (sParam.isEmpty()) "?" else "&"}episode=$episode" else ""
            val rParam = "${if (sParam.isEmpty() && eParam.isEmpty()) "?" else "&"}refresh=${if (forceRefresh) 1 else 0}"
            val endpointUrl = "$BASE_BACKEND_URL/api/movie/$encodedId/stream$sParam$eParam$rParam"

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    return@withContext PlaybackResolverBackendResponse(errorCode = "BACKEND_HTTP_$code")
                }
                if (body.isBlank()) {
                    return@withContext PlaybackResolverBackendResponse(errorCode = "INVALID_RESPONSE")
                }
                val obj = JSONObject(body)
                val status = obj.optString("status").trim().uppercase()
                val streams = parseCandidateArray(
                    obj.optJSONArray("streams")
                        ?: obj.optJSONObject("data")?.optJSONArray("streams")
                        ?: obj.optJSONArray("data"),
                    season,
                    episode,
                )
                if (status == "ERROR") {
                    PlaybackResolverBackendResponse(
                        errorCode = obj.optString("errorCode").ifBlank { "BACKEND_ERROR" },
                    )
                } else {
                    PlaybackResolverBackendResponse(candidates = streams)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: java.net.SocketTimeoutException) {
            PlaybackResolverBackendResponse(errorCode = "PROVIDER_TIMEOUT")
        } catch (_: java.io.IOException) {
            PlaybackResolverBackendResponse(errorCode = "BACKEND_UNREACHABLE")
        } catch (_: Exception) {
            PlaybackResolverBackendResponse(errorCode = "INVALID_RESPONSE")
        }
    }

    private suspend fun queryBackendResolveEndpoint(
        title: String,
        year: Int?,
        mediaType: ContentType,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
    ): PlaybackResolverBackendResponse = withContext(Dispatchers.IO) {
        try {
            val yParam = if (year != null && year > 0) "&year=$year" else ""
            val sParam = if (season != null) "&season=$season" else ""
            val eParam = if (episode != null) "&episode=$episode" else ""
            val cleanTitle = title.substringBefore(" · S").substringBefore(" (").trim()
            val category = when (mediaType) {
                ContentType.SERIES -> "series"
                ContentType.TV -> "tv"
                ContentType.MOVIE -> "movies"
            }
            val rParam = "&refresh=${if (forceRefresh) 1 else 0}"
            val endpointUrl = "$BASE_BACKEND_URL/resolve?title=${URLEncoder.encode(cleanTitle, "UTF-8")}" +
                "&category=${URLEncoder.encode(category, "UTF-8")}$yParam$sParam$eParam$rParam"

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    return@withContext PlaybackResolverBackendResponse(errorCode = "BACKEND_HTTP_$code")
                }
                if (body.isBlank()) {
                    return@withContext PlaybackResolverBackendResponse(errorCode = "INVALID_RESPONSE")
                }
                val obj = JSONObject(body)
                val status = obj.optString("status").trim().uppercase()
                val streams = parseCandidateArray(
                    obj.optJSONArray("streams")
                        ?: obj.optJSONObject("data")?.optJSONArray("streams")
                        ?: obj.optJSONArray("data"),
                    season,
                    episode,
                )
                if (status == "ERROR") {
                    PlaybackResolverBackendResponse(
                        errorCode = obj.optString("errorCode").ifBlank { "BACKEND_ERROR" },
                    )
                } else {
                    PlaybackResolverBackendResponse(candidates = streams)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: java.net.SocketTimeoutException) {
            PlaybackResolverBackendResponse(errorCode = "PROVIDER_TIMEOUT")
        } catch (_: java.io.IOException) {
            PlaybackResolverBackendResponse(errorCode = "BACKEND_UNREACHABLE")
        } catch (_: Exception) {
            PlaybackResolverBackendResponse(errorCode = "INVALID_RESPONSE")
        }
    }

    private fun canonicalTitle(value: String): String {
        val withoutEpisodeSuffix = value.trim()
            .replace(
                Regex("\\s*[·•]\\s*S\\d{1,3}E\\d{1,3}(?:\\s*[·•]\\s*Эпизод\\s+\\d+)?$", RegexOption.IGNORE_CASE),
                "",
            )
            .replace(Regex("\\s+S\\d{1,3}E\\d{1,3}$", RegexOption.IGNORE_CASE), "")
        return normalizedTitle(withoutEpisodeSuffix)
    }

    private fun identityMatches(request: PlaybackRequest, candidate: StreamCandidate): Boolean {
        candidate.catalogMediaId?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it != request.mediaId.trim()) return false
        }
        val candidateTitles = listOfNotNull(
            candidate.canonicalTitle?.trim()?.takeIf { it.isNotBlank() },
            candidate.canonicalOriginalTitle?.trim()?.takeIf { it.isNotBlank() },
        )
        if (candidateTitles.isNotEmpty() && candidateTitles.none { canonicalTitle(it) == canonicalTitle(request.title) }) {
            return false
        }
        request.year?.takeIf { it > 0 }?.let { expectedYear ->
            candidate.canonicalYear?.let { if (it != expectedYear) return false }
        }
        candidate.canonicalMediaType?.trim()?.lowercase()?.let { kind ->
            val expected = when (request.mediaType) {
                ContentType.SERIES -> setOf("tv", "series", "serial", "tv_series", "limited_series")
                ContentType.TV -> setOf("tv", "tv_channel", "channel")
                ContentType.MOVIE -> setOf("movie", "movies", "film", "films")
            }
            if (kind.replace('-', '_') !in expected) return false
        }
        if (request.isSeries) {
            if (candidate.seasonNumber != request.seasonNumber || candidate.episodeNumber != request.episodeNumber) return false
        } else if (candidate.seasonNumber != null || candidate.episodeNumber != null) {
            return false
        }
        if (request.isTrailer != candidate.isTrailer) return false
        return true
    }

    private fun usableCandidates(
        request: PlaybackRequest,
        candidates: List<StreamCandidate>,
    ): List<StreamCandidate> = candidates
        .filter(::isStructurallyValidCandidate)
        .filter { identityMatches(request, it) }

    /**
     * Backend discovery is authoritative for a stable logical stream ID. Catalog
     * candidates may contain an older signed locator for the same variant; do not
     * let that stale URL remain ahead of the freshly discovered locator.
     */
    internal fun preferDiscoveredCandidates(
        initial: List<StreamCandidate>,
        discovered: List<StreamCandidate>,
        request: PlaybackRequest,
    ): List<StreamCandidate> {
        if (initial.isEmpty()) return discovered
        if (discovered.isEmpty()) return initial
        val discoveredById = discovered
            .filter { it.stableStreamId.isNotBlank() }
            .groupBy { it.stableStreamId }
        val retainedInitial = initial.filter { old ->
            val sameIdFresh = discoveredById[old.stableStreamId].orEmpty()
            sameIdFresh.none { fresh ->
                fresh.toStreamOption().sameRequestedVariant(
                    old.toStreamOption(),
                    request.seasonNumber,
                    request.episodeNumber,
                )
            }
        }
        return retainedInitial + discovered
    }

    /**
     * Resolve identity first, and only ask the title route when that identity
     * route yielded no usable candidates. This ordering is intentional: the
     * title route is a bounded recovery path, not a second source of identity.
     */
    internal suspend fun resolveStreamsWithBackend(
        request: PlaybackRequest,
        initialCandidates: List<StreamCandidate> = emptyList(),
        forceRefresh: Boolean = false,
        backend: PlaybackResolverBackend,
    ): PlaybackResolverResult = withContext(Dispatchers.IO) {
        try {
            val localOnly = initialCandidates.any { it.url.startsWith("file://", ignoreCase = true) }
            if (localOnly) {
                val safeLocal = usableCandidates(request, initialCandidates)
                    .filter { it.url.startsWith("file://", ignoreCase = true) }
                return@withContext if (safeLocal.isEmpty()) {
                    PlaybackResolverResult.NoSource("Локальный файл не соответствует запрошенной карточке")
                } else {
                    PlaybackResolverResult.Success(StreamDeduplicator.deduplicate(safeLocal))
                }
            }

            val initial = usableCandidates(request, initialCandidates)
            val identityResponse = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                backend.resolveByIdentity(request, forceRefresh)
            } ?: PlaybackResolverBackendResponse(errorCode = "PROVIDER_TIMEOUT")
            val identityCandidates = usableCandidates(request, identityResponse.candidates)

            val discoveredCandidates: List<StreamCandidate>
            val errors = mutableListOf<String>()
            identityResponse.errorCode?.let(errors::add)
            if (identityCandidates.isNotEmpty()) {
                // Do not query /resolve when the identity endpoint succeeded.
                discoveredCandidates = identityCandidates
            } else {
                val titleResponse = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
                    backend.resolveByTitle(request, forceRefresh)
                } ?: PlaybackResolverBackendResponse(errorCode = "PROVIDER_TIMEOUT")
                discoveredCandidates = usableCandidates(request, titleResponse.candidates)
                titleResponse.errorCode?.let(errors::add)
            }

            val all = preferDiscoveredCandidates(initial, discoveredCandidates, request)
            val deduplicated = StreamDeduplicator.deduplicate(all)
            val ranked = StreamRanker.rankCandidates(
                deduplicated,
                context = StreamRankingContext(
                    requestedVoice = request.requestedVoice,
                    requestedQuality = request.requestedQuality,
                    failedStreamIds = emptySet(),
                ),
            )

            if (ranked.isEmpty() && errors.isNotEmpty()) {
                PlaybackResolverResult.Error(
                    "Сервис источников временно недоступен (${errors.distinct().first()})"
                )
            } else if (ranked.isEmpty()) {
                PlaybackResolverResult.NoSource("Не найдено доступных потоков для воспроизведения")
            } else {
                PlaybackResolverResult.Success(ranked)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveStreams failure: ${e::class.java.simpleName}")
            PlaybackResolverResult.Error("Ошибка резолвера", e)
        }
    }

    suspend fun resolveStreams(
        request: PlaybackRequest,
        initialCandidates: List<StreamCandidate> = emptyList(),
        forceRefresh: Boolean = false,
    ): PlaybackResolverResult = resolveStreamsWithBackend(
        request = request,
        initialCandidates = initialCandidates,
        forceRefresh = forceRefresh,
        backend = httpBackend,
    )

    private fun isStructurallyValidCandidate(candidate: StreamCandidate): Boolean =
        candidate.url.isNotBlank() && isStructurallyPlayableUrl(candidate.url)

    internal fun matchesReloadIdentity(
        previous: StreamCandidate,
        refreshed: StreamCandidate,
        request: PlaybackRequest,
    ): Boolean {
        if (!identityMatches(request, refreshed)) return false
        val previousOption = previous.toStreamOption()
        val refreshedOption = refreshed.toStreamOption()
        if (!refreshedOption.sameRequestedVariant(
                previousOption,
                request.seasonNumber,
                request.episodeNumber,
            )
        ) return false
        if (refreshed.stableStreamId == previous.stableStreamId) return true
        val previousLogical = previous.logicalSourceIdentity(
            request.seasonNumber,
            request.episodeNumber,
        )
        val refreshedLogical = refreshed.logicalSourceIdentity(
            request.seasonNumber,
            request.episodeNumber,
        )
        return previousLogical != null && previousLogical == refreshedLogical &&
            identityMatches(request, refreshed)
    }

    suspend fun reloadStreamCandidate(
        candidate: StreamCandidate,
        request: PlaybackRequest,
    ): StreamCandidate? = withContext(Dispatchers.IO) {
        try {
            if (!candidate.reloadSupported && candidate.reloadData.isNullOrBlank()) return@withContext null
            val fresh = resolveStreams(request, forceRefresh = true)
            if (fresh is PlaybackResolverResult.Success) {
                val matching = fresh.candidates.firstOrNull {
                    matchesReloadIdentity(candidate, it, request)
                }
                matching?.let { mergeReloadedCandidate(candidate, it) }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "reloadStreamCandidate failed: ${e::class.java.simpleName}")
            null
        }
    }

    private fun mergeReloadedCandidate(
        previous: StreamCandidate,
        refreshed: StreamCandidate,
    ): StreamCandidate = refreshed.copy(
        // The concrete URL may rotate, but the selected logical stream ID does
        // not. This keeps UI selection and the problem memory coherent.
        stableStreamId = previous.stableStreamId,
        logicalSourceId = refreshed.logicalSourceId ?: previous.logicalSourceId,
        providerItemId = refreshed.providerItemId ?: previous.providerItemId,
        infoHash = refreshed.infoHash ?: previous.infoHash,
        sourceTypeId = refreshed.sourceTypeId ?: previous.sourceTypeId,
        contentTypeId = refreshed.contentTypeId ?: previous.contentTypeId,
        userAgent = refreshed.userAgent ?: previous.userAgent,
        headers = previous.headers + refreshed.headers,
        subtitles = if (refreshed.subtitles.isNotEmpty()) refreshed.subtitles else previous.subtitles,
        hasInternalSubtitles = refreshed.hasInternalSubtitles || previous.hasInternalSubtitles,
        videoTrackIndex = refreshed.videoTrackIndex ?: previous.videoTrackIndex,
        audioTrackIndex = refreshed.audioTrackIndex ?: previous.audioTrackIndex,
        mimeType = refreshed.mimeType ?: previous.mimeType,
        drmScheme = refreshed.drmScheme ?: previous.drmScheme,
        drmLicenseUrl = refreshed.drmLicenseUrl ?: previous.drmLicenseUrl,
        reloadSupported = refreshed.reloadSupported || previous.reloadSupported,
        reloadData = refreshed.reloadData ?: previous.reloadData,
        sourceId = refreshed.sourceId ?: previous.sourceId,
        providerId = refreshed.providerId ?: previous.providerId,
        providerContentId = refreshed.providerContentId ?: previous.providerContentId,
        transportMetadata = previous.transportMetadata + refreshed.transportMetadata,
        downloadUrl = refreshed.downloadUrl ?: previous.downloadUrl,
        downloadHeaders = previous.downloadHeaders + refreshed.downloadHeaders,
        skipIntervals = if (refreshed.skipIntervals.isNotEmpty()) refreshed.skipIntervals else previous.skipIntervals,
        advertisement = refreshed.advertisement ?: previous.advertisement,
        catalogMediaId = refreshed.catalogMediaId ?: previous.catalogMediaId,
        canonicalTitle = refreshed.canonicalTitle ?: previous.canonicalTitle,
        canonicalOriginalTitle = refreshed.canonicalOriginalTitle ?: previous.canonicalOriginalTitle,
        canonicalYear = refreshed.canonicalYear ?: previous.canonicalYear,
        canonicalMediaType = refreshed.canonicalMediaType ?: previous.canonicalMediaType,
        isProblematic = false,
    )
}
