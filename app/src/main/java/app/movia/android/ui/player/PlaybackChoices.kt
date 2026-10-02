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
)

data class PlaybackChoices(
    val audio: List<PlaybackTrackChoice> = emptyList(),
    val video: List<PlaybackTrackChoice> = emptyList(),
)

internal fun qualityHeight(value: String): Int? = when {
    value.equals("4K", true) || value.equals("UHD", true) -> 2160
    value.equals("2K", true) -> 1440
    value.equals("HD", true) -> 720
    value.equals("FullHD", true) || value.equals("FHD", true) -> 1080
    else -> Regex("(?<!\\d)(\\d{3,4})p?(?!\\d)", RegexOption.IGNORE_CASE)
        .find(value)?.groupValues?.get(1)?.toIntOrNull()
}

internal fun playbackChoices(tracks: Tracks, actualHeight: Int): PlaybackChoices {
    val audio = mutableListOf<PlaybackTrackChoice>()
    val namedAudioIndexes = mutableMapOf<String, Int>()
    val video = mutableListOf<PlaybackTrackChoice>()
    tracks.groups.forEachIndexed { groupOrdinal, group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val id = "track:${group.type}:$groupOrdinal:$index"
            if (group.type == C.TRACK_TYPE_VIDEO && format.height > 0) {
                video += PlaybackTrackChoice(id, if (format.height >= 2160) "4K" else "${format.height}p",
                    height = format.height, selected = actualHeight == format.height,
                    override = TrackSelectionOverride(group.mediaTrackGroup, index))
            } else if (group.type == C.TRACK_TYPE_AUDIO) {
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
                            override = TrackSelectionOverride(group.mediaTrackGroup, index))
                } else {
                    val label = if (audio.any { it.label == name }) "$name · ${audio.size + 1}" else name
                    if (knownKey != null) namedAudioIndexes[knownKey] = audio.size
                    audio += PlaybackTrackChoice(id, label, language = language,
                        selected = group.isTrackSelected(index), override = TrackSelectionOverride(group.mediaTrackGroup, index))
                }
            }
        }
    }
    return PlaybackChoices(audio, video.distinctBy { it.height }.sortedByDescending { it.height })
}

internal fun qualityMenu(streams: List<StreamOption>, tracks: PlaybackChoices, voice: String?): List<String> {
    val scoped = streams.filter { voice.isNullOrBlank() || voice == "Auto" || it.voice.equals(voice, true) }
    val options = scoped.mapNotNull { qualityHeight(it.quality) }.plus(tracks.video.map { it.height })
        .distinct().sortedDescending().map { if (it >= 2160) "4K" else "${it}p" }
    return listOf("Auto") + options
}

internal fun voiceMenu(streams: List<StreamOption>, tracks: PlaybackChoices): List<String> {
    val providers = StreamSettingsSelection.voiceOptions(streams, null)
        .filterNot { it.equals("Auto", true) || it == "Не указано" }
    val internal = if (tracks.audio.size > 1 || providers.isEmpty()) tracks.audio.map { it.label } else emptyList()
    return (listOf("Auto") + providers + internal).distinct()
}
