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

/** A download preserves an explicit variant; preference ranking may relax only an implicit default. */
internal fun selectOfflineCandidate(
    request: app.movia.android.domain.playback.PlaybackRequest,
    candidates: List<StreamCandidate>,
    explicitVoice: String?,
    explicitQuality: String?,
    capturedAudioIndex: Int?,
): StreamCandidate {
    var pool = app.movia.android.domain.playback.DomainPlaybackResolver.validatedCandidates(request, candidates)
        .filter { !it.isProblematic && !it.unavailableQuality }
    val pin = request.requestedStreamId?.trim()?.takeIf { it.isNotBlank() }
    if (pin != null) {
        pool = pool.filter { it.stableStreamId == pin }
        if (pool.isEmpty()) throw OfflineSelectionException("SOURCE_VARIANT_UNAVAILABLE")
    }
    val voice = explicitVoice?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
    val quality = explicitQuality?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
    if (voice != null && !(pin != null && capturedAudioIndex != null && capturedAudioIndex >= 0)) {
        pool = pool.filter { it.voice.equals(voice, true) }
        if (pool.isEmpty()) throw OfflineSelectionException("VOICE_UNAVAILABLE")
    }
    if (quality != null) {
        val height = app.movia.android.domain.model.videoQualityHeight(quality)
        pool = pool.filter { source ->
            val url = source.url.substringBefore('?').substringBefore('#')
            val adaptive = source.transport.lowercase() in setOf("hls", "dash") ||
                url.endsWith(".m3u8", true) || url.endsWith(".mpd", true)
            adaptive || height != null && app.movia.android.domain.model.videoQualityHeight(source.quality) == height
        }
        if (pool.isEmpty()) throw OfflineSelectionException("QUALITY_UNAVAILABLE")
    }
    val explicit = pin != null || voice != null || quality != null
    val downloadable = pool.filter { source ->
        val effective = if (explicit) source.copy(downloadUrl = null, downloadHeaders = emptyMap())
            else offlineRequestSource(source, request.requestedQuality ?: "Auto")
        effective.url.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }
    if (pool.isNotEmpty() && downloadable.isEmpty()) throw OfflineSelectionException("UNSUPPORTED_OFFLINE_TRANSPORT")
    pool = downloadable
    val selected = app.movia.android.domain.playback.StreamRanker.selectBest(pool, request.requestedVoice, request.requestedQuality)
        ?: throw OfflineSelectionException("NO_SOURCE")
    return if (pin != null || voice != null || quality != null) selected.copy(downloadUrl = null, downloadHeaders = emptyMap()) else selected
}

/** Alternate download locators cannot replace a concrete prepared voice/quality. */
internal fun offlineRequestSource(source: StreamCandidate, selectedQuality: String): StreamCandidate {
    val concrete = (source.audioTrackIndex ?: -1) >= 0 ||
        app.movia.android.domain.model.videoQualityHeight(selectedQuality) != null ||
        source.transportMetadata["movia_captured_audio"] == "true"
    val alternative = source.downloadUrl?.trim()?.takeIf { it.isNotBlank() && !concrete }
    if (alternative == null) return source.copy(downloadUrl = null, downloadHeaders = emptyMap())
    val sameOrigin = app.movia.android.domain.playback.StreamRequestProfile.sameOrigin(source.url, alternative)
    val original = source.headers.filterKeys { sameOrigin || !it.equals("cookie", true) }
    return source.copy(url = alternative, headers = original + source.downloadHeaders,
        downloadUrl = null, downloadHeaders = emptyMap())
}

/** An impossible explicit variant is permanent, so WorkManager must not retry it. */
internal class OfflineSelectionException(val code: String) : java.io.IOException(code)
