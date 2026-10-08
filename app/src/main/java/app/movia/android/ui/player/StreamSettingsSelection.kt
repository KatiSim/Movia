package app.movia.android.ui.player

import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamRanker

/**
 * Provider voice/quality selection lives at the logical StreamOption layer.
 * Media3 audio/video track groups are a different identity space and must not
 * be used to resolve studio names such as LostFilm or Кубик в Кубе.
 */
internal object StreamSettingsSelection {
    /** A new manual track choice adopts the prepared leaf after automatic fallback. */
    fun withPreparedQuality(request: app.movia.android.domain.playback.PlaybackRequest, quality: String,
        preparedStreamId: String?): app.movia.android.domain.playback.PlaybackRequest = request.copy(
            requestedQuality = quality,
            requestedStreamId = preparedStreamId?.takeIf { it.isNotBlank() } ?: request.requestedStreamId,
        )

    /** A selected row owns its quality; voice-only switches retain the user's request. */
    fun requestedQualityForSwitch(stream: StreamOption, currentQuality: String, explicitVariant: Boolean): String {
        if (!explicitVariant) return currentQuality
        return qualityHeight(stream.quality)?.let { "${it}p" } ?: "Auto"
    }

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
        failedStreamIds: Set<String> = emptySet(),
    ): StreamOption? {
        val usable = streams.filter { it.url.isNotBlank() }
        val requestedVoice = voice?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
        val requestedQuality = quality?.trim()?.takeIf { it.isNotBlank() && !it.equals("Auto", true) }
        // Keep the requested voice/quality tiers, but rank alternatives within
        // each tier using the same decoder evidence and health policy as Auto.
        fun best(pool: List<StreamOption>): StreamOption? {
            val indexed = pool.map { it to StreamCandidate.fromStreamOption(it, it.seasonNumber, it.episodeNumber) }
            val selected = StreamRanker.selectBest(
                indexed.map { it.second }, requestedVoice, requestedQuality, failedStreamIds,
            ) ?: return null
            return indexed.firstOrNull { it.second == selected }?.first
        }
        return best(usable.filter { stream ->
            requestedVoice != null && requestedQuality != null &&
                stream.voice.equals(requestedVoice, true) && sameQuality(stream.quality, requestedQuality)
        }) ?: best(usable.filter { stream ->
            requestedVoice != null && stream.voice.equals(requestedVoice, true) && isAdaptive(stream)
        }) ?: best(usable.filter { stream ->
            (requestedVoice == null || usable.none { it.voice.equals(requestedVoice, true) }) &&
                requestedQuality != null && sameQuality(stream.quality, requestedQuality)
        }) ?: best(usable.filter { stream ->
            requestedVoice != null && stream.voice.equals(requestedVoice, true)
        }) ?: if (requestedVoice == null && requestedQuality == null) best(usable) else null
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
