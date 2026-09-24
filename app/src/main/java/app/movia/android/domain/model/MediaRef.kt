package app.movia.android.domain.model

/** Stable identity for a title or a specific episode. Display text is never used as the canonical ID. */
data class MediaRef(
    val contentId: String,
    val season: Int? = null,
    val episode: Int? = null,
) {
    init {
        require(contentId.isNotBlank()) { "contentId must not be blank" }
        require(season == null || season > 0) { "season must be positive" }
        require(episode == null || episode > 0) { "episode must be positive" }
    }

    /** Length-prefixing keeps arbitrary provider IDs from colliding with the episode suffix. */
    val storageKey: String
        get() = buildString {
            append("media:")
            append(contentId.length)
            append(':')
            append(contentId)
            append(":s")
            append(season ?: 0)
            append(":e")
            append(episode ?: 0)
        }

    companion object {
        private val seasonEpisodePattern = Regex(".* · S(\\d+)E(\\d+)(?: · Эпизод \\d+)?$")
        private val legacyEpisodePattern = Regex(".* · E(\\d+) · Эпизод \\d+$")
        private val storageSuffixPattern = Regex(":s(\\d+):e(\\d+)")

        /** Returns null for title values that older callers used as a substitute for a content ID. */
        fun from(contentId: String?, displayTitle: String): MediaRef? {
            val title = displayTitle.trim()
            val baseTitle = title.substringBefore(" · S").substringBefore(" · E")
            val id = contentId?.trim()?.takeIf {
                it.isNotEmpty() && it != title && it != baseTitle
            } ?: return null

            val seasonEpisode = seasonEpisodePattern.matchEntire(title)
            if (seasonEpisode != null) {
                return MediaRef(
                    contentId = id,
                    season = seasonEpisode.groupValues[1].toIntOrNull(),
                    episode = seasonEpisode.groupValues[2].toIntOrNull(),
                )
            }

            val legacyEpisode = legacyEpisodePattern.matchEntire(title)
            if (legacyEpisode != null) {
                return MediaRef(
                    contentId = id,
                    episode = legacyEpisode.groupValues[1].toIntOrNull(),
                )
            }
            return MediaRef(contentId = id)
        }

        /** Keeps unresolved legacy rows addressable until their content ID can be backfilled. */
        fun storageKey(contentId: String?, displayTitle: String): String =
            from(contentId, displayTitle)?.storageKey ?: legacyStorageKey(displayTitle)

        /** Decodes canonical keys without depending on localized display text. */
        fun fromStorageKey(storageKey: String): MediaRef? {
            if (!storageKey.startsWith("media:")) return null
            val lengthSeparator = storageKey.indexOf(':', startIndex = "media:".length)
            if (lengthSeparator < 0) return null
            val contentIdLength = storageKey.substring("media:".length, lengthSeparator).toIntOrNull()
                ?.takeIf { it > 0 } ?: return null
            val contentStart = lengthSeparator + 1
            val contentEnd = contentStart + contentIdLength
            if (contentEnd >= storageKey.length) return null
            val contentId = storageKey.substring(contentStart, contentEnd)
            val suffix = storageSuffixPattern.matchEntire(storageKey.substring(contentEnd)) ?: return null
            val season = suffix.groupValues[1].toIntOrNull() ?: return null
            val episode = suffix.groupValues[2].toIntOrNull() ?: return null
            return runCatching {
                MediaRef(
                    contentId = contentId,
                    season = season.takeIf { it > 0 },
                    episode = episode.takeIf { it > 0 },
                )
            }.getOrNull()
        }

        fun legacyStorageKey(displayTitle: String): String =
            "legacy:${displayTitle.length}:$displayTitle"
    }
}
