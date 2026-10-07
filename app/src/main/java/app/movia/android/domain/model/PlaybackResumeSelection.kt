package app.movia.android.domain.model

/** Resume is bound to catalog identity and exact episode, even after a cache miss. */
internal fun selectExactResumeProgress(
    mediaRef: MediaRef,
    progressByMediaRef: Map<String, PlaybackProgress>,
    lastProgress: PlaybackProgress? = null,
): PlaybackProgress? =
    progressByMediaRef[mediaRef.storageKey]?.takeIf { it.mediaRef == mediaRef }
        ?: lastProgress?.takeIf { it.mediaRef == mediaRef }

/** Stored identity is authoritative; a display title never assigns a catalog ID. */
internal fun storedProgressMediaRef(
    mediaKey: String,
    contentId: String?,
    displayTitle: String,
): MediaRef? {
    val keyRef = MediaRef.fromStorageKey(mediaKey)
    val explicitId = contentId?.trim()?.takeIf { it.isNotEmpty() }
    if (keyRef != null) return keyRef.takeIf { explicitId == null || explicitId == it.contentId }
    return explicitId?.let { MediaRef.from(it, displayTitle) }
}
