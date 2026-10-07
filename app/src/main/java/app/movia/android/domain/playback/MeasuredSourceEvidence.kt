package app.movia.android.domain.playback

import app.movia.android.domain.model.StreamOption

/** Decoder measurements belong to this exact indexed locator, never a voice label. */
data class MeasuredSourceEvidence(
    val sourceId: String?,
    val status: String?,
    val method: String?,
    val actualQuality: String?,
    val actualQualities: List<String> = emptyList(),
) {
    val decoded: Boolean get() = status == "VERIFIED" && method == "MEDIA3_SUCCESS"
    val measuredHeight: Int? get() = actualQuality?.trim()
        ?.let { Regex("^(\\d{2,5})p$", RegexOption.IGNORE_CASE).matchEntire(it) }
        ?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it > 0 }
}

fun StreamOption.withMeasuredSourceEvidence(evidence: MeasuredSourceEvidence): StreamOption {
    if (sourceId.isNullOrBlank() || sourceId != evidence.sourceId || !evidence.decoded) return this
    val metadata = transportMetadata + mapOf(
        "playback_verification_status" to "VERIFIED",
        "playback_verification_method" to "MEDIA3_SUCCESS",
        "playback_decoded" to "true",
    )
    val height = evidence.measuredHeight ?: return copy(transportMetadata = metadata)
    // Multiple decoder renditions are a selectable adaptive container. One
    // observed active height cannot redefine the entire container as fixed.
    val adaptive = transportMetadata["manifest_kind"] == "master" ||
        evidence.actualQualities.distinct().size > 1
    if (adaptive) return copy(transportMetadata = metadata)
    return copy(
        quality = "${height}p",
        resolutionHeight = height,
        transportMetadata = metadata + mapOf(
            "provider_reported_quality" to (transportMetadata["provider_reported_quality"] ?: quality),
            "measured_quality" to "${height}p",
        ),
    )
}

// Measurements may survive another inventory publication only for the exact
// locator, request profile and indexed tracks that produced them.
internal fun sameMeasurementScope(left: StreamCandidate, right: StreamCandidate): Boolean =
    left.url == right.url && left.headers == right.headers && left.userAgent == right.userAgent &&
        left.transport == right.transport && left.drmScheme == right.drmScheme &&
        left.drmLicenseUrl == right.drmLicenseUrl && left.videoTrackIndex == right.videoTrackIndex &&
        left.audioTrackIndex == right.audioTrackIndex && left.fileIndex == right.fileIndex &&
        left.filePath == right.filePath && left.seasonNumber == right.seasonNumber &&
        left.episodeNumber == right.episodeNumber

internal fun StreamCandidate.withLocalDecodedMeasurement(height: Int, heights: List<Int>): StreamCandidate {
    if (height <= 0) return this
    val adaptive = transportMetadata["manifest_kind"] == "master" || heights.distinct().size > 1
    val metadata = transportMetadata + mapOf(
        "playback_verification_status" to "VERIFIED",
        "playback_verification_method" to "MEDIA3_SUCCESS",
        "playback_decoded" to "true",
        "provider_reported_quality" to (transportMetadata["provider_reported_quality"] ?: quality),
        "measured_quality" to "${height}p",
    )
    return copy(quality = if (adaptive) quality else "${height}p",
        resolutionHeight = if (adaptive) resolutionHeight else height,
        transportMetadata = metadata, recentFailureCount = 0, isProblematic = false)
}

internal fun preserveMeasurementScope(previous: StreamCandidate, fresh: StreamCandidate,
    merged: StreamCandidate): StreamCandidate {
    val same = sameMeasurementScope(previous, fresh)
    val evidenceKeys = setOf("playback_verification_status", "playback_verification_method",
        "playback_decoded", "measured_quality", "measured_height", "measured_duration_ms",
        "manifest_verified", "manifest_kind", "available_qualities", "variant_count")
    val metadata = (if (same) previous.transportMetadata else previous.transportMetadata - evidenceKeys) +
        fresh.transportMetadata
    val priorHeight = app.movia.android.domain.model.videoQualityHeight(previous.quality)
    val provenHeight = previous.transportMetadata["measured_quality"]?.let {
        app.movia.android.domain.model.videoQualityHeight(it)
    } ?: previous.transportMetadata["measured_height"]?.toIntOrNull()
    val unknownFresh = app.movia.android.domain.model.videoQualityHeight(fresh.quality) == null &&
        !fresh.quality.equals("Auto", true)
    val keepQuality = same && unknownFresh && priorHeight != null && priorHeight == provenHeight
    return merged.copy(
        quality = if (keepQuality) previous.quality else fresh.quality,
        sourceId = fresh.sourceId ?: previous.sourceId.takeIf { same },
        resolutionHeight = fresh.resolutionHeight ?: previous.resolutionHeight.takeIf { same },
        resolutionWidth = fresh.resolutionWidth ?: previous.resolutionWidth.takeIf { same },
        resolution = fresh.resolution ?: previous.resolution.takeIf { same },
        durationMs = fresh.durationMs ?: previous.durationMs.takeIf { same },
        transportMetadata = metadata,
    )
}
