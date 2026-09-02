package app.movia.android.domain.playback

import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.StreamAdvertisement
import app.movia.android.domain.model.StreamSkipInterval
import app.movia.android.domain.model.StreamSubtitle
import app.movia.android.domain.model.withCanonicalStreamId
import app.movia.android.domain.model.variantIdentity
import app.movia.android.domain.model.logicalSourceIdentity

data class SubtitleTrackInfo(
    val url: String,
    val language: String = "ru",
    val label: String = "Русские",
    val mimeType: String = "text/vtt",
)

data class StreamCandidate(
    val stableStreamId: String,
    val logicalSourceId: String? = null,
    /** Provider item identity is distinct from the source/provider grouping. */
    val providerItemId: String? = null,
    val infoHash: String? = null,
    val fileIndex: Int? = null,
    val filePath: String? = null,
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
    val sourceId: String? = null,
    val providerId: String? = null,
    val providerContentId: String? = null,
    val transport: String = "direct",
    val transportMetadata: Map<String, String> = emptyMap(),
    val resolution: String? = null,
    val unavailableQuality: Boolean = false,
    val isTrailer: Boolean = false,
    val downloadUrl: String? = null,
    val downloadHeaders: Map<String, String> = emptyMap(),
    val skipIntervals: List<StreamSkipInterval> = emptyList(),
    val advertisement: StreamAdvertisement? = null,
    val catalogMediaId: String? = null,
    val canonicalTitle: String? = null,
    val canonicalYear: Int? = null,
    val canonicalMediaType: String? = null,
    val healthScore: Double = 0.5,
    val startupLatencyMs: Long? = null,
    val recentFailureCount: Int = 0,
    val providerReliability: Double? = null,
) {
    fun toStreamOption(): StreamOption {
        return StreamOption(
            voice = voice,
            quality = quality,
            seeders = seeders,
            url = url,
            source = provider,
            streamId = stableStreamId,
            providerItemId = providerItemId ?: logicalSourceId,
            infoHash = infoHash,
            fileIndex = fileIndex,
            filePath = filePath,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            mimeType = mimeType,
            drmScheme = drmScheme,
            drmLicenseUrl = drmLicenseUrl,
            language = language,
            codec = codec,
            userAgent = userAgent,
            headers = headers,
            subtitles = subtitles.map { subtitle ->
                StreamSubtitle(
                    url = subtitle.url,
                    language = subtitle.language,
                    label = subtitle.label,
                    mimeType = subtitle.mimeType,
                )
            },
            hasInternalSubtitles = hasInternalSubtitles,
            videoTrackIndex = videoTrackIndex,
            audioTrackIndex = audioTrackIndex,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            reloadSupported = reloadSupported,
            reloadData = reloadData,
            sourceId = sourceId,
            providerId = providerId,
            providerContentId = providerContentId,
            transport = transport,
            transportMetadata = transportMetadata,
            resolution = resolution,
            unavailableQuality = unavailableQuality,
            isTrailer = isTrailer,
            downloadUrl = downloadUrl,
            downloadHeaders = downloadHeaders,
            skipIntervals = skipIntervals,
            advertisement = advertisement,
            catalogMediaId = catalogMediaId,
            canonicalTitle = canonicalTitle,
            canonicalYear = canonicalYear,
            canonicalMediaType = canonicalMediaType?.let { raw ->
                when (raw.trim().lowercase()) {
                    "tv", "series", "serial" -> app.movia.android.domain.model.ContentType.SERIES
                    "tv_channel", "channel" -> app.movia.android.domain.model.ContentType.TV
                    else -> app.movia.android.domain.model.ContentType.MOVIE
                }
            },
            healthScore = healthScore,
            startupLatencyMs = startupLatencyMs,
            recentFailureCount = recentFailureCount,
            providerReliability = providerReliability,
        )
    }

    companion object {
        fun fromStreamOption(option: StreamOption, season: Int? = null, episode: Int? = null): StreamCandidate {
            val normalized = option.withCanonicalStreamId(season, episode)
            val v = normalized.voice.trim().ifBlank { "Не указано" }
            val q = normalized.quality.trim().ifBlank { "Не указано" }
            return StreamCandidate(
                stableStreamId = normalized.streamId,
                logicalSourceId = normalized.sourceId ?: normalized.providerItemId,
                providerItemId = normalized.providerItemId,
                infoHash = normalized.infoHash,
                fileIndex = normalized.fileIndex,
                filePath = normalized.filePath,
                provider = normalized.source ?: "catalog",
                url = normalized.url,
                voice = v,
                quality = q,
                seeders = normalized.seeders,
                mimeType = normalized.mimeType,
                drmScheme = normalized.drmScheme,
                drmLicenseUrl = normalized.drmLicenseUrl,
                sourceId = normalized.sourceId,
                providerId = normalized.providerId,
                providerContentId = normalized.providerContentId,
                transport = normalized.transport,
                transportMetadata = normalized.transportMetadata,
                resolution = normalized.resolution,
                unavailableQuality = normalized.unavailableQuality,
                isTrailer = normalized.isTrailer,
                downloadUrl = normalized.downloadUrl,
                downloadHeaders = normalized.downloadHeaders,
                skipIntervals = normalized.skipIntervals,
                advertisement = normalized.advertisement,
                reloadData = normalized.reloadData,
                catalogMediaId = normalized.catalogMediaId,
                canonicalTitle = normalized.canonicalTitle,
                canonicalYear = normalized.canonicalYear,
                canonicalMediaType = normalized.canonicalMediaType?.name,
                healthScore = normalized.healthScore,
                startupLatencyMs = normalized.startupLatencyMs,
                recentFailureCount = normalized.recentFailureCount,
                providerReliability = normalized.providerReliability,
                language = normalized.language,
                codec = normalized.codec,
                userAgent = normalized.userAgent,
                headers = normalized.headers,
                subtitles = normalized.subtitles.map { subtitle ->
                    SubtitleTrackInfo(
                        url = subtitle.url,
                        language = subtitle.language,
                        label = subtitle.label,
                        mimeType = subtitle.mimeType,
                    )
                },
                hasInternalSubtitles = normalized.hasInternalSubtitles,
                videoTrackIndex = normalized.videoTrackIndex,
                audioTrackIndex = normalized.audioTrackIndex,
                durationMs = normalized.durationMs,
                sizeBytes = normalized.sizeBytes,
                reloadSupported = normalized.reloadSupported,
                seasonNumber = normalized.seasonNumber ?: season,
                episodeNumber = normalized.episodeNumber ?: episode,
            )
        }
    }
}

fun StreamCandidate.variantIdentity(
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): String = toStreamOption().variantIdentity(seasonOverride, episodeOverride)

fun StreamCandidate.logicalSourceIdentity(
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): String? = toStreamOption().logicalSourceIdentity(seasonOverride, episodeOverride)
