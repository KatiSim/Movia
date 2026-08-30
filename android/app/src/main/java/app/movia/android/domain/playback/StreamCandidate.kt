package app.movia.android.domain.playback

import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.withCanonicalStreamId

data class SubtitleTrackInfo(
    val url: String,
    val language: String = "ru",
    val label: String = "Русские",
    val mimeType: String = "text/vtt",
)

data class StreamCandidate(
    val stableStreamId: String,
    val logicalSourceId: String? = null,
    val provider: String,
    val url: String,
    val voice: String = "Не указано",
    val language: String = "ru",
    val quality: String = "Не указано",
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val codec: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrackInfo> = emptyList(),
    val hasInternalSubtitles: Boolean = false,
    val videoTrackIndex: Int? = null,
    val audioTrackIndex: Int? = null,
    val seeders: Int = 0,
    val durationMs: Long? = null,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val drmScheme: String? = null,
    val drmLicenseUrl: String? = null,
    val reloadSupported: Boolean = false,
    val reloadData: String? = null,
    val isProblematic: Boolean = false,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    fun toStreamOption(): StreamOption {
        return StreamOption(
            voice = voice,
            quality = quality,
            seeders = seeders,
            url = url,
            source = provider,
            streamId = stableStreamId,
            providerItemId = logicalSourceId,
            infoHash = null,
            fileIndex = null,
            filePath = null,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            mimeType = mimeType,
            drmScheme = drmScheme,
            drmLicenseUrl = drmLicenseUrl,
        )
    }

    companion object {
        fun fromStreamOption(option: StreamOption, season: Int? = null, episode: Int? = null): StreamCandidate {
            val normalized = option.withCanonicalStreamId(season, episode)
            val v = normalized.voice.trim().ifBlank { "Не указано" }
            val q = normalized.quality.trim().ifBlank { "Не указано" }
            return StreamCandidate(
                stableStreamId = normalized.streamId,
                logicalSourceId = normalized.providerItemId,
                provider = normalized.source ?: "catalog",
                url = normalized.url,
                voice = v,
                quality = q,
                seeders = normalized.seeders,
                mimeType = normalized.mimeType,
                drmScheme = normalized.drmScheme,
                drmLicenseUrl = normalized.drmLicenseUrl,
                seasonNumber = season ?: normalized.seasonNumber,
                episodeNumber = episode ?: normalized.episodeNumber,
            )
        }
    }
}
