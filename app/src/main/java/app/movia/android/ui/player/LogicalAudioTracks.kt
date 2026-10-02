package app.movia.android.ui.player

internal data class AudioRenditionIdentity(
    val label: String?,
    val language: String?,
    val roleFlags: Int = 0,
    val supported: Boolean = true,
)

/** Manifest voice ordinal counts logical names, not codecs or failover renditions. */
internal fun logicalAudioTrackLocations(groups: List<List<AudioRenditionIdentity>>): List<TrackOverrideLocation> {
    val result = mutableListOf<TrackOverrideLocation>()
    val seen = mutableMapOf<String, Int>()
    val support = mutableListOf<Boolean>()
    groups.forEachIndexed { groupOrdinal, formats ->
        formats.forEachIndexed { trackIndex, format ->
            val label = format.label?.trim()?.takeIf { it.isNotBlank() }
            val key = label?.let { "$it|"+format.language.orEmpty()+"|"+format.roleFlags }
            val previous = key?.let(seen::get)
            val location = TrackOverrideLocation(groupOrdinal, trackIndex)
            if (previous == null) {
                if (key != null) seen[key] = result.size
                result += location
                support += format.supported
            } else if (!support[previous] && format.supported) {
                result[previous] = location
                support[previous] = true
            }
        }
    }
    return result
}
