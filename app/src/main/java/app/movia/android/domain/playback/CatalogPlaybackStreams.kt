package app.movia.android.domain.playback

import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.StreamOption

/** Keep the catalog's complete request profile and its original episode identity. */
fun catalogPlaybackStreams(content: MediaContent, ref: MediaRef): List<StreamOption> {
    if (content.id != ref.contentId || (ref.season == null) != (ref.episode == null)) return emptyList()
    return content.streams.filter { stream ->
        stream.url.isNotBlank() &&
            (stream.catalogMediaId.isNullOrBlank() || stream.catalogMediaId == ref.contentId) &&
            stream.seasonNumber == ref.season && stream.episodeNumber == ref.episode
    }
}
