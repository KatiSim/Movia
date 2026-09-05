package app.movia.android.domain.playback

/** Failure classes used by problem-stream memory. */
enum class StreamFailureClass {
    NETWORK,
    NON_NETWORK,
}

/**
 * Mirrors the verified Zona V4 distinction without importing Media3 types here.
 * A Media3 InvalidResponseCodeException is covered through its superclass
 * hierarchy (HttpDataSourceException).
 */
object StreamFailureClassifier {
    private val zonaNetworkFailureClassNames = setOf(
        "ConnectException",
        "HttpDataSourceException",
    )

    fun fromThrowable(error: Throwable): StreamFailureClass {
        var current: Throwable? = error
        val seen = mutableSetOf<Throwable>()
        while (current != null && seen.add(current)) {
            var type: Class<*>? = current.javaClass
            while (type != null) {
                if (type.simpleName in zonaNetworkFailureClassNames) return StreamFailureClass.NETWORK
                type = type.superclass
            }
            current = current.cause
        }
        return StreamFailureClass.NON_NETWORK
    }

    fun fromReason(reason: String): StreamFailureClass = when (reason.trim().uppercase()) {
        // A watchdog timeout has the same retry intent as a transient network
        // failure even when Media3 has not surfaced a concrete exception yet.
        "STARTUP_TIMEOUT" -> StreamFailureClass.NETWORK
        else -> StreamFailureClass.NON_NETWORK
    }
}

/**
 * Network failures become problematic only after the verified Zona threshold.
 * Counts are keyed by the concrete locator (URL) and kept bounded.
 */
class StreamProblemTracker(
    private val networkFailureThreshold: Int = 3,
    private val maxEntries: Int = 64,
) {
    private val networkFailureCounts = linkedMapOf<String, Int>()

    fun shouldMarkProblem(candidate: StreamCandidate, failureClass: StreamFailureClass): Boolean {
        if (failureClass == StreamFailureClass.NON_NETWORK) return true
        val key = locatorKey(candidate)
        val count = (networkFailureCounts[key] ?: 0) + 1
        networkFailureCounts[key] = count
        trim()
        return count >= networkFailureThreshold.coerceAtLeast(1)
    }

    fun clear(candidate: StreamCandidate) {
        networkFailureCounts.remove(locatorKey(candidate))
    }

    fun reset() {
        networkFailureCounts.clear()
    }

    internal fun networkFailureCount(candidate: StreamCandidate): Int =
        networkFailureCounts[locatorKey(candidate)] ?: 0

    private fun locatorKey(candidate: StreamCandidate): String =
        candidate.url.trim().ifBlank { candidate.stableStreamId }

    private fun trim() {
        while (networkFailureCounts.size > maxEntries.coerceAtLeast(1)) {
            networkFailureCounts.remove(networkFailureCounts.keys.first())
        }
    }
}
