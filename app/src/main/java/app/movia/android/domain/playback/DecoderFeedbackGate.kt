package app.movia.android.domain.playback

/** First-frame reporting belongs to each preparation, including same-URL recovery. */
internal class DecoderFeedbackGate {
    private var preparation = 0L
    private var claimed = false
    private var startedMs = 0L
    private var renderedFrame = false
    private var frameVersion = 0L

    fun prepare(nowMs: Long) { preparation += 1; claimed = false; renderedFrame = false; startedMs = nowMs }
    fun onRenderedFrame() { if (preparation > 0L) { renderedFrame = true; frameVersion += 1 } }
    fun renderedFrameVersion(): Long = frameVersion
    fun recoveryIsCurrent(attemptId: Long, version: Long): Boolean = preparation > 0L && isCurrent(attemptId) && frameVersion == version
    fun hasRenderedFrame(): Boolean = renderedFrame
    fun shouldRecoverStartup(playWhenReady: Boolean): Boolean = preparation > 0L && playWhenReady && !renderedFrame
    fun attemptId(): Long = preparation
    fun isCurrent(attemptId: Long): Boolean = preparation == attemptId
    fun latencyMs(nowMs: Long): Long = (nowMs - startedMs).coerceAtLeast(0)
    fun claimFirstFrame(): Boolean {
        if (preparation == 0L || claimed) return false
        claimed = true
        return true
    }
}
