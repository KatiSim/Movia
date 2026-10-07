package app.movia.android.domain.playback

/** Limits automatic preparation attempts, independently of the variant inventory.
 * A new user request/choice or a decoded frame starts a new failure episode. */
internal class PlaybackRecoveryBudget(
    private val maxAttempts: Int = 6,
    private val durationMs: Long = 60_000L,
) {
    init { require(maxAttempts > 0 && durationMs > 0) }
    private var startedAtMs: Long? = null
    private var attempts = 0

    fun isExhausted(nowMs: Long): Boolean =
        attempts >= maxAttempts || startedAtMs?.let { nowMs - it >= durationMs } == true

    fun tryAcquire(nowMs: Long): Boolean {
        if (isExhausted(nowMs)) return false
        if (startedAtMs == null) startedAtMs = nowMs
        attempts += 1
        return true
    }

    fun reset() { startedAtMs = null; attempts = 0 }
}
