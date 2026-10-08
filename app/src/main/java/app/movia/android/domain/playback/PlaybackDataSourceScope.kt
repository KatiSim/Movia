package app.movia.android.domain.playback

/** Captured by one MediaSource, including HLS/DASH loaders created after a switch. */
internal class PlaybackDataSourceScope<T>(
    profile: StreamRequestProfile,
    private val offlineCreator: (() -> T)?,
    private val networkCreator: (StreamRequestProfile) -> T,
) {
    private val profileSnapshot = profile.copy(headers = profile.headers.toMap())
    fun create(): T = offlineCreator?.invoke() ?: networkCreator(profileSnapshot)
}
