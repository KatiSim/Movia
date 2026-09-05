package app.movia.android.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamRequestProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Clean-room port of Zona's verified Media3 metadata-probe stage.
 *
 * This does not create a second ExoPlayer. MetadataRetriever is Media3's
 * metadata/track inspection utility and is used only for source variants that
 * the recovered Zona registry proved to require a probe.
 */
internal object ZonaMediaProbe {
    private const val DURATION_TIMEOUT_SECONDS = 5L
    private const val TRACK_GROUP_TIMEOUT_SECONDS = 8L
    private val verifiedProbeSourceTypes = setOf(23, 32, 45, 53)

    private fun shouldProbe(candidate: StreamCandidate): Boolean {
        if (candidate.videoTrackIndex != null || candidate.audioTrackIndex != null) return false
        if (candidate.transportMetadata["zona_media_probe"].equals("ALL", ignoreCase = true)) return true
        return candidate.sourceTypeId in verifiedProbeSourceTypes
    }

    private fun mimeType(candidate: StreamCandidate): String? {
        candidate.mimeType?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val path = candidate.url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || candidate.transport.equals("hls", true) -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || candidate.transport.equals("dash", true) -> MimeTypes.APPLICATION_MPD
            path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
            else -> null
        }
    }

    private fun mediaItem(candidate: StreamCandidate): MediaItem = MediaItem.Builder()
        .setMediaId(candidate.catalogMediaId.orEmpty())
        .setUri(candidate.url)
        .apply { mimeType(candidate)?.let(::setMimeType) }
        .build()

    suspend fun expand(
        context: Context,
        candidates: List<StreamCandidate>,
    ): List<StreamCandidate> = coroutineScope {
        candidates.map { candidate ->
            async(Dispatchers.IO) {
                if (!shouldProbe(candidate)) listOf(candidate) else probeOne(context.applicationContext, candidate)
            }
        }.awaitAll().flatten()
    }

    private suspend fun probeOne(
        context: Context,
        candidate: StreamCandidate,
    ): List<StreamCandidate> = withContext(Dispatchers.IO) {
        val profile = StreamRequestProfile.from(candidate, candidate.url)
        val dataSourceFactory = DynamicHeaderDataSourceFactory(context).apply {
            setRequestProfile(profile)
        }
        val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val retriever = MetadataRetriever.Builder(context, mediaItem(candidate))
            .setMediaSourceFactory(sourceFactory)
            .build()
        try {
            val durationMs = runCatching {
                val durationUs = retriever.retrieveDurationUs()
                    .get(DURATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                durationUs.takeIf { it > 0L && it != C.TIME_UNSET }?.div(1_000L)
            }.getOrNull() ?: candidate.durationMs

            val groups = runCatching {
                retriever.retrieveTrackGroups()
                    .get(TRACK_GROUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.getOrNull() ?: return@withContext listOf(candidate.copy(durationMs = durationMs))

            val videoGroups = (0 until groups.length)
                .map { index -> index to groups.get(index) }
                .filter { (_, group) -> group.type == C.TRACK_TYPE_VIDEO }
            val audioGroups = (0 until groups.length)
                .map { index -> index to groups.get(index) }
                .filter { (_, group) -> group.type == C.TRACK_TYPE_AUDIO }

            if (videoGroups.isEmpty()) {
                return@withContext listOf(candidate.copy(durationMs = durationMs))
            }

            val audioChoices = if (audioGroups.isEmpty()) {
                listOf(Triple(-1, -1, null))
            } else {
                audioGroups.flatMap { (groupIndex, group) ->
                    (0 until group.length).map { trackIndex ->
                        Triple(groupIndex, trackIndex, group.getFormat(trackIndex))
                    }
                }
            }

            val expanded = buildList {
                for ((videoGroupIndex, videoGroup) in videoGroups) {
                    for (videoTrackIndex in 0 until videoGroup.length) {
                        val videoFormat = videoGroup.getFormat(videoTrackIndex)
                        val height = videoFormat.height.takeIf { it > 0 }
                        val width = videoFormat.width.takeIf { it > 0 }
                        val quality = height?.let { "${it}p" } ?: candidate.quality
                        val resolution = if (width != null && height != null) "${width}x${height}" else candidate.resolution
                        for ((audioGroupIndex, audioTrackIndex, audioFormat) in audioChoices) {
                            val audioLabel = audioFormat?.label?.trim().orEmpty()
                            val audioLanguage = audioFormat?.language?.trim().orEmpty()
                            val voice = when {
                                candidate.voice.isNotBlank() && !candidate.voice.equals("Не указано", true) -> candidate.voice
                                audioLabel.isNotBlank() -> audioLabel
                                audioLanguage.isNotBlank() -> audioLanguage
                                else -> candidate.voice
                            }
                            val metadata = candidate.transportMetadata.toMutableMap().apply {
                                put("zona_media_probe", "ALL")
                                put("zona_video_group_index", videoGroupIndex.toString())
                                videoGroup.id.trim().takeIf { it.isNotBlank() }?.let { put("zona_video_group_id", it) }
                                if (audioGroupIndex >= 0) {
                                    put("zona_audio_group_index", audioGroupIndex.toString())
                                    groups.get(audioGroupIndex).id.trim().takeIf { it.isNotBlank() }?.let {
                                        put("zona_audio_group_id", it)
                                    }
                                }
                            }
                            add(
                                candidate.copy(
                                    voice = voice,
                                    language = audioLanguage.ifBlank { candidate.language },
                                    quality = quality,
                                    resolution = resolution,
                                    resolutionWidth = width ?: candidate.resolutionWidth,
                                    resolutionHeight = height ?: candidate.resolutionHeight,
                                    codec = videoFormat.codecs?.trim()?.takeIf { it.isNotBlank() } ?: candidate.codec,
                                    videoTrackIndex = videoTrackIndex,
                                    audioTrackIndex = audioTrackIndex.takeIf { it >= 0 },
                                    durationMs = durationMs,
                                    transportMetadata = metadata,
                                )
                            )
                        }
                    }
                }
            }
            expanded.ifEmpty { listOf(candidate.copy(durationMs = durationMs)) }
        } catch (_: Throwable) {
            listOf(candidate)
        } finally {
            runCatching { retriever.close() }
        }
    }
}
