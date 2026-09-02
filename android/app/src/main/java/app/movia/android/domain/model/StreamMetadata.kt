package app.movia.android.domain.model

/** Sanitized subtitle metadata carried with a stream candidate. */
data class StreamSubtitle(
    val url: String,
    val language: String = "ru",
    val label: String = "Русские",
    val mimeType: String = "text/vtt",
)

/** A bounded skip interval in the media timeline. */
data class StreamSkipInterval(
    val startMs: Long,
    val endMs: Long,
)

/** Provider advertisement metadata; raw content is never used as a playback URL. */
data class StreamAdvertisement(
    val raw: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
