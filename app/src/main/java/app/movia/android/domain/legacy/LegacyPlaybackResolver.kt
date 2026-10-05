package app.movia.android.domain.legacy

import android.content.Context
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.StreamCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Compatibility entry point for the Movia-owned registry and Media3 transport. */
object LegacyPlaybackResolver {
    @Volatile private var latestDiagnostics = JSONObject().put("status", "NOT_STARTED")
    private val candidateCache = LegacyCandidateCache()
    fun diagnostics(): JSONObject = latestDiagnostics

    private val registry = app.movia.android.domain.provider.MoviaProviderRegistry(
        listOf(app.movia.android.domain.provider.MoviaBackendProviderAdapter()),
    )

    suspend fun discover(context: Context, request: PlaybackRequest, onCandidates: (List<StreamCandidate>) -> Unit = {}): List<StreamCandidate> = withContext(Dispatchers.IO) {
        if (request.isTrailer || request.title.isBlank() || request.mediaId.isBlank()) return@withContext emptyList()
        candidateCache.get(request)?.let(onCandidates)
        val result = registry.discover(request) { rows ->
            candidateCache.put(request, rows)
            onCandidates(rows)
        }
        latestDiagnostics = JSONObject().put("architecture", "movia-provider-registry")
            .put("referenceRuntimeLoaded", false)
            .put("status", if (result.candidates.isEmpty()) "UNAVAILABLE" else "RESOLVED")
            .put("providers", JSONObject(result.statuses))
            .put("candidateCount", result.candidates.size)
        result.candidates.also { candidateCache.put(request, it) }
    }

    suspend fun refresh(context: Context, previous: StreamCandidate, request: PlaybackRequest): StreamCandidate? =
        app.movia.android.domain.playback.DomainPlaybackResolver.reloadStreamCandidate(previous, request)

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

    fun firstFramePayload(candidate: StreamCandidate, request: PlaybackRequest, observation: JSONObject): JSONObject =
        JSONObject().put("architecture", "movia-provider-registry").put("mediaId", request.mediaId)
            .put("kind", if (request.isSeries) "EPISODE" else "MOVIE")
            .put("season", request.seasonNumber).put("episode", request.episodeNumber)
            .put("candidate", JSONObject().put("provider", candidate.provider).put("providerItemId", candidate.providerItemId)
                .put("url", candidate.url).put("voice", candidate.voice).put("quality", candidate.quality)
                .put("transport", candidate.transport))
            .put("observation", observation)
}
