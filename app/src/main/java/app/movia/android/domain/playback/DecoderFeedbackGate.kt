package app.movia.android.domain.playback

/** First-frame reporting belongs to each preparation, including same-URL recovery. */
internal class DecoderFeedbackGate {
    private var preparation = 0L
    private var claimed = false
    private var startedMs = 0L

    fun prepare(nowMs: Long) { preparation += 1; claimed = false; startedMs = nowMs }
    fun attemptId(): Long = preparation
    fun isCurrent(attemptId: Long): Boolean = preparation == attemptId
    fun latencyMs(nowMs: Long): Long = (nowMs - startedMs).coerceAtLeast(0)
    fun claimFirstFrame(): Boolean {
        if (preparation == 0L || claimed) return false
        claimed = true
        return true
    }
}
