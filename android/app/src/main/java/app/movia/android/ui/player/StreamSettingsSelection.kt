package app.movia.android.ui.player

import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.playback.StreamVariantSelection

/**
 * Player-facing adapter around the shared domain quality -> voice contract.
 * Media3 raw track labels are never used as provider voice identities here.
 */
internal object StreamSettingsSelection {
    fun qualityOptions(streams: List<StreamOption>): List<String> =
        StreamVariantSelection.qualityOptions(streams)

    fun defaultQuality(streams: List<StreamOption>): String? =
        StreamVariantSelection.defaultQuality(streams)

    fun matchingQualityOption(streams: List<StreamOption>, quality: String?): String? =
        StreamVariantSelection.matchingQualityOption(streams, quality)

    fun voiceOptions(streams: List<StreamOption>, quality: String?): List<String> =
        StreamVariantSelection.voiceOptions(streams, quality)

    fun bestVoiceForQuality(
        streams: List<StreamOption>,
        quality: String?,
        preferredVoice: String? = null,
    ): String? = StreamVariantSelection.bestVoiceForQuality(streams, quality, preferredVoice)

    fun select(
        streams: List<StreamOption>,
        voice: String?,
        quality: String?,
    ): StreamOption? = StreamVariantSelection.select(streams, voice, quality)
}
