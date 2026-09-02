package app.movia.android.domain.playback

import android.util.Log
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.StreamAdvertisement
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.StreamSkipInterval
import app.movia.android.domain.model.StreamSubtitle
import app.movia.android.domain.model.sameRequestedVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

object DomainPlaybackResolver {
    private data class BackendStreamsResponse(
        val streams: List<StreamCandidate> = emptyList(),
        val errorCode: String? = null,
    )

    private const val TAG = "DomainPlaybackResolver"
    private const val BASE_BACKEND_URL = "http://127.0.0.1:8888"

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
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true)
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

    private fun parseCandidateArray(
        arr: JSONArray?,
        season: Int?,
        episode: Int?,
    ): List<StreamCandidate> {
        if (arr == null) return emptyList()
        val result = mutableListOf<StreamCandidate>()
        for (i in 0 until arr.length()) {
            val sObj = arr.optJSONObject(i) ?: continue
            val url = sObj.optString("url").takeIf { it.isNotBlank() }
                ?: sObj.optString("playback_url", "")
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
            val durationMs = sObj.optLong("duration", 0L).takeIf { it > 0L }
            val sizeBytes = sObj.optLong("size", 0L).takeIf { it > 0L }
            val transport = transportFor(url, firstString(sObj, "transport"))
            val option = StreamOption(
                voice = sObj.optString("voice", "Не указано"),
                quality = sObj.optString("quality", "Не указано"),
                seeders = sObj.optInt("seeders", 0),
                url = url,
                source = source,
                streamId = sObj.optString("stream_id").takeIf { it.isNotBlank() }
                    ?: sObj.optString("streamId").takeIf { it.isNotBlank() }.orEmpty(),
                providerItemId = sObj.optString("provider_item_id").takeIf { it.isNotBlank() }
                    ?: sObj.optString("providerItemId").takeIf { it.isNotBlank() },
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
                    sObj.optBoolean("reload_supported", false),
                transport = transport,
                transportMetadata = parseStringMap(
                    sObj.optJSONObject("transport_metadata")
                        ?: sObj.optJSONObject("transportMetadata")
                ),
                resolution = firstString(sObj, "resolution", "video_resolution", "videoResolution"),
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
                startupLatencyMs = sObj.optLong("startup_latency_ms", 0L).takeIf { it > 0L }
                    ?: sObj.optLong("startupLatencyMs", 0L).takeIf { it > 0L },
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
    ): BackendStreamsResponse = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(mediaId, "UTF-8")
            val sParam = if (season != null) "?season=$season" else ""
            val eParam = if (episode != null) "${if (sParam.isEmpty()) "?" else "&"}episode=$episode" else ""
            val rParam = "${if (sParam.isEmpty() && eParam.isEmpty()) "?" else "&"}refresh=${if (forceRefresh) 1 else 0}"
            val endpointUrl = "$BASE_BACKEND_URL/api/movie/$encodedId/stream$sParam$eParam$rParam"

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    return@withContext BackendStreamsResponse(errorCode = "BACKEND_HTTP_$code")
                }
                if (body.isBlank()) {
                    return@withContext BackendStreamsResponse(errorCode = "INVALID_RESPONSE")
                }
                val obj = JSONObject(body)
                val status = obj.optString("status").trim().uppercase()
                val streams = parseCandidateArray(obj.optJSONArray("streams"), season, episode)
                if (status == "ERROR") {
                    BackendStreamsResponse(errorCode = obj.optString("errorCode").ifBlank { "BACKEND_ERROR" })
                } else {
                    BackendStreamsResponse(streams = streams)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: java.net.SocketTimeoutException) {
            BackendStreamsResponse(errorCode = "PROVIDER_TIMEOUT")
        } catch (_: java.io.IOException) {
            BackendStreamsResponse(errorCode = "BACKEND_UNREACHABLE")
        } catch (_: Exception) {
            BackendStreamsResponse(errorCode = "INVALID_RESPONSE")
        }
    }

    private suspend fun queryBackendResolveEndpoint(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
    ): BackendStreamsResponse = withContext(Dispatchers.IO) {
        try {
            val yParam = if (year != null && year > 0) "&year=$year" else ""
            val sParam = if (season != null) "&season=$season" else ""
            val eParam = if (episode != null) "&episode=$episode" else ""
            val cleanTitle = title.substringBefore(" · S").substringBefore(" (").trim()
            val rParam = "&refresh=${if (forceRefresh) 1 else 0}"
            val endpointUrl = "$BASE_BACKEND_URL/resolve?title=${URLEncoder.encode(cleanTitle, "UTF-8")}$yParam$sParam$eParam$rParam"

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/json")
            }
            try {
                val code = conn.responseCode
                val bodyStream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = bodyStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    return@withContext BackendStreamsResponse(errorCode = "BACKEND_HTTP_$code")
                }
                if (body.isBlank()) {
                    return@withContext BackendStreamsResponse(errorCode = "INVALID_RESPONSE")
                }
                val obj = JSONObject(body)
                val status = obj.optString("status").trim().uppercase()
                val streams = parseCandidateArray(obj.optJSONArray("streams"), season, episode)
                if (status == "ERROR") {
                    BackendStreamsResponse(errorCode = obj.optString("errorCode").ifBlank { "BACKEND_ERROR" })
                } else {
                    BackendStreamsResponse(streams = streams)
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: java.net.SocketTimeoutException) {
            BackendStreamsResponse(errorCode = "PROVIDER_TIMEOUT")
        } catch (_: java.io.IOException) {
            BackendStreamsResponse(errorCode = "BACKEND_UNREACHABLE")
        } catch (_: Exception) {
            BackendStreamsResponse(errorCode = "INVALID_RESPONSE")
        }
    }

    private fun identityMatches(request: PlaybackRequest, candidate: StreamCandidate): Boolean {
        candidate.catalogMediaId?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (it != request.mediaId.trim()) return false
        }
        candidate.canonicalTitle?.trim()?.takeIf { it.isNotBlank() }?.let {
            if (normalizedTitle(it) != normalizedTitle(request.title)) return false
        }
        request.year?.takeIf { it > 0 }?.let { expectedYear ->
            candidate.canonicalYear?.let { if (it != expectedYear) return false }
        }
        candidate.canonicalMediaType?.trim()?.lowercase()?.let { kind ->
            val expected = when (request.mediaType) {
                ContentType.SERIES -> setOf("tv", "series", "serial")
                ContentType.TV -> setOf("tv", "tv_channel", "channel")
                ContentType.MOVIE -> setOf("movie", "movies", "film")
            }
            if (kind !in expected) return false
        }
        if (request.isSeries) {
            if (candidate.seasonNumber != request.seasonNumber || candidate.episodeNumber != request.episodeNumber) return false
        } else if (candidate.seasonNumber != null || candidate.episodeNumber != null) {
            return false
        }
        return true
    }

    suspend fun resolveStreams(
        request: PlaybackRequest,
        initialCandidates: List<StreamCandidate> = emptyList(),
        forceRefresh: Boolean = false,
    ): PlaybackResolverResult = withContext(Dispatchers.IO) {
        try {
            val localOnly = initialCandidates.firstOrNull()?.url?.startsWith("file://", ignoreCase = true) == true
            if (localOnly) {
                val safeLocal = initialCandidates
                    .filter(::isStructurallyValidCandidate)
                    .filter { identityMatches(request, it) }
                return@withContext if (safeLocal.isEmpty()) {
                    PlaybackResolverResult.NoSource("Локальный файл не соответствует запрошенной карточке")
                } else {
                    PlaybackResolverResult.Success(StreamDeduplicator.deduplicate(safeLocal))
                }
            }

            val fetched = coroutineScope {
                val directJob = async {
                    withTimeoutOrNull(25_000L) {
                        queryBackendStreamEndpoint(
                            request.mediaId,
                            request.seasonNumber,
                            request.episodeNumber,
                            forceRefresh,
                        )
                    } ?: BackendStreamsResponse(errorCode = "PROVIDER_TIMEOUT")
                }
                val resolveJob = async {
                    withTimeoutOrNull(25_000L) {
                        queryBackendResolveEndpoint(
                            request.title,
                            request.year,
                            request.seasonNumber,
                            request.episodeNumber,
                            forceRefresh,
                        )
                    } ?: BackendStreamsResponse(errorCode = "PROVIDER_TIMEOUT")
                }
                directJob.await() to resolveJob.await()
            }

            val fetchedStreams = fetched.first.streams + fetched.second.streams
            val errors = listOfNotNull(fetched.first.errorCode, fetched.second.errorCode)
                .distinct()
            val all = (initialCandidates + fetchedStreams)
                .filter(::isStructurallyValidCandidate)
                .filter { identityMatches(request, it) }
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
                    "Сервис источников временно недоступен (${errors.first()})"
                )
            } else if (ranked.isEmpty()) {
                PlaybackResolverResult.NoSource("Не найдено доступных потоков для воспроизведения")
            } else {
                PlaybackResolverResult.Success(ranked)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveStreams failure: ${e.message}", e)
            PlaybackResolverResult.Error(e.message ?: "Ошибка резолвера", e)
        }
    }

    private fun isStructurallyValidCandidate(candidate: StreamCandidate): Boolean =
        candidate.url.isNotBlank() && isStructurallyPlayableUrl(candidate.url)

    suspend fun reloadStreamCandidate(
        candidate: StreamCandidate,
        request: PlaybackRequest,
    ): StreamCandidate? = withContext(Dispatchers.IO) {
        try {
            val fresh = resolveStreams(request, forceRefresh = true)
            if (fresh is PlaybackResolverResult.Success) {
                fresh.candidates.firstOrNull { freshCandidate ->
                    freshCandidate.stableStreamId == candidate.stableStreamId &&
                        freshCandidate.variantIdentity() == candidate.variantIdentity()
                } ?: fresh.candidates.firstOrNull { freshCandidate ->
                    val oldLogical = candidate.logicalSourceIdentity()
                    val freshLogical = freshCandidate.logicalSourceIdentity()
                    oldLogical != null && oldLogical == freshLogical &&
                        freshCandidate.toStreamOption().sameRequestedVariant(
                            candidate.toStreamOption(),
                            request.seasonNumber,
                            request.episodeNumber,
                        ) &&
                        identityMatches(request, freshCandidate)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "reloadStreamCandidate failed: ${e.message}")
            null
        }
    }
}
