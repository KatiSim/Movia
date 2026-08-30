package app.movia.android.domain.model

import java.security.MessageDigest

private val magnetHashRegex = Regex("(?:^|[?&])xt=urn:btih:([^&\\s]+)", RegexOption.IGNORE_CASE)

fun StreamOption.canonicalStreamId(
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): String {
    val explicit = streamId.trim()
    if (explicit.isNotEmpty()) return explicit

    val hash = infoHash?.trim()?.ifEmpty { null }
        ?: magnetHashRegex.find(url)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
    val season = seasonOverride ?: seasonNumber
    val episode = episodeOverride ?: episodeNumber
    val identity = listOf(
        source.orEmpty().trim().lowercase(),
        providerItemId.orEmpty().trim(),
        hash.orEmpty().lowercase(),
        fileIndex?.toString().orEmpty(),
        filePath.orEmpty().trim(),
        season?.toString().orEmpty(),
        episode?.toString().orEmpty(),
        quality.trim().lowercase(),
        voice.trim().lowercase(),
        url.trim(),
    ).joinToString("|")

    val digest = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(24)
    return "stream:$digest"
}

fun StreamOption.withCanonicalStreamId(
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): StreamOption {
    val id = canonicalStreamId(seasonOverride, episodeOverride)
    return if (streamId == id) this else copy(streamId = id)
}

fun StreamOption.sameRequestedVariant(
    other: StreamOption,
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): Boolean {
    val leftQuality = quality.trim().lowercase()
    val rightQuality = other.quality.trim().lowercase()
    val leftVoice = voice.trim().lowercase()
    val rightVoice = other.voice.trim().lowercase()
    return leftQuality == rightQuality &&
        leftVoice == rightVoice &&
        (seasonOverride ?: seasonNumber) == (seasonOverride ?: other.seasonNumber) &&
        (episodeOverride ?: episodeNumber) == (episodeOverride ?: other.episodeNumber)
}
