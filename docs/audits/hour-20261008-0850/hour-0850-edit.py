from pathlib import Path
import json,hashlib
C=Path.home()/'.cache/movia-architecture-20261002';R=C/'repo'
base=json.loads((C/'block08-dirty-baseline.json').read_text());assert all(hashlib.sha256((R/n).read_bytes()).hexdigest()==h for n,h in base.items())
def change(path,old,new,count=1):
 p=R/path;s=p.read_text();assert s.count(old)==count,(path,s.count(old),old[:70]);p.write_text(s.replace(old,new))
domain=R/'app/src/main/java/app/movia/android/domain/playback'
(domain/'PlaybackDataSourceScope.kt').write_text('''package app.movia.android.domain.playback

/** Captured by one MediaSource, including HLS/DASH loaders created after a switch. */
internal class PlaybackDataSourceScope<T>(
    profile: StreamRequestProfile,
    private val offlineCreator: (() -> T)?,
    private val networkCreator: (StreamRequestProfile) -> T,
) {
    private val profileSnapshot = profile.copy(headers = profile.headers.toMap())
    fun create(): T = offlineCreator?.invoke() ?: networkCreator(profileSnapshot)
}
''')
(domain/'PlaybackLoadEvidence.kt').write_text('''package app.movia.android.domain.playback

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
''')
gate='app/src/main/java/app/movia/android/domain/playback/DecoderFeedbackGate.kt'
change(gate,'    private var startedMs = 0L','    private var startedMs = 0L\n    private var renderedFrame = false')
change(gate,'claimed = false; startedMs = nowMs','claimed = false; renderedFrame = false; startedMs = nowMs')
change(gate,'    fun attemptId(): Long', '    fun onRenderedFrame() { if (preparation > 0L) renderedFrame = true }\n    fun hasRenderedFrame(): Boolean = renderedFrame\n    fun shouldRecoverStartup(playWhenReady: Boolean): Boolean = preparation > 0L && playWhenReady && !renderedFrame\n    fun attemptId(): Long')
f='app/src/main/java/app/movia/android/ui/player/PlaybackSession.kt'
change(f,'import android.content.Context','import app.movia.android.domain.playback.PlaybackDataSourceScope\nimport app.movia.android.domain.playback.PlaybackLoadEvidence\nimport android.content.Context')
change(f,'    private val requestProfile: StreamRequestProfile,\n) : DataSource {','    private val requestProfile: StreamRequestProfile,\n    private val loadEvidence: PlaybackLoadEvidence? = null,\n) : DataSource {')
change(f,'    private val listeners = mutableListOf<TransferListener>()','    private val listeners = mutableListOf<TransferListener>()\n    private var loadPhase = "OTHER"\n    private var loadStartedMs = 0L\n    private var transferredBytes = 0L\n    private var hasOpenAttempt = false')
change(f,'    override fun open(dataSpec: DataSpec): Long {\n        fun openFreshDelegate()', '''    override fun open(dataSpec: DataSpec): Long {
        loadStartedMs = SystemClock.elapsedRealtime()
        transferredBytes = 0L
        hasOpenAttempt = true
        val path = dataSpec.uri.path.orEmpty().lowercase()
        loadPhase = when {
            path.endsWith(".m3u8") || path.endsWith(".mpd") -> "MANIFEST_SUFFIX"
            path.endsWith(".ts") || path.endsWith(".m4s") -> "SEGMENT_SUFFIX"
            else -> "OTHER"
        }
        fun openFreshDelegate()''')
change(f,'            open = ::openFreshDelegate,','''            open = {
                try {
                    openFreshDelegate().also {
                        loadEvidence?.record("OPEN", loadPhase, SystemClock.elapsedRealtime() - loadStartedMs)
                    }
                } catch (error: Exception) {
                    recordLoadError(error)
                    throw error
                }
            },''')
change(f,'    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =\n        delegate?.read(buffer, offset, length) ?: -1','''    private fun recordLoadError(error: Exception) {
        val status = (error as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
        loadEvidence?.record("ERROR", loadPhase, SystemClock.elapsedRealtime() - loadStartedMs,
            errorClass = error.javaClass.simpleName, httpStatus = status)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        (delegate?.read(buffer, offset, length) ?: -1).also { if (it > 0) transferredBytes += it }
    } catch (error: Exception) {
        recordLoadError(error)
        throw error
    }''')
# First close belongs to DynamicHeaderDataSource, not factory/session.
change(f,'    override fun close() {\n        delegate?.close()\n        delegate = null\n    }','''    override fun close() {
        try { delegate?.close() } finally {
            delegate = null
            if (hasOpenAttempt) {
                loadEvidence?.record("CLOSE", loadPhase, SystemClock.elapsedRealtime() - loadStartedMs,
                    transferred = transferredBytes)
                hasOpenAttempt = false
            }
        }
    }''')
change(f,'    private val decoderFeedbackGate = DecoderFeedbackGate()','''    private val decoderFeedbackGate = DecoderFeedbackGate()
    private var preparationLoadEvidence = PlaybackLoadEvidence()
    internal fun sourceLoadEvidence(): Map<String, Any> = preparationLoadEvidence.snapshot()''')
change(f,'                    watchdogJob?.cancel()\n                    stallWatchdogJob?.cancel()\n                }\n                publishSnapshot()', '                    if (decoderFeedbackGate.hasRenderedFrame()) watchdogJob?.cancel()\n                    stallWatchdogJob?.cancel()\n                }\n                publishSnapshot()')
change(f,'                        watchdogJob?.cancel()\n                        stallWatchdogJob?.cancel()\n                    }\n                    Player.STATE_BUFFERING', '                        if (decoderFeedbackGate.hasRenderedFrame()) watchdogJob?.cancel()\n                        stallWatchdogJob?.cancel()\n                    }\n                    Player.STATE_BUFFERING')
change(f,'            override fun onRenderedFirstFrame() {\n                activeCandidate', '''            override fun onRenderedFirstFrame() {
                if (activeConsumedUri == null || player.currentMediaItem?.localConfiguration?.uri?.toString() != activeConsumedUri) return
                decoderFeedbackGate.onRenderedFrame()
                watchdogJob?.cancel()
                activeCandidate''')
change(f,'            if (player.playbackState != Player.STATE_READY && !player.isPlaying) {','            if (decoderFeedbackGate.shouldRecoverStartup(desiredPlayWhenReady)) {')
change(f,'                handleCandidateFailure("STARTUP_TIMEOUT", resumePositionMs, generation)','                handleCandidateFailure("DECODER_STARTUP_TIMEOUT", resumePositionMs, generation)')
old='''        dataSourceFactory.setOfflineFactory(if (candidate.provider == "offline") {
            OfflineMediaStore.playbackFactory(appContext, MediaRef(request.mediaId, request.seasonNumber, request.episodeNumber))
        } else null)
        dataSourceFactory.setRequestProfile(StreamRequestProfile.from(candidate, uri))'''
new='''        val offlineFactory = if (candidate.provider == "offline") {
            OfflineMediaStore.playbackFactory(appContext, MediaRef(request.mediaId, request.seasonNumber, request.episodeNumber))
        } else null
        val evidence = PlaybackLoadEvidence()
        preparationLoadEvidence = evidence
        val dataSourceScope = PlaybackDataSourceScope(
            StreamRequestProfile.from(candidate, uri), offlineFactory?.let { it::createDataSource },
        ) { profile -> DynamicHeaderDataSource(appContext, profile, evidence) }
        val scopedFactory = DataSource.Factory { dataSourceScope.create() }'''
change(f,old,new)
change(f,'            player.setMediaItem(buildMediaItem(request, candidate, uri))\n            player.prepare()\n            if (resumePositionMs > 0L) player.seekTo(resumePositionMs)', '''            val source = DefaultMediaSourceFactory(scopedFactory, extractorsFactory)
                .createMediaSource(buildMediaItem(request, candidate, uri))
            // Bind profile, offline factory and start position before any loader is created.
            player.setMediaSource(source, resumePositionMs.coerceAtLeast(0L))
            player.prepare()''')
# An explicit supported in-place quality choice must not keep a stale failed leaf pinned.
change(f,'            playbackRequest = playbackRequest?.copy(requestedQuality = requestedVideoQuality)','            playbackRequest = playbackRequest?.let { StreamSettingsSelection.withPreparedQuality(it, requestedVideoQuality, activeCandidate?.stableStreamId) }')
change(f,'(_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(requestedQuality = requestedVideoQuality, fallbackReason = null)', '(_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(requestedQuality = requestedVideoQuality, requestedStreamId = playbackRequest?.requestedStreamId, fallbackReason = null)')
settings='app/src/main/java/app/movia/android/ui/player/StreamSettingsSelection.kt'
change(settings,'internal object StreamSettingsSelection {','''internal object StreamSettingsSelection {
    /** A new manual track choice adopts the prepared leaf after automatic fallback. */
    fun withPreparedQuality(request: app.movia.android.domain.playback.PlaybackRequest, quality: String,
        preparedStreamId: String?): app.movia.android.domain.playback.PlaybackRequest = request.copy(
            requestedQuality = quality,
            requestedStreamId = preparedStreamId?.takeIf { it.isNotBlank() } ?: request.requestedStreamId,
        )
''')
agent='app/src/main/java/app/movia/android/agent/AgentControlRuntime.kt'
change(agent,'        var playerErrorCode: String? = null','        var sourceLoadEvidence: Map<String, Any> = emptyMap()\n        var playerErrorCode: String? = null')
change(agent,'                val player = session.player','                sourceLoadEvidence = session.sourceLoadEvidence()\n                val player = session.player')
change(agent,'            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)\n            put("legacyEngine",', '            put("schemaVersion", MOVIA_AGENT_SCHEMA_VERSION)\n            put("sourceLoadEvidence", JSONObject(sourceLoadEvidence))\n            put("legacyEngine",')
print('Scoped MediaSource, atomic resume, decoder startup watchdog, manual quality pin and bounded source I/O diagnostics edited')
