package app.movia.android.ui.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import app.movia.android.domain.model.StreamOption
import java.util.Locale

data class PlaybackTrackChoice(
    val id: String,
    val label: String,
    val height: Int = 0,
    val language: String? = null,
    val selected: Boolean = false,
    internal val override: TrackSelectionOverride,
    internal val providerAudioIndex: Int? = null,
)

data class PlaybackChoices(
    val audio: List<PlaybackTrackChoice> = emptyList(),
    val video: List<PlaybackTrackChoice> = emptyList(),
    internal val supportedAudioOrdinals: Set<Int>? = null,
)

internal fun qualityHeight(value: String): Int? = app.movia.android.domain.model.videoQualityHeight(value)

internal fun playbackChoices(tracks: Tracks, actualHeight: Int): PlaybackChoices {
    val audio = mutableListOf<PlaybackTrackChoice>()
    val namedAudioIndexes = mutableMapOf<String, Int>()
    val video = mutableListOf<PlaybackTrackChoice>()
    val providerOrdinals = providerAudioOrdinals(tracks)
    val supportedAudio = mutableSetOf<Int>()
    tracks.groups.forEachIndexed { groupOrdinal, group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val id = "track:${group.type}:$groupOrdinal:$index"
            if (group.type == C.TRACK_TYPE_VIDEO && format.height > 0) {
                video += PlaybackTrackChoice(id, when(format.height) { 4320 -> "8K"; 2160 -> "4K"; else -> "${format.height}p" },
                    height = format.height, selected = actualHeight == format.height,
                    override = TrackSelectionOverride(group.mediaTrackGroup, index))
            } else if (group.type == C.TRACK_TYPE_AUDIO) {
                val providerOrdinal = providerOrdinals[groupOrdinal to index]
                providerOrdinal?.let(supportedAudio::add)
                val language = format.language?.takeUnless { it.isBlank() || it == "und" }
                val name = format.label?.takeIf { it.isNotBlank() }
                    ?: language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale("ru")) }
                        ?.replaceFirstChar { it.titlecase(Locale("ru")) }
                    ?: "Аудиодорожка ${audio.size + 1}"
                val knownKey = format.label?.trim()?.takeIf { it.isNotBlank() }?.let { "$it|"+language.orEmpty()+"|"+format.roleFlags }
                val previous = knownKey?.let(namedAudioIndexes::get)
                if (previous != null) {
                    if (group.isTrackSelected(index) && !audio[previous].selected) audio[previous] =
                        PlaybackTrackChoice(id, name, language = language, selected = true,
                            override = TrackSelectionOverride(group.mediaTrackGroup, index), providerAudioIndex = providerOrdinal)
                } else {
                    val label = if (audio.any { it.label == name }) "$name · ${audio.size + 1}" else name
                    if (knownKey != null) namedAudioIndexes[knownKey] = audio.size
                    audio += PlaybackTrackChoice(id, label, language = language,
                        selected = group.isTrackSelected(index), override = TrackSelectionOverride(group.mediaTrackGroup, index), providerAudioIndex = providerOrdinal)
                }
            }
        }
    }
    return PlaybackChoices(audio, video.distinctBy { it.height }.sortedByDescending { it.height },
        supportedAudio.takeIf { tracks.groups.isNotEmpty() })
}

internal fun qualityMenu(streams: List<StreamOption>, tracks: PlaybackChoices, voice: String?, prepared: StreamOption? = null): List<String> {
    val scoped = streams.filter { voice.isNullOrBlank() || voice == "Auto" || it.voice.equals(voice, true) }
    val options = scoped.mapNotNull { row ->
        val height = qualityHeight(row.quality)
        if (prepared != null && tracks.video.isNotEmpty() && row.url == prepared.url &&
            row.headers == prepared.headers && row.userAgent == prepared.userAgent &&
            tracks.video.none { it.height == height }
        ) null else height
    }.plus(tracks.video.map { it.height })
        .distinct().sortedDescending().map { when(it) { 4320 -> "8K"; 2160 -> "4K"; else -> "${it}p" } }
    return listOf("Auto") + options
}

internal fun voiceMenu(streams: List<StreamOption>, tracks: PlaybackChoices, quality: String? = null, prepared: StreamOption? = null): List<String> {
    val available = streams.filter { row ->
        prepared == null || row.url != prepared.url || row.headers != prepared.headers || row.userAgent != prepared.userAgent ||
            row.audioTrackIndex == null || tracks.supportedAudioOrdinals == null || row.audioTrackIndex in tracks.supportedAudioOrdinals
    }
    val providers = StreamSettingsSelection.voiceOptions(available, quality, prepared?.url, tracks.video.map { it.height }.toSet(), prepared)
        .filterNot { it.equals("Auto", true) || it == "Не указано" }
    val mappedOrdinals = streams.filter { it.voice in providers && prepared != null && it.url == prepared.url &&
        it.headers == prepared.headers && it.userAgent == prepared.userAgent }
        .mapNotNull { it.audioTrackIndex }.toSet()
    val internal = tracks.audio.filter { it.providerAudioIndex !in mappedOrdinals }
        .takeIf { tracks.audio.size > 1 || providers.isEmpty() }.orEmpty().map { it.label }
    return (listOf("Auto") + providers + internal).distinct()
}
