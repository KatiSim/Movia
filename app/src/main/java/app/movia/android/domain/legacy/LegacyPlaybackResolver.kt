package app.movia.android.domain.legacy

import android.content.Context
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.SubtitleTrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bridges original provider playlists into the one Movia Media3 session. */
object LegacyPlaybackResolver {
    @Volatile private var latestDiagnostics = JSONObject().put("status", "NOT_STARTED")
    private val candidateCache = LegacyCandidateCache()
    fun diagnostics(): JSONObject = latestDiagnostics

    suspend fun discover(context: Context, request: PlaybackRequest, onCandidates: (List<StreamCandidate>) -> Unit = {}): List<StreamCandidate> = withContext(Dispatchers.IO) {
        if (request.isTrailer || request.title.isBlank()) return@withContext emptyList()
        candidateCache.get(request)?.let(onCandidates)
        try {
            val result = kotlinx.coroutines.runInterruptible {
                LegacyProviderEngine.get(context).apply { setConfigurationOrigin(app.movia.android.domain.backend.MoviaBackend.apiOrigin) }.discover(request.title, request.year,
                    request.seasonNumber, request.episodeNumber, 25_000) { rows ->
                        latestDiagnostics = LegacyProviderEngine.get(context).diagnostics()
                        decode(rows, request).takeIf { it.isNotEmpty() }?.let { found ->
                            candidateCache.put(request, found)
                            onCandidates(found)
                        }
                    }
            }
            latestDiagnostics = result.getJSONObject("diagnostics")
            resolveRows(context, result.optJSONArray("streams") ?: JSONArray(), request).also {
                candidateCache.put(request, it)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            latestDiagnostics = JSONObject().put("status", "ENGINE_ERROR").put("error", error.javaClass.simpleName)
            emptyList()
        }
    }

    suspend fun refresh(context: Context, previous: StreamCandidate, request: PlaybackRequest): StreamCandidate? = withContext(Dispatchers.IO) {
        val id = previous.transportMetadata["legacy_provider_id"]?.toIntOrNull() ?: return@withContext null
        val article = previous.transportMetadata["legacy_article_url"] ?: return@withContext null
        try {
            val rows = kotlinx.coroutines.runInterruptible {
                LegacyProviderEngine.get(context).resolveArticle(id, article, previous.transportMetadata["legacy_content_url"].orEmpty(), request.title, request.year,
                    request.seasonNumber, request.episodeNumber, 20_000)
            }
            val candidates = resolveRows(context, rows, request)
            val sameVariant = candidates.filter { it.voice == previous.voice && it.quality == previous.quality }
            (sameVariant.firstOrNull {
                (it.providerItemId == previous.providerItemId || previous.transportMetadata["legacy_web"] == "true") && it.voice == previous.voice && it.quality == previous.quality
            } ?: sameVariant.firstOrNull())?.copy(stableStreamId = previous.stableStreamId, logicalSourceId = previous.logicalSourceId)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) { null }
    }

    private suspend fun resolveRows(context: Context, rows: JSONArray, request: PlaybackRequest): List<StreamCandidate> =
        decode(rows, request)

    /** Keep every original voice in the menu; open an embed only when it is selected. */
    suspend fun playable(context: Context, candidate: StreamCandidate): StreamCandidate? = withContext(Dispatchers.IO) {
        if (candidate.transportMetadata["legacy_web_player"] != "true") return@withContext candidate
        val media = kotlinx.coroutines.withTimeoutOrNull(13_000) {
            LegacyWebEmbedResolver.resolve(context, candidate.url, candidate.headers)
        }?.sortedWith(compareBy<LegacyWebEmbedResolver.Media> {
            when { it.url.substringBefore('?').endsWith(".m3u8", true) -> 0
                it.url.substringBefore('?').endsWith(".mpd", true) -> 1; else -> 2 }
        }.thenBy { it.url.substringBefore('?') })?.firstOrNull() ?: return@withContext null
        val transport = when {
            media.url.substringBefore('?').endsWith(".m3u8", true) -> "hls"
            media.url.substringBefore('?').endsWith(".mpd", true) -> "dash"
            else -> "direct"
        }
        candidate.copy(url=media.url, headers=media.headers, transport=transport,
            mimeType=when(transport){ "hls"->"application/x-mpegURL";"dash"->"application/dash+xml";else->null },
            transportMetadata=candidate.transportMetadata + mapOf("legacy_web" to "true","legacy_web_player" to "false"))
    }

    internal fun decode(rows: JSONArray, request: PlaybackRequest): List<StreamCandidate> = buildList {
        for (index in 0 until rows.length().coerceAtMost(512)) {
            val row = rows.optJSONObject(index) ?: continue
            val url = row.optString("url")
            if (!LegacyProviderEngine.isMediaUrl(url)) continue
            val season = row.optInt("season").takeIf { it > 0 }
            val episode = row.optInt("episode").takeIf { it > 0 }
            if (season != request.seasonNumber || episode != request.episodeNumber) continue
            val id = row.optInt("providerOrdinal", -1)
            val providerItemId = row.optString("providerItemId")
            if (id < 0 || providerItemId.isBlank()) continue
            val kind = row.optString("kind")
            val headers = row.optJSONObject("headers")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.optString(it) }
            }.orEmpty()
            val quality = row.optString("quality", "Не указано")
            val height = quality.removeSuffix("p").toIntOrNull()
            val transport = when {
                kind == "webplayer" -> "web_embed"
                url.startsWith("magnet:", true) -> "torrent_p2p"
                url.substringBefore('?').endsWith(".m3u8", true) -> "hls"
                url.substringBefore('?').endsWith(".mpd", true) -> "dash"
                else -> "direct"
            }
            val subtitles = row.optJSONArray("subtitles") ?: JSONArray()
            add(StreamCandidate(
                stableStreamId = "legacy:$id:${request.canonicalEpisodeKey}:$providerItemId",
                logicalSourceId = "legacy:$id:${request.canonicalEpisodeKey}:${row.optString("providerSourceId").ifBlank { providerItemId }}",
                providerItemId = providerItemId,
                provider = row.optString("provider"), providerId = "lazy:$id", url = url,
                voice = row.optString("voice", "Не указано"), language = "und", quality = quality,
                resolutionHeight = height, headers = headers,
                userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value,
                subtitles = (0 until subtitles.length()).mapNotNull { n ->
                    val sub = subtitles.optJSONObject(n) ?: return@mapNotNull null
                    val value = sub.optString("url")
                    if (!LegacyProviderEngine.isMediaUrl(value)) return@mapNotNull null
                    SubtitleTrackInfo(value, sub.optString("language", "und"), sub.optString("label", "Субтитры"),
                        if (value.substringBefore('?').endsWith(".srt", true)) "application/x-subrip" else "text/vtt")
                },
                transport = transport,
                mimeType = when (transport) { "hls" -> "application/x-mpegURL"; "dash" -> "application/dash+xml"; else -> null },
                seasonNumber = season, episodeNumber = episode, catalogMediaId = request.mediaId,
                canonicalTitle = request.title, canonicalYear = request.year,
                canonicalMediaType = if (request.isSeries) "series" else "movie",
                reloadSupported = true,
                transportMetadata = mapOf("legacy_engine" to "3.466", "legacy_provider_id" to id.toString(),
                    "legacy_article_url" to row.optString("articleUrl"), "legacy_content_url" to row.optString("contentUrl"), "legacy_web" to row.optBoolean("legacyWeb").toString(),
                    "legacy_web_player" to (kind == "webplayer").toString()),
            ))
        }
    }.distinctBy { it.stableStreamId }

    fun firstFramePayload(candidate: StreamCandidate, request: PlaybackRequest, observation: JSONObject): JSONObject =
        JSONObject().put("engineSha256", LegacyProviderEngine.ENGINE_SHA256).put("mediaId", request.mediaId)
            .put("kind", if (request.isSeries) "EPISODE" else "MOVIE")
            .put("season", request.seasonNumber).put("episode", request.episodeNumber)
            .put("candidate", JSONObject().put("provider", candidate.provider).put("providerItemId", candidate.providerItemId)
                .put("url", candidate.url).put("voice", candidate.voice).put("quality", candidate.quality)
                .put("transport", candidate.transport))
            .put("observation", observation)
}
