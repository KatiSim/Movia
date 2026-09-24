package app.movia.android.domain.playback

import app.movia.android.domain.model.MediaContent
import kotlin.math.ceil

enum class PlaybackReadiness {
    READY,
    HIGH,
    PREPARING,
    SLOW,
    MAY_WAIT,
    UNAVAILABLE,
}

data class PlaybackSummaryModel(
    val quality: String,
    val audioSummary: String,
    val subtitlesAvailable: Boolean,
    val readiness: PlaybackReadiness,
    val estimatedStartupSeconds: Int? = null,
)

fun buildPlaybackSummary(content: MediaContent): PlaybackSummaryModel {
    val playableStreams = content.streams.filter { it.url.isNotBlank() }
    val qualityCandidates = buildList {
        playableStreams.mapNotNullTo(this) { stream ->
            stream.resolution?.takeIf { it.isNotBlank() }
                ?: stream.quality.takeIf { it.isNotBlank() }
        }
        content.quality.takeIf { it.isNotBlank() }?.let(::add)
    }
    val quality = qualityCandidates
        .distinct()
        .maxByOrNull(::qualityRank)
        ?.let(::friendlyQuality)
        ?: "Авто"

    val hasRussian = content.audioLanguages.any(::isRussianLabel) ||
        playableStreams.any { stream ->
            stream.language.startsWith("ru", ignoreCase = true) ||
                isRussianLabel(stream.voice)
        }
    val hasOriginal = content.audioLanguages.any(::isOriginalLabel) ||
        playableStreams.any { stream ->
            stream.language.startsWith("en", ignoreCase = true) ||
                isOriginalLabel(stream.voice)
        }
    val audioSummary = buildList {
        if (hasRussian) add("Русский")
        if (hasOriginal) add("Original")
    }.ifEmpty {
        listOf("Автоматически")
    }.joinToString(" + ")

    val subtitlesAvailable = content.subtitleLanguages.isNotEmpty() ||
        playableStreams.any { it.subtitles.isNotEmpty() || it.hasInternalSubtitles }

    val bestHealth = playableStreams.maxOfOrNull { it.healthScore.coerceIn(0.0, 1.0) }
    val startupMs = playableStreams
        .mapNotNull { it.startupLatencyMs }
        .filter { it > 0L }
        .minOrNull()
    val hasFallbackPlayback = !content.playbackUrl.isNullOrBlank()
    val readiness = when {
        playableStreams.isEmpty() && !hasFallbackPlayback -> PlaybackReadiness.UNAVAILABLE
        playableStreams.isEmpty() && hasFallbackPlayback -> PlaybackReadiness.HIGH
        bestHealth == null -> PlaybackReadiness.HIGH
        bestHealth >= 0.82 -> PlaybackReadiness.READY
        bestHealth >= 0.65 -> PlaybackReadiness.HIGH
        bestHealth >= 0.45 -> PlaybackReadiness.MAY_WAIT
        else -> PlaybackReadiness.SLOW
    }
    val startupSeconds = startupMs
        ?.let { ceil(it / 1000.0).toInt() }
        ?.coerceIn(1, 60)

    return PlaybackSummaryModel(
        quality = quality,
        audioSummary = audioSummary,
        subtitlesAvailable = subtitlesAvailable,
        readiness = readiness,
        estimatedStartupSeconds = startupSeconds,
    )
}

private fun qualityRank(raw: String): Int {
    val value = raw.trim().lowercase()
    return when {
        "8k" in value -> 4320
        "4k" in value || "2160" in value -> 2160
        "1440" in value -> 1440
        "1080" in value -> 1080
        "720" in value -> 720
        "480" in value -> 480
        "360" in value -> 360
        else -> value.filter(Char::isDigit).toIntOrNull() ?: 0
    }
}

private fun friendlyQuality(raw: String): String {
    val value = raw.trim()
    val lower = value.lowercase()
    return when {
        "4k" in lower || "2160" in lower -> if ("hdr" in lower) "4K HDR" else "4K"
        "1080" in lower -> "1080p"
        "720" in lower -> "720p"
        "480" in lower -> "480p"
        value.isBlank() -> "Авто"
        else -> value
    }
}

private fun isRussianLabel(raw: String): Boolean {
    val value = raw.lowercase()
    return value.startsWith("ru") ||
        "рус" in value ||
        "дуб" in value ||
        "lostfilm" in value ||
        "rezka" in value ||
        "кураж" in value ||
        "кубик" in value ||
        "newstudio" in value
}

private fun isOriginalLabel(raw: String): Boolean {
    val value = raw.lowercase()
    return value == "en" ||
        value.startsWith("en-") ||
        "original" in value ||
        "english" in value ||
        "оригин" in value
}
