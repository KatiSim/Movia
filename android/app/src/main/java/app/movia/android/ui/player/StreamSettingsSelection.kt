package app.movia.android.ui.player

import app.movia.android.domain.model.StreamOption

/**
 * Provider voice/quality selection lives at the logical StreamOption layer.
 * Media3 audio/video track groups are a different identity space and must not
 * be used to resolve studio names such as LostFilm or Кубик в Кубе.
 */
internal object StreamSettingsSelection {
    fun qualityOptions(streams: List<StreamOption>): List<String> {
        val all = streams.asSequence()
            .filter { it.url.isNotBlank() }
            .map { it.quality.trim().ifBlank { "Не указано" } }
            .distinct()
            .toList()
        val atLeast360 = all.filter { (qualityHeight(it) ?: 0) >= 360 }
        val visible = atLeast360.ifEmpty { all }
        return visible.sortedWith(
            compareBy<String> { qualityHeight(it) ?: Int.MAX_VALUE }
                .thenBy { it.lowercase() },
        )
    }

    fun voiceOptions(streams: List<StreamOption>, quality: String?): List<String> {
        val usable = streams.filter { it.url.isNotBlank() }
        val requestedQuality = quality?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
        val qualityScoped = requestedQuality?.let { requested ->
            usable.filter { sameQuality(it.quality, requested) }
        }.orEmpty()
        val pool = qualityScoped.ifEmpty { usable }
        return pool
            .map { it.voice.trim().ifBlank { "Не указано" } }
            .distinct()
            .sortedWith(compareBy(::voiceRank).thenBy { it.lowercase() })
    }

    fun select(
        streams: List<StreamOption>,
        voice: String?,
        quality: String?,
    ): StreamOption? {
        val usable = streams.filter { it.url.isNotBlank() }
        val requestedVoice = voice?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
        val requestedQuality = quality?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
        return usable.firstOrNull { stream ->
            requestedVoice != null && requestedQuality != null &&
                stream.voice.equals(requestedVoice, ignoreCase = true) &&
                sameQuality(stream.quality, requestedQuality)
        } ?: usable.firstOrNull { stream ->
            requestedQuality != null && sameQuality(stream.quality, requestedQuality)
        } ?: usable.firstOrNull { stream ->
            requestedVoice != null && stream.voice.equals(requestedVoice, ignoreCase = true)
        }
    }

    private fun sameQuality(left: String, right: String): Boolean {
        val leftHeight = qualityHeight(left)
        val rightHeight = qualityHeight(right)
        return left.equals(right, ignoreCase = true) ||
            (leftHeight != null && rightHeight != null && leftHeight == rightHeight)
    }

    private fun qualityHeight(value: String): Int? {
        val low = value.trim().lowercase()
        return when {
            low.contains("2160") || low.contains("4k") || low.contains("uhd") -> 2160
            low.contains("1440") || low.contains("2k") -> 1440
            low.contains("1080") || low.contains("fullhd") || low.contains("fhd") -> 1080
            low.contains("720") || low == "hd" -> 720
            low.contains("480") || low == "sd" -> 480
            low.contains("360") -> 360
            low.contains("240") -> 240
            low.contains("144") -> 144
            else -> Regex("""(?<!\d)(\d{3,4})p?(?!\d)""").find(low)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun voiceRank(value: String): Int {
        val low = value.lowercase()
        return when {
            low.contains("дубляж") || low.contains("дублированный") -> 0
            low.contains("lostfilm") -> 1
            low.contains("red head sound") || low.contains("rhs") -> 2
            low.contains("hdrezka") || low.contains("rezka") -> 3
            low.contains("кубик") -> 4
            low.contains("кураж") -> 5
            low.contains("newstudio") -> 6
            low.contains("профессиональн") -> 7
            low.contains("русск") -> 8
            low.contains("original") || low.contains("english") -> 20
            else -> 10
        }
    }
}
