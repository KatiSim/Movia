package app.movia.android.domain.playback

import java.security.MessageDigest

internal data class NativeFeedbackScope(val locatorHash: String, val profileHash: String)

/** Calculated from the leaf actually prepared, before any delayed discovery.
 * Mirrors native_variant_feedback.feedback_fingerprints; no locator is sent. */
internal fun nativeFeedbackScope(candidate: StreamCandidate): NativeFeedbackScope {
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
            }
        }
        append('"')
    }
    fun string(value: String?): String = value?.let(::quote) ?: "null"
    val headers = candidate.headers.toSortedMap().entries.joinToString(",", "{", "}") {
        quote(it.key) + ":" + quote(it.value)
    }
    val profile = listOf(
        "\"audio\":" + (candidate.audioTrackIndex?.toString() ?: "null"),
        "\"drm\":" + string(candidate.drmLicenseUrl),
        "\"file\":" + (candidate.fileIndex?.toString() ?: "null"),
        "\"headers\":" + headers,
        "\"path\":" + string(candidate.filePath),
        "\"transport\":" + quote(candidate.transport),
        "\"userAgent\":" + string(candidate.userAgent),
        "\"video\":" + (candidate.videoTrackIndex?.toString() ?: "null"),
    ).joinToString(",", "{", "}")
    fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return NativeFeedbackScope(digest(candidate.url.trim()), digest(profile))
}

/** A delayed HTTP reply cannot attach an ID to a rotated source or another episode. */
internal fun StreamCandidate.withNativeFeedbackSourceId(prepared: StreamCandidate, sourceId: String): StreamCandidate {
    if (!sourceId.matches(Regex("src:[A-Za-z0-9:_-]{1,124}"))) return this
    if (stableStreamId != prepared.stableStreamId || catalogMediaId != prepared.catalogMediaId ||
        seasonNumber != prepared.seasonNumber || episodeNumber != prepared.episodeNumber ||
        nativeFeedbackScope(this) != nativeFeedbackScope(prepared)) return this
    return copy(sourceId = sourceId)
}
