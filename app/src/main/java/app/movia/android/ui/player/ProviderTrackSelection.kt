@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.ui.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/** Raw logical ordinals include unsupported renditions; UI rows may omit them. */
internal fun providerAudioOrdinals(tracks: Tracks): Map<Pair<Int, Int>, Int> {
    val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val identities = groups.map { group -> (0 until group.length).map { index ->
        val format = group.getTrackFormat(index)
        AudioRenditionIdentity(format.label?.trim()?.takeIf { it.isNotBlank() },
            format.language?.takeUnless { it.isBlank() || it == "und" }, format.roleFlags, group.isTrackSupported(index))
    } }
    val logical = logicalAudioTrackLocations(identities)
    val result = mutableMapOf<Pair<Int, Int>, Int>()
    var audioGroupOrdinal = 0
    tracks.groups.forEachIndexed { globalGroup, group ->
        if (group.type == C.TRACK_TYPE_AUDIO) {
            for (track in 0 until group.length) {
                val identity = identities[audioGroupOrdinal][track]
                val ordinal = logical.indexOfFirst { location ->
                    val other = identities[location.groupOrdinal][location.trackIndex]
                    location.groupOrdinal == audioGroupOrdinal && location.trackIndex == track ||
                        identity.label != null && identity.label == other.label &&
                        identity.language == other.language && identity.roleFlags == other.roleFlags
                }
                if (ordinal >= 0) result[globalGroup to track] = ordinal
            }
            audioGroupOrdinal++
        }
    }
    return result
}

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
    val explicit = groupId?.let { id -> tracks.groups.firstOrNull { it.type == type && it.mediaTrackGroup.id == id } }
        ?: groupIndex?.let { tracks.groups.getOrNull(it)?.takeIf { group -> group.type == type } }
    if (explicit != null) return if (providerIndex in 0 until explicit.length && explicit.isTrackSupported(providerIndex) &&
        (named == null || explicit.getTrackFormat(providerIndex).label == named))
        TrackSelectionOverride(explicit.mediaTrackGroup, providerIndex) else null
    val groups = tracks.groups.filter { it.type == type }
    val location = if (type == C.TRACK_TYPE_AUDIO) logicalAudioTrackLocations(groups.map { group ->
        (0 until group.length).map { index ->
            val format = group.getTrackFormat(index)
            AudioRenditionIdentity(format.label?.trim()?.takeIf { it.isNotBlank() },
                format.language?.takeUnless { it.isBlank() || it == "und" }, format.roleFlags, group.isTrackSupported(index))
        }
    }).getOrNull(providerIndex) else locateProviderTrackIndex(groups.map { it.length }, providerIndex)
    location ?: return null
    val group = groups[location.groupOrdinal]
    return if (group.isTrackSupported(location.trackIndex) &&
        (named == null || group.getTrackFormat(location.trackIndex).label == named))
        TrackSelectionOverride(group.mediaTrackGroup, location.trackIndex) else null
}
