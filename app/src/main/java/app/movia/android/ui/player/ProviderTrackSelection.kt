@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.ui.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/** Shared by playback and offline preparation; codec copies are not new voices. */
internal fun providerTrackOverride(
    tracks: Tracks,
    type: Int,
    providerIndex: Int,
    metadata: Map<String, String> = emptyMap(),
): TrackSelectionOverride? {
    if (providerIndex < 0) return null
    val prefix = if (type == C.TRACK_TYPE_AUDIO) "zona_audio" else "zona_video"
    val groupId = metadata["${prefix}_group_id"]?.takeIf { it.isNotBlank() }
    val groupIndex = metadata["${prefix}_group_index"]?.toIntOrNull()
    val named = if (type == C.TRACK_TYPE_AUDIO) metadata["movia_audio_label"]?.takeIf { it.isNotBlank() } else null
    if (named != null) {
        tracks.groups.filter { it.type == type }.forEach { group ->
            for (index in 0 until group.length) {
                if (group.isTrackSupported(index) && group.getTrackFormat(index).label == named)
                    return TrackSelectionOverride(group.mediaTrackGroup, index)
            }
        }
        // A persisted explicit rendition must never silently become another one.
        return null
    }
    val explicit = groupId?.let { id -> tracks.groups.firstOrNull { it.type == type && it.mediaTrackGroup.id == id } }
        ?: groupIndex?.let { tracks.groups.getOrNull(it)?.takeIf { group -> group.type == type } }
    if (explicit != null) return if (providerIndex in 0 until explicit.length && explicit.isTrackSupported(providerIndex))
        TrackSelectionOverride(explicit.mediaTrackGroup, providerIndex) else null
    val groups = tracks.groups.filter { it.type == type }
    val location = if (type == C.TRACK_TYPE_AUDIO) logicalAudioTrackLocations(groups.map { group ->
        (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            AudioRenditionIdentity(format.label, format.language, format.roleFlags, group.isTrackSupported(index))
        }
    }).getOrNull(providerIndex) else locateProviderTrackIndex(groups.map { it.length }, providerIndex)
    location ?: return null
    val group = groups[location.groupOrdinal]
    return if (group.isTrackSupported(location.trackIndex)) TrackSelectionOverride(group.mediaTrackGroup, location.trackIndex) else null
}
