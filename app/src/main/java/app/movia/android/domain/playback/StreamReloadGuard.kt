package app.movia.android.domain.playback

/** One reload per concrete source until decoded video or a new explicit attempt. */
internal class StreamReloadGuard(private val maximumEntries: Int = 128) {
    private val attempted = linkedSetOf<String>()
    init { require(maximumEntries > 0) }
    fun tryAcquire(id: String): Boolean {
        if (id.isBlank() || id in attempted || attempted.size >= maximumEntries) return false
        attempted += id
        return true
    }
    fun onDecoded(id: String) { attempted.remove(id) }
    fun reset(id: String? = null) { if (id == null) attempted.clear() else attempted.remove(id) }
}
