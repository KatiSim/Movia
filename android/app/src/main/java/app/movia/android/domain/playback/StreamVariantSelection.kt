package app.movia.android.domain.playback

import app.movia.android.domain.model.StreamOption

/**
 * Single quality -> voice contract shared by player UI, agent control and Auto ranking.
 * A concrete quality is a hard boundary: voice selection must never silently cross it.
 */
internal object StreamVariantSelection {
    const val UNKNOWN_VOICE = "Не указано"

    private val technicalVoice = Regex(
        """^(?:delete|audio\s*\d+|track\s*\d+|rus\d+|eng\d+|ukr\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun concrete(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("Auto", true) && !it.equals("Any", true) }

    fun qualityHeight(value: String?): Int? {
        val low = value?.trim()?.lowercase().orEmpty()
        return when {
            low.contains("2160") || low.contains("4k") || low.contains("uhd") -> 2160
            low.contains("1440") || low.contains("2k") -> 1440
            low.contains("1080") || low.contains("fullhd") || low.contains("fhd") -> 1080
            low.contains("720") || low == "hd" -> 720
            low.contains("480") || low == "sd" -> 480
            low.contains("360") -> 360
            low.contains("240") -> 240
            low.contains("144") -> 144
            else -> Regex("""(?<!\d)(\d{3,4})p?(?!\d)""")
                .find(low)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }
    }

    fun canonicalQualityLabel(value: String?): String {
        val height = qualityHeight(value)
        return when (height) {
            2160 -> "4K"
            1440 -> "1440p"
            1080 -> "1080p"
            720 -> "720p"
            480 -> "480p"
            360 -> "360p"
            240 -> "240p"
            144 -> "144p"
            else -> value?.trim()?.ifBlank { "Не указано" } ?: "Не указано"
        }
    }

    fun sameQuality(left: String?, right: String?): Boolean {
        val a = left?.trim().orEmpty()
        val b = right?.trim().orEmpty()
        if (a.isBlank() || b.isBlank()) return false
        val ah = qualityHeight(a)
        val bh = qualityHeight(b)
        return a.equals(b, ignoreCase = true) || (ah != null && bh != null && ah == bh)
    }

    fun isAllowedAudioLanguage(language: String?, voice: String?): Boolean =
        semanticLanguage(language, voice) in setOf("ru", "uk")

    fun isTechnicalVoiceLabel(voice: String?): Boolean =
        voice?.trim()?.takeIf { it.isNotBlank() }?.let(technicalVoice::matches) == true

    fun isAllowed(stream: StreamOption): Boolean =
        stream.url.isNotBlank() && !stream.unavailableQuality &&
            !isTechnicalVoiceLabel(stream.voice) && isAllowedAudioLanguage(stream.language, stream.voice)

    fun isAllowed(candidate: StreamCandidate): Boolean =
        candidate.url.isNotBlank() && !candidate.unavailableQuality &&
            !isTechnicalVoiceLabel(candidate.voice) && isAllowedAudioLanguage(candidate.language, candidate.voice)

    private fun usable(streams: List<StreamOption>): List<StreamOption> = streams.filter(::isAllowed)

    fun qualityOptions(streams: List<StreamOption>): List<String> {
        val all = usable(streams)
            .asSequence()
            .map { canonicalQualityLabel(it.quality) }
            .distinct()
            .toList()
        val atLeast360 = all.filter { (qualityHeight(it) ?: 0) >= 360 }
        val visible = atLeast360.ifEmpty { all }
        return visible.sortedWith(
            compareBy<String> { qualityHeight(it) ?: Int.MAX_VALUE }
                .thenBy { it.lowercase() },
        )
    }

    /** 1080p remains Movia's balanced Auto target; otherwise use the best known resolution. */
    fun defaultQuality(streams: List<StreamOption>): String? {
        val options = qualityOptions(streams)
        return options.firstOrNull { qualityHeight(it) == 1080 }
            ?: options.maxWithOrNull(
                compareBy<String> { qualityHeight(it) ?: -1 }
                    .thenByDescending { it.lowercase() },
            )
    }

    fun matchingQualityOption(streams: List<StreamOption>, value: String?): String? {
        val requested = concrete(value) ?: return null
        return qualityOptions(streams).firstOrNull { sameQuality(it, requested) }
    }

    fun voiceLabel(stream: StreamOption): String {
        val raw = stream.voice.trim()
        return if (raw.isBlank() || technicalVoice.matches(raw)) UNKNOWN_VOICE else raw
    }

    fun semanticLanguage(language: String?, voice: String?): String {
        val raw = language?.trim()?.lowercase().orEmpty()
        val lowVoice = voice?.trim()?.lowercase().orEmpty()
        return when {
            lowVoice.contains("укр") || lowVoice.contains("укра") -> "uk"
            lowVoice.contains("original") || lowVoice.contains("english") || lowVoice.contains("оригинал") -> "en"
            raw.startsWith("ru") -> "ru"
            raw.startsWith("uk") || raw.startsWith("ua") -> "uk"
            raw.startsWith("en") -> "en"
            else -> raw.ifBlank { "und" }
        }
    }

    /** Lower is preferred for Auto. Language has priority, then translation/studio preference. */
    fun voicePreferenceRank(language: String?, voice: String?): Int {
        val label = voice?.trim().orEmpty()
        val low = label.lowercase()
        val languageBase = when (semanticLanguage(language, voice)) {
            "ru" -> 0
            "uk" -> 100
            else -> 10_000
        }
        val translationRank = when {
            label.isBlank() || technicalVoice.matches(label) || low == UNKNOWN_VOICE.lowercase() -> 90
            low.contains("дубляж") || low.contains("дублирован") || low.contains("дубльован") -> 0
            low.contains("lostfilm") -> 1
            low.contains("red head sound") || low.contains("rhs") -> 2
            low.contains("hdrezka") || low.contains("rezka") -> 3
            low.contains("кубик") -> 4
            low.contains("кураж") -> 5
            low.contains("newstudio") -> 6
            low.contains("tvshows") -> 7
            low.contains("профессиональн") || low.contains("професій") -> 8
            low.contains("русск") -> 9
            low.contains("авторск") || low.contains("гаврилов") || low.contains("живов") || low.contains("сербин") -> 20
            low.contains("original") || low.contains("english") || low.contains("оригинал") -> 0
            else -> 30
        }
        return languageBase + translationRank
    }

    private fun qualityScoped(streams: List<StreamOption>, quality: String?): List<StreamOption> {
        val pool = usable(streams)
        val requested = concrete(quality) ?: return pool
        return pool.filter { sameQuality(it.quality, requested) }
    }

    fun voiceOptions(streams: List<StreamOption>, quality: String?): List<String> {
        val pool = qualityScoped(streams, quality)
        if (pool.isEmpty()) return emptyList()
        val grouped = pool.groupBy(::voiceLabel)
        val meaningful = grouped.keys.filterNot { it == UNKNOWN_VOICE }
        val visible = meaningful.ifEmpty { grouped.keys.toList() }
        return visible.sortedWith(
            compareBy<String> { label ->
                grouped[label].orEmpty().minOfOrNull { voicePreferenceRank(it.language, it.voice) } ?: Int.MAX_VALUE
            }.thenBy { it.lowercase() },
        )
    }

    fun bestVoiceForQuality(
        streams: List<StreamOption>,
        quality: String?,
        preferredVoice: String? = null,
    ): String? {
        val voices = voiceOptions(streams, quality)
        if (voices.isEmpty()) return null
        val preferred = concrete(preferredVoice)
        return preferred?.let { wanted -> voices.firstOrNull { it.equals(wanted, ignoreCase = true) } }
            ?: voices.first()
    }

    /**
     * Select one concrete stream. Explicit quality and voice are strict; if either is absent,
     * the missing dimension is resolved only inside the remaining scoped pool.
     */
    fun select(
        streams: List<StreamOption>,
        voice: String?,
        quality: String?,
    ): StreamOption? {
        val pool = qualityScoped(streams, quality)
        if (pool.isEmpty()) return null
        val requestedVoice = concrete(voice)
        if (requestedVoice != null) {
            return pool.firstOrNull { voiceLabel(it).equals(requestedVoice, ignoreCase = true) }
        }
        val bestVoice = bestVoiceForQuality(pool, quality = null) ?: return null
        return pool.firstOrNull { voiceLabel(it).equals(bestVoice, ignoreCase = true) }
    }
}
