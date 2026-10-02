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
        return all.sortedWith(
            compareBy<String> { qualityHeight(it) ?: Int.MAX_VALUE }
                .thenBy { it.lowercase() },
        )
    }

    fun voiceOptions(streams: List<StreamOption>, quality: String?, preparedUrl: String? = null, preparedHeights: Set<Int> = emptySet(), prepared: StreamOption? = null): List<String> {
        val usable = streams.filter { it.url.isNotBlank() }
        val requestedQuality = quality?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
        val qualityScoped = requestedQuality?.let { requested ->
            usable.filter { sameQuality(it.quality, requested) ||
                (it.url == preparedUrl && qualityHeight(requested) in preparedHeights &&
                    (prepared == null || it.headers == prepared.headers && it.userAgent == prepared.userAgent)) || isAdaptive(it) }
        }.orEmpty()
        val pool = if (requestedQuality == null) usable else qualityScoped
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
            requestedVoice != null && stream.voice.equals(requestedVoice, true) && isAdaptive(stream)
        } ?: usable.firstOrNull { stream ->
            (requestedVoice == null || usable.none { it.voice.equals(requestedVoice, true) }) && requestedQuality != null && sameQuality(stream.quality, requestedQuality)
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

    private fun qualityHeight(value: String): Int? = app.movia.android.domain.model.videoQualityHeight(value)

    private fun isAdaptive(stream: StreamOption): Boolean = qualityHeight(stream.quality) == null &&
        (stream.transport.lowercase() in setOf("hls", "dash") ||
            stream.url.substringBefore('?').endsWith(".m3u8", true) || stream.url.substringBefore('?').endsWith(".mpd", true))

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
