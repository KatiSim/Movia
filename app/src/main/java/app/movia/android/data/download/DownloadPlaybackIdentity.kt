package app.movia.android.data.download

import app.movia.android.domain.model.MediaRef

/** Idle/transitional player state has no identity and cannot own a download. */
internal object DownloadPlaybackIdentity {
    fun matches(request: MediaRef?, mediaId: String, season: Int?, episode: Int?): Boolean =
        request != null && mediaId.isNotBlank() && request.contentId == mediaId &&
            request.season == season && request.episode == episode
}
