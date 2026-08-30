package app.movia.android.domain.playback

object StreamDeduplicator {
    fun normalizeVoice(rawVoice: String?): String {
        val trimmed = rawVoice?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) {
            return "Не указано"
        }
        return trimmed
    }

    fun normalizeQuality(rawQuality: String?): String {
        val trimmed = rawQuality?.trim().orEmpty()
        if (trimmed.isBlank() || trimmed.equals("unknown", ignoreCase = true) || trimmed.equals("none", ignoreCase = true)) {
            return "Не указано"
        }
        return trimmed
    }

    fun deduplicate(candidates: List<StreamCandidate>): List<StreamCandidate> {
        val result = mutableListOf<StreamCandidate>()
        val seenKeys = mutableSetOf<String>()

        for (candidate in candidates) {
            val normalizedVoice = normalizeVoice(candidate.voice)
            val normalizedQuality = normalizeQuality(candidate.quality)
            val clean = candidate.copy(voice = normalizedVoice, quality = normalizedQuality)

            val semanticKey = listOf(
                clean.provider.trim().lowercase(),
                clean.seasonNumber?.toString().orEmpty(),
                clean.episodeNumber?.toString().orEmpty(),
                clean.voice.trim().lowercase(),
                clean.quality.trim().lowercase(),
                clean.stableStreamId.trim().lowercase(),
            ).joinToString("|")

            if (seenKeys.add(semanticKey)) {
                result.add(clean)
            }
        }
        return result
    }
}
