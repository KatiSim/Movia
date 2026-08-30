package app.movia.android.domain.playback

import android.util.Log
import app.movia.android.domain.model.StreamOption
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
    private const val TAG = "DomainPlaybackResolver"
    private const val BASE_BACKEND_URL = "http://127.0.0.1:8888"

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

    private fun parseCandidateArray(arr: JSONArray?, season: Int?, episode: Int?): List<StreamCandidate> {
        if (arr == null) return emptyList()
        val result = mutableListOf<StreamCandidate>()
        for (i in 0 until arr.length()) {
            val sObj = arr.optJSONObject(i) ?: continue
            val url = sObj.optString("url").takeIf { it.isNotBlank() }
                ?: sObj.optString("playback_url", "")
            val source = sObj.optString("source").takeIf { it.isNotBlank() } ?: "resolver"
            if (!isStructurallyPlayableUrl(url)) continue
            if (url.contains("archive.org") || url.contains("themoviedb.org")) continue

            val rawStreamId = sObj.optString("stream_id").takeIf { it.isNotBlank() }
                ?: sObj.optString("streamId").takeIf { it.isNotBlank() }
            val rawVoice = sObj.optString("voice", "Не указано")
            val rawQuality = sObj.optString("quality", "Не указано")

            val option = StreamOption(
                voice = rawVoice,
                quality = rawQuality,
                seeders = sObj.optInt("seeders", 0),
                url = url,
                source = source,
                streamId = rawStreamId.orEmpty(),
                providerItemId = sObj.optString("provider_item_id").takeIf { it.isNotBlank() }
                    ?: sObj.optString("providerItemId").takeIf { it.isNotBlank() },
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
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        try {
            val encodedId = URLEncoder.encode(mediaId, "UTF-8")
            val sParam = if (season != null) "?season=$season" else ""
            val eParam = if (episode != null) "${if (sParam.isEmpty()) "?" else "&"}episode=$episode" else ""
            val rParam = "${if (sParam.isEmpty() && eParam.isEmpty()) "?" else "&"}refresh=${if (forceRefresh) 1 else 0}"
            val endpointUrl = "$BASE_BACKEND_URL/api/movie/$encodedId/stream$sParam$eParam$rParam"

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                parseCandidateArray(obj.optJSONArray("streams"), season, episode)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryBackendStreamEndpoint error: ${e.message}")
            emptyList()
        }
    }

    private suspend fun queryBackendResolveEndpoint(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        try {
            val yParam = if (year != null && year > 0) "&year=$year" else ""
            val sParam = if (season != null) "&season=$season" else ""
            val eParam = if (episode != null) "&episode=$episode" else ""
            val cleanTitle = title.substringBefore(" · S").substringBefore(" (").trim()
            val rParam = "&refresh=${if (forceRefresh) 1 else 0}"
            val endpointUrl = "$BASE_BACKEND_URL/resolve?title=${URLEncoder.encode(cleanTitle, "UTF-8")}$yParam$sParam$eParam$rParam"

            val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 30000
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                parseCandidateArray(obj.optJSONArray("streams"), season, episode)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.d(TAG, "queryBackendResolveEndpoint error: ${e.message}")
            emptyList()
        }
    }

    suspend fun resolveStreams(
        request: PlaybackRequest,
        initialCandidates: List<StreamCandidate> = emptyList(),
        forceRefresh: Boolean = false,
    ): PlaybackResolverResult = withContext(Dispatchers.IO) {
        try {
            val localOnly = initialCandidates.firstOrNull()?.url?.startsWith("file://", ignoreCase = true) == true
            if (localOnly) {
                return@withContext PlaybackResolverResult.Success(initialCandidates)
            }

            val fetched = coroutineScope {
                val directJob = async {
                    withTimeoutOrNull(25_000L) {
                        queryBackendStreamEndpoint(request.mediaId, request.seasonNumber, request.episodeNumber, forceRefresh)
                    } ?: emptyList()
                }
                val resolveJob = async {
                    withTimeoutOrNull(25_000L) {
                        queryBackendResolveEndpoint(request.title, request.year, request.seasonNumber, request.episodeNumber, forceRefresh)
                    } ?: emptyList()
                }
                directJob.await() + resolveJob.await()
            }

            val all = initialCandidates + fetched
            val deduplicated = StreamDeduplicator.deduplicate(all)
            val ranked = StreamRanker.rankCandidates(deduplicated)

            if (ranked.isEmpty()) {
                PlaybackResolverResult.NoSource("Не найдено доступных потоков для воспроизведения")
            } else {
                PlaybackResolverResult.Success(ranked)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolveStreams failure: ${e.message}", e)
            PlaybackResolverResult.Error(e.message ?: "Ошибка резолвера", e)
        }
    }

    suspend fun reloadStreamCandidate(
        candidate: StreamCandidate,
        request: PlaybackRequest,
    ): StreamCandidate? = withContext(Dispatchers.IO) {
        try {
            val fresh = resolveStreams(request, forceRefresh = true)
            if (fresh is PlaybackResolverResult.Success) {
                fresh.candidates.firstOrNull { it.stableStreamId == candidate.stableStreamId }
                    ?: fresh.candidates.firstOrNull {
                        it.voice.equals(candidate.voice, ignoreCase = true) &&
                            it.quality.equals(candidate.quality, ignoreCase = true)
                    }
                    ?: fresh.candidates.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "reloadStreamCandidate failed: ${e.message}")
            null
        }
    }
}
