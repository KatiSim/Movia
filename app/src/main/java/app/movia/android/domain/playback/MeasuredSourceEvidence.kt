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
