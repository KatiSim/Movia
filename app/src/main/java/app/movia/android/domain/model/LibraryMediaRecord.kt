package app.movia.android.domain.model

/** A persisted library row with canonical identity and presentation data kept separately. */
data class LibraryMediaRecord(
    val mediaKey: String,
    val contentId: String?,
    val title: String,
    val updatedAt: Long,
) {
    val mediaRef: MediaRef?
        get() = MediaRef.fromStorageKey(mediaKey)
            ?: MediaRef.from(contentId, title)
}
