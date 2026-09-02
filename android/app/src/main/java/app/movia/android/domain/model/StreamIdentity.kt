package app.movia.android.domain.model

import java.security.MessageDigest
import java.net.URI

private val magnetHashRegex = Regex("(?:^|[?&])xt=urn:btih:([^&\\s]+)", RegexOption.IGNORE_CASE)

private fun canonicalMagnetLocator(url: String): String {
    val trimmed = url.trim()
    val hash = magnetHashRegex.find(trimmed)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
        ?: return trimmed
    val query = runCatching { URI(trimmed).rawQuery.orEmpty() }.getOrDefault("")
    val selectors = query.split('&')
        .mapNotNull { part ->
            val key = part.substringBefore('=', "").lowercase()
            if (key == "so" || key == "fl") part else null
        }
        .sorted()
    return "magnet:btih:$hash" + selectors.joinToString("&", prefix = "|")
}

/** Stable locator for dedupe. Magnet trackers and display names are not identity. */
fun StreamOption.canonicalLocator(): String {
    val clean = url.trim()
    return if (clean.startsWith("magnet:?", ignoreCase = true)) {
        canonicalMagnetLocator(clean)
    } else {
        clean
    }
}

private fun normalizedPart(value: String?): String = value.orEmpty().trim().lowercase()

/** Variant identity mirrors Zona's UniqueStreamFilter contract. */
fun StreamOption.variantIdentity(
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): String = listOf(
    canonicalLocator(),
    normalizedPart(voice),
    normalizedPart(quality),
    (seasonOverride ?: seasonNumber)?.toString().orEmpty(),
    (episodeOverride ?: episodeNumber)?.toString().orEmpty(),
    fileIndex?.toString().orEmpty(),
    normalizedPart(filePath),
).joinToString("|")

/** Identity used only for same-logical-stream reload, excluding a rotated URL. */
fun StreamOption.logicalSourceIdentity(
    seasonOverride: Int? = null,
    episodeOverride: Int? = null,
): String? {
    val source = listOf(sourceId, providerId, providerContentId, providerItemId, infoHash)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        ?: return null
    return listOf(
        source.lowercase(),
        normalizedPart(voice),
        normalizedPart(quality),
        (seasonOverride ?: seasonNumber)?.toString().orEmpty(),
        (episodeOverride ?: episodeNumber)?.toString().orEmpty(),
        fileIndex?.toString().orEmpty(),
        normalizedPart(filePath),
    ).joinToString("|")
}

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
        canonicalLocator(),
        hash.orEmpty().lowercase(),
        fileIndex?.toString().orEmpty(),
        filePath.orEmpty().trim(),
        season?.toString().orEmpty(),
        episode?.toString().orEmpty(),
        quality.trim().lowercase(),
        voice.trim().lowercase(),
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
    val leftSeason = seasonOverride ?: seasonNumber
    val rightSeason = seasonOverride ?: other.seasonNumber
    val leftEpisode = episodeOverride ?: episodeNumber
    val rightEpisode = episodeOverride ?: other.episodeNumber
    return leftQuality == rightQuality &&
        leftVoice == rightVoice &&
        leftSeason == rightSeason &&
        leftEpisode == rightEpisode
}
