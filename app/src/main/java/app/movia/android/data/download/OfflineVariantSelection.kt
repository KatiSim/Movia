package app.movia.android.data.download

import app.movia.android.domain.playback.StreamCandidate

/** The player's logical ordinal belongs to its prepared manifest, not an alternative download. */
internal fun capturedDownloadSource(
    source: StreamCandidate,
    requestedStreamId: String?,
    voice: String?,
    audioIndex: Int?,
    audioLabel: String?,
): StreamCandidate {
    if (audioIndex == null || audioIndex < 0) return source
    if (source.stableStreamId != requestedStreamId) throw OfflineSelectionException("AUDIO_SELECTION_SOURCE_CHANGED")
    val metadata = source.transportMetadata.filterKeys {
        it !in setOf("zona_audio_group_id", "zona_audio_group_index", "movia_audio_label")
    } + mapOf("movia_captured_audio" to "true") +
        (audioLabel?.takeIf { it.isNotBlank() }?.let { mapOf("movia_audio_label" to it) } ?: emptyMap())
    return source.copy(audioTrackIndex = audioIndex, voice = voice ?: source.voice,
        transportMetadata = metadata, downloadUrl = null, downloadHeaders = emptyMap())
}
