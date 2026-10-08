package app.movia.android.domain.playback

/** Bounded per-preparation I/O facts; never stores locator, headers or exception text. */
internal class PlaybackLoadEvidence(private val maxEvents: Int = 32) {
    init { require(maxEvents > 0) }
    private val events = ArrayDeque<Map<String, Any>>()
    private var opens = 0L
    private var errors = 0L
    private var bytes = 0L
    @Synchronized fun record(event: String, phase: String, elapsedMs: Long = 0, transferred: Long = 0,
        errorClass: String? = null, httpStatus: Int? = null) {
        require(event in setOf("OPEN", "ERROR", "CLOSE"))
        require(phase in setOf("MANIFEST_SUFFIX", "SEGMENT_SUFFIX", "OTHER"))
        if (event == "OPEN") opens++
        if (event == "ERROR") errors++
        if (event == "CLOSE") bytes += transferred.coerceAtLeast(0)
        val item = linkedMapOf<String, Any>("event" to event, "phase" to phase,
            "elapsedMs" to elapsedMs.coerceAtLeast(0), "bytes" to transferred.coerceAtLeast(0))
        errorClass?.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,96}")) }?.let { item["errorClass"] = it }
        httpStatus?.takeIf { it in 100..599 }?.let { item["httpStatus"] = it }
        if (events.size == maxEvents) events.removeFirst()
        events.addLast(item)
    }
    @Synchronized fun snapshot(): Map<String, Any> = mapOf("opens" to opens, "errors" to errors,
        "closedRequestBytes" to bytes, "events" to events.map { it.toMap() }, "maxEvents" to maxEvents,
        "classification" to "Filename suffix only; OPEN/bytes are not decoder evidence")
}
