package app.movia.android.domain.playback

import app.movia.android.domain.model.ContentType
import java.util.UUID

enum class VideoSourceType {
    DIRECT_CDN,
    TORRENT_P2P,
    LOCAL_STORAGE,
    EMBEDDED_GATEWAY,
}

data class VideoSourceDescriptor(
    val sourceId: String,
    val provider: String,
    val sourceType: VideoSourceType,
    val mediaId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val providerReference: String = "",
    val capabilities: Set<String> = emptySet(),
)

data class PlaybackRequest(
    val mediaId: String,
    val title: String,
    val mediaType: ContentType = ContentType.MOVIE,
    val year: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val requestedVoice: String? = null,
    val requestedQuality: String? = null,
    val requestedStreamId: String? = null,
    val isTrailer: Boolean = false,
    val startPositionMs: Long = 0L,
    val generationId: Long = 0L,
    val correlationId: String = UUID.randomUUID().toString(),
    val attempt: Int = 1,
) {
    val isSeries: Boolean get() = seasonNumber != null && episodeNumber != null
    val canonicalEpisodeKey: String get() = if (isSeries) "$mediaId:s${seasonNumber}e${episodeNumber}" else mediaId
}
