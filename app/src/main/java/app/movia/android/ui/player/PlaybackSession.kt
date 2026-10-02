@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.ui.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.session.MediaSession
import app.movia.android.domain.model.ActiveStreamSelection
import app.movia.android.domain.model.MediaRef
import app.movia.android.data.download.OfflineMediaStore
import androidx.media3.datasource.cache.SimpleCache
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.PlaybackStatus
import app.movia.android.domain.model.PlaybackSwitchState
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.sameRequestedVariant
import app.movia.android.domain.legacy.LegacyPlaybackResolver
import app.movia.android.domain.playback.DomainPlaybackResolver
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.PlaybackResolverResult
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamFailureClass
import app.movia.android.domain.playback.StreamFailureClassifier
import app.movia.android.domain.playback.StreamDeduplicator
import app.movia.android.domain.playback.StreamProblemTracker
import app.movia.android.domain.playback.StreamRanker
import app.movia.android.domain.playback.StreamRankingContext
import app.movia.android.domain.playback.openWithSingleRetry
import app.movia.android.domain.playback.StreamRequestProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder

internal object MoviaPlaybackRegistry {
    var current: PlaybackSession? = null
        internal set

    @Synchronized
    fun obtain(context: Context): PlaybackSession =
        current ?: PlaybackSession(context.applicationContext)
}

private const val TAG = "MoviaPlayer"
private const val STARTUP_WATCHDOG_MS = 10_000L
private const val RELOAD_TIMEOUT_MS = 10_000L
private const val STALL_WATCHDOG_MS = 10_000L
private const val RESOLVER_TIMEOUT_MS = 12_000L
private const val MAX_PROBLEM_MEMORY = 64

internal data class TrackOverrideLocation(val groupOrdinal: Int, val trackIndex: Int)

internal fun locateProviderTrackIndex(groupLengths: List<Int>, providerIndex: Int): TrackOverrideLocation? {
    if (providerIndex < 0) return null
    var remaining = providerIndex
    for ((ordinal, rawLength) in groupLengths.withIndex()) {
        val length = rawLength.coerceAtLeast(0)
        if (remaining < length) return TrackOverrideLocation(ordinal, remaining)
        remaining -= length
    }
    return null
}

internal fun canSwitchTracksInPlace(current: StreamCandidate?, target: StreamCandidate): Boolean {
    current ?: return false
    if (target.audioTrackIndex == null && target.videoTrackIndex == null) return false
    if (current.url.isBlank() || !current.url.trim().equals(target.url.trim(), ignoreCase = false)) return false
    return current.headers == target.headers &&
        current.userAgent == target.userAgent &&
        current.mimeType == target.mimeType &&
        current.drmScheme == target.drmScheme &&
        current.drmLicenseUrl == target.drmLicenseUrl &&
        current.subtitles == target.subtitles &&
        current.seasonNumber == target.seasonNumber &&
        current.episodeNumber == target.episodeNumber &&
        current.transport.equals(target.transport, ignoreCase = true)
}

private fun inferredMimeType(uri: String): String? {
    val clean = uri.substringBefore("?").lowercase()
    return when {
        clean.endsWith(".m3u8") || clean.contains("m3u8") || clean.contains("/hls/") -> MimeTypes.APPLICATION_M3U8
        clean.endsWith(".mp4") || clean.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        clean.endsWith(".mpd") || clean.contains("mpd") -> MimeTypes.APPLICATION_MPD
        clean.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        clean.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        clean.endsWith(".ts") -> MimeTypes.VIDEO_MP2T
        else -> null
    }
}

private fun safeMimeType(candidate: StreamCandidate, consumedUri: String): String? {
    val declared = candidate.mimeType?.trim()?.takeIf {
        it.length <= 128 &&
            it.matches(Regex("[A-Za-z0-9!#$&^_.+\\-/]+/[A-Za-z0-9!#$&^_.+\\-]+"))
    }
    return declared ?: inferredMimeType(consumedUri)
}

private fun drmUuidForScheme(rawScheme: String?): java.util.UUID? = when (rawScheme?.trim()?.lowercase()) {
    "widevine", "com.widevine.alpha" -> C.WIDEVINE_UUID
    "playready", "com.microsoft.playready" -> C.PLAYREADY_UUID
    "clearkey", "org.w3.clearkey" -> C.CLEARKEY_UUID
    else -> null
}

private fun safeDrmLicenseUrl(rawUrl: String?): String? {
    val value = rawUrl?.trim().orEmpty()
    if (value.length > 4096 || value.any { it == '\r' || it == '\n' }) return null
    return value.takeIf {
        it.startsWith("https://", ignoreCase = true) ||
            it.startsWith("http://127.0.0.1:", ignoreCase = true) ||
            it.startsWith("http://localhost:", ignoreCase = true)
    }
}

private fun safeSubtitleMimeType(raw: String?, url: String): String {
    val declared = raw?.trim()?.takeIf {
        it.length <= 128 &&
            it.matches(Regex("[A-Za-z0-9!#$&^_.+\\-/]+/[A-Za-z0-9!#$&^_.+\\-]+"))
    }
    if (declared != null) return declared
    return when {
        url.substringBefore("?").lowercase().endsWith(".srt") ||
            url.substringBefore("?").lowercase().endsWith(".sub") -> "application/x-subrip"
        url.substringBefore("?").lowercase().endsWith(".ass") ||
            url.substringBefore("?").lowercase().endsWith(".ssa") -> "text/x-ssa"
        else -> "text/vtt"
    }
}

private fun safeExternalSubtitleConfigurations(
    candidate: StreamCandidate,
): List<MediaItem.SubtitleConfiguration> = candidate.subtitles.mapNotNull { subtitle ->
    val value = subtitle.url.trim()
    if (value.length > 4096 || value.any { it == '\r' || it == '\n' }) return@mapNotNull null
    val parsed = Uri.parse(value)
    if (parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
        return@mapNotNull null
    }
    runCatching {
        MediaItem.SubtitleConfiguration.Builder(parsed)
            .setMimeType(safeSubtitleMimeType(subtitle.mimeType, value))
            .setLanguage(subtitle.language.trim().takeIf { it.isNotBlank() })
            .setLabel(subtitle.label.trim().takeIf { it.isNotBlank() })
            .build()
    }.getOrNull()
}

/**
 * A Media3 data source whose request profile is selected by the active
 * StreamCandidate. No provider headers are inferred from a hostname.
 */
class DynamicHeaderDataSourceFactory(
    private val context: Context,
    private val userAgent: String = StreamRequestProfile.DEFAULT_STREAM_USER_AGENT,
) : DataSource.Factory {
    @Volatile
    private var requestProfile = StreamRequestProfile(userAgent = userAgent)

    @Volatile private var offlineFactory: DataSource.Factory? = null

    fun setOfflineFactory(factory: DataSource.Factory?) { offlineFactory = factory }

    fun setRequestProfile(profile: StreamRequestProfile) {
        requestProfile = profile
    }

    override fun createDataSource(): DataSource = offlineFactory?.createDataSource() ?: DynamicHeaderDataSource(
        context.applicationContext,
        requestProfile,
    )
}

class DynamicHeaderDataSource(
    private val context: Context,
    private val requestProfile: StreamRequestProfile,
) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    /** Compatibility constructor for callers that only have a default UA. */
    constructor(context: Context, userAgent: String) : this(
        context,
        StreamRequestProfile(userAgent = userAgent),
    )

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        fun openFreshDelegate(): Long {
            val headers = requestProfile.headersFor(dataSpec.uri.toString()).toMutableMap()
            if (headers.none { it.key.equals("accept", ignoreCase = true) }) {
                headers["Accept"] = "*/*"
            }
            val httpFactory: androidx.media3.datasource.HttpDataSource.Factory = if (requestProfile.publicNetworkOnly) {
                androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                    app.movia.android.domain.legacy.LegacyMediaHttp.client(requestProfile))
                    .setUserAgent(requestProfile.userAgent)
                    .setDefaultRequestProperties(headers)
            } else DefaultHttpDataSource.Factory()
                .setUserAgent(requestProfile.userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(5_000)
                .setReadTimeoutMs(8_000)
                .setDefaultRequestProperties(headers)
            val dataSource = DefaultDataSource.Factory(context, httpFactory).createDataSource()
            listeners.forEach(dataSource::addTransferListener)
            delegate = dataSource
            return dataSource.open(dataSpec)
        }

        return openWithSingleRetry(
            resetBeforeRetry = {
                runCatching { delegate?.close() }
                delegate = null
            },
            open = ::openFreshDelegate,
        )
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders ?: emptyMap()

    override fun close() {
        delegate?.close()
        delegate = null
    }
}

class PlaybackSession(context: Context) {
    private val appContext = context.applicationContext
    private val extractorsFactory = DefaultExtractorsFactory().apply {
        setConstantBitrateSeekingEnabled(true)
        setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
    }

    private val dataSourceFactory = DynamicHeaderDataSourceFactory(appContext)
    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            15_000,
            45_000,
            1_000,
            2_000,
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(10_000, true)
        .build()

    /** One PlaybackSession owns exactly one player and one MediaSession. */
    val player: ExoPlayer = ExoPlayer.Builder(appContext)
        .setLooper(Looper.getMainLooper())
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSourceFactory)
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .build()
        .apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _streamOptions = MutableStateFlow<List<StreamOption>>(emptyList())
    val streamOptions: StateFlow<List<StreamOption>> = _streamOptions.asStateFlow()

    private val _choices = MutableStateFlow(PlaybackChoices())
    val choices: StateFlow<PlaybackChoices> = _choices.asStateFlow()
    var recordHistory: Boolean = true
        private set
    private var requestedVideoQuality: String = "Auto"
    private var userSelectedAutoQuality = false
    private var userSelectedAutoAudio = false
    private var requestedAudioTrack: String? = null
    private var desiredPlayWhenReady = false
    private var frameProbe: PlaybackFrameProbe? = null
    val probeFrames: Long get() = frameProbe?.frames ?: 0L
    fun setFrameProbe(enabled: Boolean) {
        require(appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Probe requires a debug build" }
        frameProbe?.close()
        frameProbe = if (enabled) PlaybackFrameProbe(player) else null
    }
    private var feedbackGeneration = -1L
    private var requestStartedMs = 0L
    var readyLatencyMs: Long? = null
        private set
    var firstFrameLatencyMs: Long? = null
        private set

    private var playbackGeneration = 0L
    private var playbackRequest: PlaybackRequest? = null
    private var candidates: List<StreamCandidate> = emptyList()
    private var activeCandidate: StreamCandidate? = null
    private var activeConsumedUri: String? = null
    private val failedStreamIds = linkedSetOf<String>()
    private val problemTracker = StreamProblemTracker(maxEntries = MAX_PROBLEM_MEMORY)
    private val reloadAttemptedStreamIds = linkedSetOf<String>()
    private var recoveryAttemptCount = 0
    private var recoveryAttemptBudget = 1
    private var watchdogJob: Job? = null
    private var stallWatchdogJob: Job? = null
    private var recoveryJob: Job? = null
    private var discoveryJob: Job? = null
    private var startupHandoverJob: Job? = null
    private var startupHandoverUsed = false
    private var webResolveJob: Job? = null
    private var appliedTrackSelectionKey: String? = null

    // Compatibility getters; state and candidate metadata remain authoritative.
    val activeTitle: String? get() = _state.value.displayTitle.takeIf { _state.value.hasMedia }
    val activeSourceUri: String? get() = activeConsumedUri
    val isOffline: Boolean get() = activeCandidate?.provider == "offline"
    val isPlaying: Boolean get() = _state.value.isPlaying
    val playWhenReady: Boolean get() = _state.value.playWhenReady
    val playbackState: Int
        get() = when (_state.value.status) {
            PlaybackStatus.IDLE -> Player.STATE_IDLE
            PlaybackStatus.BUFFERING -> Player.STATE_BUFFERING
            PlaybackStatus.READY -> Player.STATE_READY
            PlaybackStatus.ENDED -> Player.STATE_ENDED
        }

    val mediaSession: MediaSession = MediaSession.Builder(appContext, player).build()

    init {
        MoviaPlaybackRegistry.current = this
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    watchdogJob?.cancel()
                    stallWatchdogJob?.cancel()
                }
                publishSnapshot()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        if (readyLatencyMs == null && requestStartedMs > 0L) readyLatencyMs = SystemClock.elapsedRealtime() - requestStartedMs
                        watchdogJob?.cancel()
                        stallWatchdogJob?.cancel()
                    }
                    Player.STATE_BUFFERING -> {
                        if (player.playWhenReady) {
                            startStallWatchdog(playbackGeneration)
                        }
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> {
                        stallWatchdogJob?.cancel()
                    }
                }
                publishSnapshot()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyCandidateTrackOverrides(tracks)
                applyUserTrackPreferences(tracks)
                publishSnapshot()
            }

            override fun onRenderedFirstFrame() {
                if (firstFrameLatencyMs == null && requestStartedMs > 0L) {
                    firstFrameLatencyMs = SystemClock.elapsedRealtime() - requestStartedMs
                }
                publishSnapshot()
                recordNativeFirstFrame()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                desiredPlayWhenReady = playWhenReady
                if (!playWhenReady) {
                    stallWatchdogJob?.cancel()
                } else if (player.playbackState == Player.STATE_BUFFERING) {
                    startStallWatchdog(playbackGeneration)
                }
                publishSnapshot()
            }

            override fun onPlayerError(error: PlaybackException) {
                watchdogJob?.cancel()
                stallWatchdogJob?.cancel()
                val candidateId = activeCandidate?.stableStreamId ?: "unknown"
                // Error messages may contain URLs or provider material; only
                // log the stable ID and Media3 error code.
                Log.w(TAG, "Playback candidate failed: id=$candidateId code=${error.errorCodeName}")
                val position = maxOf(
                    0L,
                    _state.value.currentPositionMs,
                    player.currentPosition.coerceAtLeast(0L),
                )
                handleCandidateFailure(
                    "PLAYER_ERROR",
                    position,
                    playbackGeneration,
                    StreamFailureClassifier.fromThrowable(error),
                )
            }
        })
        scope.launch {
            while (isActive) {
                if (_state.value.hasMedia) publishSnapshot()
                delay(250L)
            }
        }
    }

    /** Provider voices and Media3 tracks are separate identity spaces. */
    fun selectVideoQuality(value: String): Boolean {
        val quality = value.trim()
        userSelectedAutoQuality = quality.equals("Auto", true)
        val tracks = playbackChoices(player.currentTracks, player.videoFormat?.height ?: 0)
        val wantedHeight = qualityHeight(quality)
        if (quality.equals("Auto", true) || tracks.video.any { it.height == wantedHeight }) {
            requestedVideoQuality = if (quality.equals("Auto", true)) "Auto" else quality
            playbackRequest = playbackRequest?.copy(requestedQuality = requestedVideoQuality)
            _state.value = _state.value.copy(activeStreamSelection =
                (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(requestedQuality = requestedVideoQuality, fallbackReason = null))
            applyUserTrackPreferences(player.currentTracks)
            publishSnapshot()
            return true
        }
        val voice = _state.value.activeStreamSelection?.activeVoice
        val option = streamOptions.value.firstOrNull {
            qualityHeight(it.quality) == wantedHeight && (voice.isNullOrBlank() || it.voice.equals(voice, true))
        } ?: return false
        requestedVideoQuality = quality
        switchToStream(option)
        return true
    }

    fun selectVoice(value: String): Boolean {
        val voice = value.trim()
        userSelectedAutoAudio = voice.equals("Auto", true)
        if (voice.equals("Auto", true)) {
            requestedAudioTrack = null
            playbackRequest = playbackRequest?.copy(requestedVoice = "Auto", requestedStreamId = null)
            _state.value = _state.value.copy(activeStreamSelection =
                (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(requestedVoice = "Auto"))
            applyUserTrackPreferences(player.currentTracks)
            publishSnapshot()
            return true
        }
        val currentChoices = playbackChoices(player.currentTracks, player.videoFormat?.height ?: 0)
        val qualityAlreadySupported = requestedVideoQuality.equals("Auto", true) ||
            currentChoices.video.any { it.height == qualityHeight(requestedVideoQuality) }
        val preparedVoice = if (qualityAlreadySupported) streamOptions.value.firstOrNull { option ->
            option.voice.equals(voice, true) && option.audioTrackIndex != null &&
                canSwitchTracksInPlace(activeCandidate, StreamCandidate.fromStreamOption(
                    option, _state.value.seasonNumber, _state.value.episodeNumber))
        } else null
        val option = preparedVoice ?: StreamSettingsSelection.select(streamOptions.value, voice, requestedVideoQuality)
        if (option != null && option.voice.equals(voice, true)) {
            requestedAudioTrack = null
            if (activeCandidate?.stableStreamId != option.streamId) switchToStream(option)
            else {
                // Selecting the same voice after Auto still has to restore its override.
                appliedTrackSelectionKey = null
                applyCandidateTrackOverrides(player.currentTracks)
                applyUserTrackPreferences(player.currentTracks)
            }
            playbackRequest = playbackRequest?.copy(requestedVoice = voice, requestedQuality = requestedVideoQuality)
            _state.value = _state.value.copy(activeStreamSelection =
                (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(requestedVoice = voice, requestedQuality = requestedVideoQuality))
            publishSnapshot()
            return true
        }
        val choice = playbackChoices(player.currentTracks, player.videoFormat?.height ?: 0).audio
            .firstOrNull { it.id == voice || it.label.equals(voice, true) } ?: return false
        requestedAudioTrack = choice.label
        playbackRequest = playbackRequest?.copy(requestedVoice = choice.label, requestedStreamId = activeCandidate?.stableStreamId)
        _state.value = _state.value.copy(audioTrackId = choice.id, activeStreamSelection =
            (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(requestedVoice = choice.label))
        applyUserTrackPreferences(player.currentTracks)
        publishSnapshot()
        return true
    }

    /** Persist the logical rendition, rather than a codec-dependent group offset. */
    internal fun selectedDownloadAudio(): Pair<Int, String?>? {
        val audio = playbackChoices(player.currentTracks, 0).audio
        val index = audio.indexOfFirst { it.selected }
        if (index < 0) return null
        val selected = audio[index]
        val format = selected.override.mediaTrackGroup.getFormat(selected.override.trackIndices.first())
        return index to format.label?.takeIf { it.isNotBlank() }
    }

    private fun applyUserTrackPreferences(tracks: Tracks) {
        val choices = playbackChoices(tracks, player.videoFormat?.height ?: 0)
        val builder = player.trackSelectionParameters.buildUpon()
        if (requestedVideoQuality == "Auto") {
            // Respect a provider's explicit video index unless the user chose Auto.
            if (userSelectedAutoQuality || activeCandidate?.videoTrackIndex == null) builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        } else {
            val track = choices.video.firstOrNull { it.height == qualityHeight(requestedVideoQuality) }
            if (track != null) builder.setOverrideForType(track.override)
            else if (choices.video.isNotEmpty()) {
                _state.value = _state.value.copy(activeStreamSelection =
                    (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(fallbackReason = "QUALITY_UNAVAILABLE_FOR_VOICE"))
            }
        }
        if (requestedAudioTrack == null && (userSelectedAutoAudio || activeCandidate?.audioTrackIndex == null)) builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        requestedAudioTrack?.let { wanted ->
            choices.audio.firstOrNull { it.id == wanted || it.label.equals(wanted, true) }?.let {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(it.override)
            }
        }
        val parameters = builder.build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
    }

    private fun recordNativeFirstFrame() {
        val candidate = activeCandidate ?: return
        val request = playbackRequest ?: return
        val isLegacy = candidate.transportMetadata["legacy_engine"] == "3.466"
        val sourceId = candidate.sourceId?.takeIf { it.isNotBlank() }
        if (sourceId == null && !isLegacy) return
        if (feedbackGeneration == playbackGeneration || isOffline) return
        feedbackGeneration = playbackGeneration
        val observation = org.json.JSONObject().put("sourceId", sourceId)
            .put("startupLatencyMs", firstFrameLatencyMs ?: readyLatencyMs ?: 0L)
            .put("actualQuality", player.videoFormat?.height?.takeIf { it > 0 }?.let { "${it}p" } ?: "Auto")
            .put("actualQualities", org.json.JSONArray(choices.value.video.map { it.label }))
            .put("actualAudioTracks", org.json.JSONArray().apply { choices.value.audio.forEach {
                put(org.json.JSONObject().put("label", it.label).put("language", it.language ?: "und"))
            } })
        scope.launch(Dispatchers.IO) {
            runCatching {
                val token = java.io.File(appContext.filesDir, "agent/movia-agent.token").readText().trim()
                val endpoint = if (isLegacy) "legacy-media3-success" else "media3-success"
                val payload = if (isLegacy) {
                    val facts = org.json.JSONObject(observation.toString()).apply { remove("sourceId") }
                    LegacyPlaybackResolver.firstFramePayload(candidate, request, facts)
                } else observation
                val connection = java.net.URL("http://127.0.0.1:8888/internal/playback-availability/$endpoint").openConnection() as java.net.HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 2000
                    connection.readTimeout = 3000
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("Authorization", "Bearer " + token)
                    connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                    connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
            }
        }
    }

    private fun nextPlaybackGeneration(): Long {
        requestStartedMs = SystemClock.elapsedRealtime()
        readyLatencyMs = null
        firstFrameLatencyMs = null
        playbackGeneration += 1L
        return playbackGeneration
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        generation == playbackGeneration

    private fun canonicalRequestTitle(value: String): String = value.trim()
        .replace(
            Regex("\\s*[·•]\\s*S\\d{1,3}E\\d{1,3}(?:\\s*[·•]\\s*Эпизод\\s+\\d+)?$", RegexOption.IGNORE_CASE),
            "",
        )
        .replace(Regex("\\s+S\\d{1,3}E\\d{1,3}$", RegexOption.IGNORE_CASE), "")
        .substringBefore(" (")
        .trim()

    private fun transportFor(url: String): String = when {
        url.startsWith("magnet:", ignoreCase = true) -> "torrent_p2p"
        url.contains("/stream?", ignoreCase = true) -> "local_gateway"
        url.contains(".m3u8", ignoreCase = true) -> "hls"
        url.contains(".mpd", ignoreCase = true) -> "dash"
        else -> "direct"
    }

    private fun genericStreamOption(
        url: String,
        season: Int?,
        episode: Int?,
    ): StreamOption = StreamOption(
        voice = "Не указано",
        quality = "Не указано",
        url = url.trim(),
        source = if (url.trim().startsWith("magnet:", ignoreCase = true)) "torrent_p2p" else "direct",
        seasonNumber = season,
        episodeNumber = episode,
        transport = transportFor(url),
    )

    private fun initialCandidates(
        sourceUri: String?,
        candidateStreams: List<String>,
        candidateStreamOptions: List<StreamOption>,
        season: Int?,
        episode: Int?,
    ): List<StreamCandidate> {
        // Catalog options are authoritative. Compatibility URL arguments are
        // only materialized when their locator is not already represented by
        // a metadata-rich option.
        val optionSeeds = candidateStreamOptions.filter { it.url.isNotBlank() }
        val representedUrls = optionSeeds.mapTo(mutableSetOf()) { it.url.trim() }
        val compatibilitySeeds = buildList {
            sourceUri?.trim()?.takeIf { it.isNotBlank() }?.let { value ->
                if (representedUrls.add(value)) add(genericStreamOption(value, season, episode))
            }
            candidateStreams.forEach { raw ->
                val value = raw.trim()
                if (value.isNotBlank() && representedUrls.add(value)) {
                    add(genericStreamOption(value, season, episode))
                }
            }
        }
        return (optionSeeds + compatibilitySeeds).map {
            StreamCandidate.fromStreamOption(it, season, episode)
        }
    }

    private fun publishCandidateOptions() {
        // StreamOption has no problematic flag. Filter before converting so
        // a voice/quality choice cannot repeatedly pick its known failed URL.
        _streamOptions.value = candidates.filter {
            !it.isProblematic && it.stableStreamId !in failedStreamIds
        }.map(StreamCandidate::toStreamOption)
    }

    private fun requestContext(request: PlaybackRequest): StreamRankingContext =
        StreamRankingContext(
            requestedVoice = request.requestedVoice,
            requestedQuality = request.requestedQuality,
            failedStreamIds = failedStreamIds.toSet(),
        )

    private fun selectInitialCandidate(request: PlaybackRequest): StreamCandidate? {
        val exact = request.requestedStreamId?.trim()?.takeIf { it.isNotBlank() }?.let { id ->
            // An explicit variant is a constrained ranking pool, not a
            // bypass around the session's failed/problematic memory.
            val exactPool = candidates.filter { it.stableStreamId == id }
            StreamRanker.rankCandidates(
                candidates = exactPool,
                failedStreamIds = failedStreamIds.toSet(),
                context = requestContext(request),
            ).firstOrNull { !failedStreamIds.contains(it.stableStreamId) && !it.isProblematic }
        }
        return exact ?: selectReadyHttpStartup(request, candidates, requestContext(request)) ?: StreamRanker.selectBest(
            candidates = candidates,
            requestedVoice = request.requestedVoice,
            requestedQuality = request.requestedQuality,
            failedStreamIds = failedStreamIds.toSet(),
            context = requestContext(request),
        )
    }

    private fun markProblem(candidate: StreamCandidate?) {
        candidate ?: return
        failedStreamIds += candidate.stableStreamId
        while (failedStreamIds.size > MAX_PROBLEM_MEMORY) {
            failedStreamIds.remove(failedStreamIds.first())
        }
        candidates = candidates.map {
            if (it.stableStreamId == candidate.stableStreamId) it.copy(isProblematic = true) else it
        }
        if (activeCandidate?.stableStreamId == candidate.stableStreamId) {
            activeCandidate = activeCandidate?.copy(isProblematic = true)
        }
        publishCandidateOptions()
    }

    private fun recordFailure(candidate: StreamCandidate?, failureClass: StreamFailureClass) {
        candidate ?: return
        if (problemTracker.shouldMarkProblem(candidate, failureClass)) {
            markProblem(candidate)
        }
    }

    private fun clearProblemMemory(candidate: StreamCandidate) {
        failedStreamIds.remove(candidate.stableStreamId)
        problemTracker.clear(candidate)
        candidates = candidates.map {
            if (it.stableStreamId == candidate.stableStreamId) it.copy(isProblematic = false) else it
        }
        if (activeCandidate?.stableStreamId == candidate.stableStreamId) {
            activeCandidate = activeCandidate?.copy(isProblematic = false)
        }
        publishCandidateOptions()
    }

    private fun rememberReloadAttempt(candidate: StreamCandidate): Boolean {
        if (reloadAttemptedStreamIds.contains(candidate.stableStreamId)) return false
        reloadAttemptedStreamIds += candidate.stableStreamId
        while (reloadAttemptedStreamIds.size > MAX_PROBLEM_MEMORY) {
            reloadAttemptedStreamIds.remove(reloadAttemptedStreamIds.first())
        }
        return true
    }

    private fun replaceCandidate(previous: StreamCandidate, refreshed: StreamCandidate) {
        val index = candidates.indexOfFirst { it.stableStreamId == previous.stableStreamId }
        if (index >= 0) {
            candidates = candidates.toMutableList().also { it[index] = refreshed }
        } else {
            candidates += refreshed
        }
        publishCandidateOptions()
    }

    private fun clearCandidateTrackOverrides() {
        appliedTrackSelectionKey = null
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
    }

    private fun applyCandidateTrackOverrides(tracks: Tracks): Boolean {
        val candidate = activeCandidate ?: return false
        val videoTrackIndex = candidate.videoTrackIndex
        val audioTrackIndex = candidate.audioTrackIndex
        if (videoTrackIndex == null && audioTrackIndex == null) return false

        val videoGroupId = candidate.transportMetadata["zona_video_group_id"]?.trim()?.takeIf { it.isNotBlank() }
        val audioGroupId = candidate.transportMetadata["zona_audio_group_id"]?.trim()?.takeIf { it.isNotBlank() }
        val videoGroupIndex = candidate.transportMetadata["zona_video_group_index"]?.toIntOrNull()
        val audioGroupIndex = candidate.transportMetadata["zona_audio_group_index"]?.toIntOrNull()
        val key = listOf(
            candidate.stableStreamId,
            videoGroupId.orEmpty(),
            videoGroupIndex?.toString().orEmpty(),
            videoTrackIndex?.toString().orEmpty(),
            audioGroupId.orEmpty(),
            audioGroupIndex?.toString().orEmpty(),
            audioTrackIndex?.toString().orEmpty(),
        ).joinToString("|")
        if (appliedTrackSelectionKey == key) return true

        val videoOverride = videoTrackIndex?.let { providerTrackOverride(tracks, C.TRACK_TYPE_VIDEO, it, candidate.transportMetadata) }
        val audioOverride = audioTrackIndex?.let { providerTrackOverride(tracks, C.TRACK_TYPE_AUDIO, it, candidate.transportMetadata) }
        if ((videoTrackIndex != null && videoOverride == null) || (audioTrackIndex != null && audioOverride == null)) return false
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        videoOverride?.let(builder::setOverrideForType)
        audioOverride?.let(builder::setOverrideForType)
        appliedTrackSelectionKey = key
        player.trackSelectionParameters = builder.build()
        return true
    }

    private fun consumedUri(candidate: StreamCandidate, request: PlaybackRequest? = null): String? {
        val raw = candidate.url.trim()
        if (raw.isBlank()) return null
        val transport = candidate.transport.trim().lowercase()
        val mayBeGateway = transport in setOf(
            "torrent",
            "p2p",
            "torrent_p2p",
            "magnet",
            "local_gateway",
        ) || raw.contains("127.0.0.1:8888/stream", ignoreCase = true) ||
            raw.contains("localhost:8888/stream", ignoreCase = true)
        val magnet = when {
            raw.startsWith("magnet:?", ignoreCase = true) -> raw
            mayBeGateway -> runCatching { Uri.parse(raw).getQueryParameter("magnet") }
                .getOrNull()?.trim()
                ?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
            else -> null
        }
        if (magnet != null) {
            val encoded = runCatching { URLEncoder.encode(magnet, "UTF-8") }.getOrNull() ?: return null
            val season = candidate.seasonNumber ?: request?.seasonNumber
            val episode = candidate.episodeNumber ?: request?.episodeNumber
            val episodeQuery = if (season != null && episode != null) {
                "&season=$season&episode=$episode"
            } else {
                ""
            }
            val fileIndexQuery = candidate.fileIndex?.takeIf { it >= 0 }?.let { "&file_index=$it" }.orEmpty()
            // P2P is a logical candidate; the player always consumes it via
            // the local raw-container gateway, never via a provider host.
            return "http://127.0.0.1:8888/stream?magnet=$encoded&format=raw$episodeQuery$fileIndexQuery"
        }
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.startsWith("file://", ignoreCase = true)
        ) {
            return raw
        }
        return null
    }

    private fun buildMediaItem(
        request: PlaybackRequest,
        candidate: StreamCandidate,
        consumedUri: String,
    ): MediaItem {
        if (candidate.provider == "offline") {
            val ref = MediaRef(request.mediaId, request.seasonNumber, request.episodeNumber)
            val downloaded = OfflineMediaStore.request(appContext, ref) ?: error("Offline metadata missing")
            return downloaded.toMediaItem().buildUpon().setMediaId(request.mediaId).build()
        }
        return MediaItem.Builder()
        .setMediaId(request.mediaId)
        .setUri(consumedUri)
        .apply {
            safeMimeType(candidate, consumedUri)?.let(::setMimeType)
            val subtitles = safeExternalSubtitleConfigurations(candidate)
            if (subtitles.isNotEmpty()) setSubtitleConfigurations(subtitles)
            val drmUuid = drmUuidForScheme(candidate.drmScheme)
            val licenseUrl = safeDrmLicenseUrl(candidate.drmLicenseUrl)
            if (drmUuid != null && licenseUrl != null) {
                setDrmConfiguration(
                    MediaItem.DrmConfiguration.Builder(drmUuid)
                        .setLicenseUri(licenseUrl)
                        .build(),
                )
            }
        }
        .build()
    }

    private fun startWatchdog(
        candidate: StreamCandidate,
        resumePositionMs: Long,
        generation: Long,
    ) {
        watchdogJob?.cancel()
        val candidateId = candidate.stableStreamId
        watchdogJob = scope.launch {
            delay(STARTUP_WATCHDOG_MS)
            if (!isActive || !isCurrentGeneration(generation)) return@launch
            if (activeCandidate?.stableStreamId != candidateId) return@launch
            if (player.playbackState != Player.STATE_READY && !player.isPlaying) {
                Log.w(TAG, "Startup watchdog fired for candidate id=$candidateId")
                handleCandidateFailure("STARTUP_TIMEOUT", resumePositionMs, generation)
            }
        }
    }

    private fun startStallWatchdog(generation: Long) {
        if (stallWatchdogJob?.isActive == true) return
        val candidate = activeCandidate ?: return
        val candidateId = candidate.stableStreamId
        stallWatchdogJob = scope.launch {
            delay(STALL_WATCHDOG_MS)
            if (!isActive || !isCurrentGeneration(generation)) return@launch
            if (activeCandidate?.stableStreamId != candidateId) return@launch
            if (player.playbackState == Player.STATE_BUFFERING && player.playWhenReady) {
                Log.w(TAG, "Stall watchdog fired for candidate id=$candidateId after ${STALL_WATCHDOG_MS}ms")
                val position = maxOf(
                    0L,
                    _state.value.currentPositionMs,
                    player.currentPosition.coerceAtLeast(0L),
                )
                handleCandidateFailure("BUFFERING_TIMEOUT", position, generation)
            }
        }
    }

    /** Prepare one candidate; no URL preflight is performed. */
    private fun prepareCandidate(
        candidate: StreamCandidate,
        request: PlaybackRequest,
        resumePositionMs: Long,
        generation: Long,
    ): Boolean {
        if (!isCurrentGeneration(generation)) return false
        webResolveJob?.cancel()
        webResolveJob = null
        if (candidate.transportMetadata["legacy_web_player"] == "true") {
            player.stop()
            player.clearMediaItems()
            activeCandidate = candidate
            activeConsumedUri = null
            _state.value = _state.value.copy(status = PlaybackStatus.BUFFERING,
                switchState = PlaybackSwitchState.CONNECTING, statusMessage = "Открываем источник…",
                currentPositionMs = resumePositionMs.coerceAtLeast(0))
            webResolveJob = scope.launch {
                val resolved = try { LegacyPlaybackResolver.playable(appContext, candidate) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (!isCurrentGeneration(generation)) return@launch
                webResolveJob = null
                if (resolved == null) {
                    handleCandidateFailure("WEB_RESOLUTION_FAILED", resumePositionMs, generation)
                } else {
                    replaceCandidate(candidate, resolved)
                    if (!prepareCandidate(resolved, request, resumePositionMs, generation)) {
                        handleCandidateFailure("WEB_PREPARE_FAILED", resumePositionMs, generation)
                    }
                }
            }
            publishSnapshot()
            return true
        }
        val uri = consumedUri(candidate, request) ?: return false
        stallWatchdogJob?.cancel()
        activeCandidate = candidate
        activeConsumedUri = uri
        dataSourceFactory.setOfflineFactory(if (candidate.provider == "offline") {
            OfflineMediaStore.playbackFactory(appContext, MediaRef(request.mediaId, request.seasonNumber, request.episodeNumber))
        } else null)
        dataSourceFactory.setRequestProfile(StreamRequestProfile.from(candidate, uri))
        val previousSwitchState = _state.value.switchState
        val preparationState = when (previousSwitchState) {
            PlaybackSwitchState.RECOVERING -> PlaybackSwitchState.RECOVERING
            PlaybackSwitchState.SWITCHING_SOURCE -> PlaybackSwitchState.SWITCHING_SOURCE
            else -> PlaybackSwitchState.CONNECTING
        }
        val preparationMessage = when (preparationState) {
            PlaybackSwitchState.RECOVERING -> "Пытаемся восстановить воспроизведение…"
            PlaybackSwitchState.SWITCHING_SOURCE -> "Переключаем источник…"
            else -> "Подключаемся"
        }
        _state.value = _state.value.copy(
            status = PlaybackStatus.BUFFERING,
            switchState = preparationState,
            statusMessage = preparationMessage,
            currentPositionMs = resumePositionMs.coerceAtLeast(0L),
            bufferedPositionMs = resumePositionMs.coerceAtLeast(0L),
            activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                activeStreamId = null,
                activeQuality = null,
                activeVoice = null,
                source = candidate.provider,
            ),
        )
        return try {
            player.stop()
            player.clearMediaItems()
            clearCandidateTrackOverrides()
            player.setMediaItem(buildMediaItem(request, candidate, uri))
            player.prepare()
            if (resumePositionMs > 0L) player.seekTo(resumePositionMs)
            // Preparing another URL must preserve the user's pause intent.
            // New media starts set this intent before discovery; pause/play
            // can change it while a web source is still being resolved.
            player.playWhenReady = desiredPlayWhenReady
            startWatchdog(candidate, resumePositionMs, generation)
            publishSnapshot()
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "Candidate preparation failed: id=${candidate.stableStreamId} msg=${throwable.message}", throwable)
            false
        }
    }

    private fun isP2pCandidate(candidate: StreamCandidate): Boolean {
        val transport = candidate.transport.trim().lowercase()
        return candidate.url.startsWith("magnet:", ignoreCase = true) ||
            transport in setOf("torrent", "p2p", "torrent_p2p", "magnet", "local_gateway")
    }

    private fun nextHealthyCandidates(
        request: PlaybackRequest,
        excludeStreamId: String? = null,
        preferNonP2p: Boolean = false,
    ): List<StreamCandidate> {
        val ordered = StreamRanker.fallbackOrder(
            candidates = candidates,
            context = requestContext(request),
        ).filter {
            it.stableStreamId != excludeStreamId &&
                !failedStreamIds.contains(it.stableStreamId) &&
                !it.isProblematic
        }
        return if (preferNonP2p) {
            // After a cold P2P network timeout, try an already-resolved direct/HLS source
            // before burning another watchdog window on a second cold torrent candidate.
            ordered.sortedBy { if (isP2pCandidate(it)) 1 else 0 }
        } else {
            ordered
        }
    }

    private fun failPlayback(reason: String) {
        watchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        player.stop()
        player.clearMediaItems()
        activeCandidate = null
        activeConsumedUri = null
        _state.value = _state.value.copy(
            status = PlaybackStatus.IDLE,
            switchState = PlaybackSwitchState.FAILED,
            isPlaying = false,
            playWhenReady = false,
            statusMessage = "Не удалось найти стабильный источник.",
            activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                activeStreamId = null,
                activeQuality = null,
                activeVoice = null,
                fallbackReason = reason,
            ),
        )
        publishSnapshot()
    }

    private suspend fun recoverFromFailure(
        reason: String,
        resumePositionMs: Long,
        generation: Long,
        failureClass: StreamFailureClass,
    ) {
        if (!isCurrentGeneration(generation)) return
        val failed = activeCandidate
        recoveryAttemptCount += 1
        if (recoveryAttemptCount > recoveryAttemptBudget) {
            recordFailure(failed, failureClass)
            failPlayback("EXHAUSTED_$reason")
            return
        }

        _state.value = _state.value.copy(
            status = PlaybackStatus.BUFFERING,
            switchState = PlaybackSwitchState.RECOVERING,
            statusMessage = "Источник работает медленно. Пытаемся восстановить воспроизведение…",
            activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                fallbackReason = reason,
            ),
        )
        val request = playbackRequest
        // Zona V4 continuity rule: refresh the same logical stream before
        // committing it to problem memory or falling back to another candidate.
        if (failed != null && request != null && rememberReloadAttempt(failed) &&
            (failed.reloadSupported || !failed.reloadData.isNullOrBlank())
        ) {
            val refreshed = withTimeoutOrNull(RELOAD_TIMEOUT_MS) {
                val refreshRequest = request.copy(startPositionMs = resumePositionMs.coerceAtLeast(0L), attempt = request.attempt + 1)
                if (failed.transportMetadata["legacy_engine"] == "3.466") {
                    LegacyPlaybackResolver.refresh(appContext, failed, refreshRequest)
                } else DomainPlaybackResolver.reloadStreamCandidate(failed, refreshRequest)
            }
            if (isCurrentGeneration(generation) && refreshed != null) {
                replaceCandidate(failed, refreshed)
                activeCandidate = refreshed
                // A successful logical reload supersedes transient problem memory
                // for the old locator and allows a later failure to refresh again.
                failedStreamIds.remove(refreshed.stableStreamId)
                problemTracker.clear(failed)
                reloadAttemptedStreamIds.remove(refreshed.stableStreamId)
                _state.value = _state.value.copy(
                    switchState = PlaybackSwitchState.RECOVERING,
                    statusMessage = "Проверяем более стабильный вариант…",
                    activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                        source = refreshed.provider,
                        fallbackReason = "RELOADED_$reason",
                    ),
                )
                if (prepareCandidate(refreshed, request, resumePositionMs, generation)) {
                    clearProblemMemory(refreshed)
                    return
                }
                // A refreshed candidate that cannot even be prepared is a
                // structural/non-network failure, not the original network event.
                recordFailure(refreshed, StreamFailureClass.NON_NETWORK)
            }
        }

        // Reload was unavailable or failed. Only now apply the original failure
        // to the problem memory, matching the verified Zona ordering.
        recordFailure(failed, failureClass)

        val currentRequest = playbackRequest
        if (currentRequest == null || !isCurrentGeneration(generation)) {
            failPlayback("REQUEST_UNAVAILABLE")
            return
        }
        val next = nextHealthyCandidates(
            request = currentRequest,
            excludeStreamId = failed?.stableStreamId,
            preferNonP2p = failureClass == StreamFailureClass.NETWORK && failed?.let(::isP2pCandidate) == true,
        )
        for (candidate in next) {
            if (!isCurrentGeneration(generation)) return
            _state.value = _state.value.copy(
                switchState = PlaybackSwitchState.SWITCHING_SOURCE,
                statusMessage = "Нашли более стабильный источник. Переключаемся…",
            )
            if (prepareCandidate(candidate, currentRequest, resumePositionMs, generation)) return
            recordFailure(candidate, StreamFailureClass.NON_NETWORK)
        }
        failPlayback("EXHAUSTED_$reason")
    }

    private fun handleCandidateFailure(
        reason: String,
        resumePositionMs: Long,
        generation: Long,
        failureClass: StreamFailureClass = StreamFailureClassifier.fromReason(reason),
    ) {
        if (!isCurrentGeneration(generation) || recoveryJob?.isActive == true) return
        stallWatchdogJob?.cancel()
        recoveryJob = scope.launch {
            try {
                recoverFromFailure(reason, resumePositionMs, generation, failureClass)
            } finally {
                recoveryJob = null
            }
        }
    }

    fun start(
        mediaId: String,
        title: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        sourceUri: String? = null,
        startPositionMs: Long = 0L,
        audioTrackId: String = _state.value.audioTrackId,
        subtitleTrackId: String? = _state.value.subtitleTrackId,
        candidateStreams: List<String> = emptyList(),
        contentYear: Int? = null,
        mediaType: ContentType? = null,
        preferredQuality: String? = null,
        preferredVoice: String? = null,
        preferredStreamId: String? = null,
        candidateStreamOptions: List<StreamOption> = emptyList(),
        recordHistory: Boolean = true,
    ) {
        this.recordHistory = recordHistory
        startupHandoverJob?.cancel()
        startupHandoverUsed = false
        discoveryJob?.cancel()
        userSelectedAutoQuality = false
        userSelectedAutoAudio = false
        requestedVideoQuality = preferredQuality?.takeIf { it.isNotBlank() } ?: "Auto"
        requestedAudioTrack = null
        requestStartedMs = SystemClock.elapsedRealtime()
        readyLatencyMs = null
        firstFrameLatencyMs = null
        _choices.value = PlaybackChoices()
        val generation = nextPlaybackGeneration()
        watchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        failedStreamIds.clear()
        problemTracker.reset()
        reloadAttemptedStreamIds.clear()
        recoveryAttemptCount = 0
        candidates = emptyList()
        activeCandidate = null
        activeConsumedUri = null
        // Bind the single player to the new request before discovery starts;
        // the previous media must not keep playing under the new identity.
        player.stop()
        player.clearMediaItems()
        val effectiveMediaType = mediaType ?: if (seasonNumber != null && episodeNumber != null) {
            ContentType.SERIES
        } else {
            ContentType.MOVIE
        }
        val request = PlaybackRequest(
            mediaId = mediaId.trim(),
            title = canonicalRequestTitle(title),
            mediaType = effectiveMediaType,
            year = contentYear,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            requestedVoice = preferredVoice?.trim()?.takeIf { it.isNotBlank() },
            requestedQuality = preferredQuality?.trim()?.takeIf { it.isNotBlank() },
            requestedStreamId = preferredStreamId?.trim()?.takeIf { it.isNotBlank() },
            startPositionMs = startPositionMs.coerceAtLeast(0L),
            generationId = generation,
        )
        playbackRequest = request
        val seeds = initialCandidates(
            sourceUri = sourceUri,
            candidateStreams = candidateStreams,
            candidateStreamOptions = candidateStreamOptions,
            season = seasonNumber,
            episode = episodeNumber,
        )
        _streamOptions.value = seeds.map(StreamCandidate::toStreamOption)
        _state.value = PlaybackState(
            mediaId = request.mediaId,
            displayTitle = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            currentPositionMs = request.startPositionMs,
            bufferedPositionMs = request.startPositionMs,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            playWhenReady = true,
            status = PlaybackStatus.BUFFERING,
            statusMessage = "Ищем источник",
            switchState = PlaybackSwitchState.SEARCHING_SOURCE,
            activeStreamSelection = ActiveStreamSelection(
                requestedStreamId = request.requestedStreamId,
                requestedQuality = request.requestedQuality,
                requestedVoice = request.requestedVoice,
            ),
        )
        desiredPlayWhenReady = true
        player.playWhenReady = true
        val ref = MediaRef(request.mediaId, request.seasonNumber, request.episodeNumber)
        val downloaded = OfflineMediaStore.request(appContext, ref)
        if (downloaded != null) {
            val storedSelection = OfflineMediaStore.selection(appContext, ref)
            val candidate = StreamCandidate(stableStreamId = "offline:" + ref.storageKey,
                provider = "offline", url = downloaded.uri.toString(), mimeType = downloaded.mimeType,
                voice = storedSelection?.voice ?: "Офлайн", quality = storedSelection?.quality ?: "Auto", transport = "local_storage",
                audioTrackIndex = storedSelection?.audioIndex,
                transportMetadata = storedSelection?.audioLabel?.let { mapOf("movia_audio_label" to it) }.orEmpty(),
                seasonNumber = ref.season, episodeNumber = ref.episode, catalogMediaId = ref.contentId)
            candidates = listOf(candidate)
            publishCandidateOptions()
            prepareCandidate(candidate, request, request.startPositionMs, generation)
            return
        }
        // The card's HTTP variants are already bound to its identity. Start one
        // without waiting for every provider; refreshing locators and adding
        // voices continues in parallel, with the usual failure/reload policy.
        val cachedStartup = DomainPlaybackResolver.cachedStartupCandidates(request, seeds)
        if (cachedStartup.isNotEmpty()) {
            candidates = StreamRanker.rankCandidates(cachedStartup, context = requestContext(request))
            publishCandidateOptions()
            selectInitialCandidate(request)?.let { candidate ->
                if (!prepareCandidate(candidate, request, request.startPositionMs, generation)) {
                    handleCandidateFailure("CACHED_PREPARE_FAILED", request.startPositionMs, generation)
                }
            }
        }
        discoveryJob = scope.launch {
            val updates = kotlinx.coroutines.channels.Channel<List<StreamCandidate>>(kotlinx.coroutines.channels.Channel.CONFLATED)
            suspend fun mergeDiscovered(incoming: List<StreamCandidate>) {
                if (playbackRequest?.correlationId == request.correlationId) acceptDiscoveredCandidates(incoming)
            }
            val updateConsumer = launch { for (incoming in updates) mergeDiscovered(incoming) }
            val nativeDiscovery = async(Dispatchers.IO) {
                try {
                    if (seeds.isNotEmpty() && seeds.all { it.url.startsWith("http://127.") || it.transport == "local_storage" }) {
                        emptyList()
                    } else LegacyPlaybackResolver.discover(appContext, request) { updates.trySend(it) }
                } finally { updates.close() }
            }
            val result = try {
                withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                    DomainPlaybackResolver.resolveStreams(request = request, initialCandidates = seeds)
                } ?: PlaybackResolverResult.Error("Таймаут резолвера потоков (${RESOLVER_TIMEOUT_MS / 1000}с)")
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                PlaybackResolverResult.Error("Резолвер потоков завершился с ошибкой", throwable)
            }
            if (playbackRequest?.correlationId != request.correlationId) return@launch
            if (result is PlaybackResolverResult.Success) {
                // Publish ready backend rows immediately. Slow metadata sources add their
                // verified tracks later; candidate mutations remain on this playback scope.
                ZonaMediaProbe.expand(appContext, result.candidates) { rows ->
                    mergeDiscovered(rows)
                }
            }
            val additional = nativeDiscovery.await()
            updateConsumer.join()
            if (playbackRequest?.correlationId != request.correlationId) return@launch
            mergeDiscovered(additional)
            if (activeCandidate == null && candidates.isEmpty()) {
                failPlayback(if (result is PlaybackResolverResult.Error) "RESOLVER_ERROR" else "NO_SOURCE")
            }
        }
    }

    /** All discovery routes enter on the playback scope; stale identities are discarded. */
    internal suspend fun acceptDiscoveredCandidates(incoming: List<StreamCandidate>) {
        val current = playbackRequest ?: return
        if (incoming.isEmpty()) return
        val needsRecovery = _state.value.switchState == PlaybackSwitchState.FAILED
        val validated = DomainPlaybackResolver.validatedCandidates(current, incoming)
        if (validated.isEmpty()) return
        candidates = StreamRanker.rankCandidates(
            StreamDeduplicator.deduplicate(validated + candidates), context = requestContext(current))
        publishCandidateOptions()
        recoveryAttemptBudget = candidates.size.coerceAtLeast(1) * 2 + 1
        val desired = selectInitialCandidate(current) ?: return
        if (activeCandidate == null) {
            _state.value = _state.value.copy(
                activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(source = desired.provider))
            if (!prepareCandidate(desired, current, current.startPositionMs, playbackGeneration)) {
                handleCandidateFailure("PREPARE_FAILED", current.startPositionMs, playbackGeneration)
            }
            return
        }
        scheduleStartupHandover(current, validated)
        val preferredVoiceArrived = !current.requestedVoice.isNullOrBlank() &&
            desired.voice == current.requestedVoice && activeCandidate?.voice != current.requestedVoice
        val preferredQualityArrived = !current.requestedQuality.isNullOrBlank() &&
            !current.requestedQuality.equals("Auto", true) && desired.quality == current.requestedQuality &&
            activeCandidate?.quality != current.requestedQuality && player.videoFormat?.height != desired.resolutionHeight
        if (desired.stableStreamId != activeCandidate?.stableStreamId &&
            (needsRecovery || preferredVoiceArrived || preferredQualityArrived)) {
            val position = if (needsRecovery) _state.value.currentPositionMs else player.currentPosition
            switchToStream(desired.toStreamOption(), position.coerceAtLeast(0))
        }
    }

    private fun scheduleStartupHandover(request: PlaybackRequest, incoming: List<StreamCandidate>) {
        if (startupHandoverUsed || startupHandoverJob?.isActive == true ||
            firstFrameLatencyMs != null || readyLatencyMs != null || recoveryJob?.isActive == true) return
        val previous = activeCandidate ?: return
        val replacement = selectStartupReplacement(request, previous, incoming, requestContext(request)) ?: return
        val generation = playbackGeneration
        startupHandoverJob = scope.launch {
            delay((1_500L - (SystemClock.elapsedRealtime() - requestStartedMs)).coerceAtLeast(0))
            if (!isCurrentGeneration(generation) || playbackRequest?.correlationId != request.correlationId ||
                activeCandidate != previous || firstFrameLatencyMs != null || readyLatencyMs != null ||
                player.playbackState == Player.STATE_READY || player.isPlaying || recoveryJob?.isActive == true) return@launch
            startupHandoverUsed = true
            watchdogJob?.cancel()
            stallWatchdogJob?.cancel()
            val position = maxOf(request.startPositionMs, _state.value.currentPositionMs, player.currentPosition.coerceAtLeast(0))
            _state.value = _state.value.copy(switchState = PlaybackSwitchState.SWITCHING_SOURCE)
            Log.i(TAG, "Startup discovery handover id=" + replacement.stableStreamId)
            if (!prepareCandidate(replacement, request, position, generation)) {
                handleCandidateFailure("STARTUP_HANDOVER_FAILED", position, generation)
            }
        }
    }

    fun switchToStream(streamUrl: String, resumePositionMs: Long = -1L) {
        if (streamUrl.isBlank()) return
        val normalized = streamUrl.trim()
        val known = candidates.firstOrNull {
            it.url.trim() == normalized ||
                (activeConsumedUri == normalized && it == activeCandidate)
        }
        val option = known?.toStreamOption()
            ?: genericStreamOption(normalized, _state.value.seasonNumber, _state.value.episodeNumber)
        switchToStream(option, resumePositionMs)
    }

    fun switchToStream(stream: StreamOption, resumePositionMs: Long = -1L) {
        startupHandoverJob?.cancel()
        startupHandoverUsed = true
        if (stream.url.isBlank() || !_state.value.hasMedia) return
        val generation = nextPlaybackGeneration()
        watchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        recoveryAttemptCount = 0
        val position = if (resumePositionMs >= 0L) resumePositionMs else {
            player.currentPosition.coerceAtLeast(0L)
        }
        val requestBase = playbackRequest ?: PlaybackRequest(
            mediaId = _state.value.mediaId,
            title = canonicalRequestTitle(_state.value.displayTitle),
            mediaType = if (_state.value.seasonNumber != null && _state.value.episodeNumber != null) {
                ContentType.SERIES
            } else {
                ContentType.MOVIE
            },
            seasonNumber = _state.value.seasonNumber,
            episodeNumber = _state.value.episodeNumber,
        )
        val normalizedCandidate = StreamCandidate.fromStreamOption(
            stream,
            requestBase.seasonNumber,
            requestBase.episodeNumber,
        )
        val candidate = candidates.firstOrNull {
            it.stableStreamId == normalizedCandidate.stableStreamId &&
                it.toStreamOption().sameRequestedVariant(
                    normalizedCandidate.toStreamOption(),
                    requestBase.seasonNumber,
                    requestBase.episodeNumber,
                )
        } ?: candidates.firstOrNull {
            it.url == normalizedCandidate.url &&
                it.toStreamOption().sameRequestedVariant(
                    normalizedCandidate.toStreamOption(),
                    requestBase.seasonNumber,
                    requestBase.episodeNumber,
                )
        } ?: normalizedCandidate
        val candidateIndex = candidates.indexOfFirst { it.stableStreamId == candidate.stableStreamId }
        if (candidateIndex < 0) {
            candidates += candidate
        } else if (candidates[candidateIndex] != candidate) {
            candidates = candidates.toMutableList().also { it[candidateIndex] = candidate }
        }
        // An explicit user choice starts a new bounded attempt for that ID.
        failedStreamIds.remove(candidate.stableStreamId)
        problemTracker.clear(candidate)
        reloadAttemptedStreamIds.remove(candidate.stableStreamId)
        candidates = candidates.map {
            if (it.stableStreamId == candidate.stableStreamId) it.copy(isProblematic = false) else it
        }
        publishCandidateOptions()
        val request = requestBase.copy(
            requestedVoice = stream.voice.trim().takeIf { it.isNotBlank() && !it.equals("Auto", true) },
            requestedQuality = requestedVideoQuality.takeIf { !it.equals("Auto", true) },
            requestedStreamId = candidate.stableStreamId,
            startPositionMs = position,
            generationId = generation,
            attempt = 1,
        )
        playbackRequest = request
        recoveryAttemptBudget = (candidates.size.coerceAtLeast(1) * 2) + 1

        val previousCandidate = activeCandidate
        val canSwitchInPlace = player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            canSwitchTracksInPlace(previousCandidate, candidate)
        if (canSwitchInPlace) {
            activeCandidate = candidate
            appliedTrackSelectionKey = null
            if (applyCandidateTrackOverrides(player.currentTracks)) {
                applyUserTrackPreferences(player.currentTracks)
                Log.i(
                    TAG,
                    "Applied in-place media track switch id=${candidate.stableStreamId} audioIndex=${candidate.audioTrackIndex} videoIndex=${candidate.videoTrackIndex}",
                )
                _state.value = _state.value.copy(
                    currentPositionMs = position,
                    statusMessage = null,
                    activeStreamSelection = ActiveStreamSelection(
                        requestedStreamId = request.requestedStreamId,
                        requestedQuality = request.requestedQuality,
                        requestedVoice = request.requestedVoice,
                        activeStreamId = candidate.stableStreamId,
                        activeQuality = candidate.quality,
                        activeVoice = candidate.voice,
                        source = candidate.provider,
                    ),
                )
                publishSnapshot()
                return
            }
            activeCandidate = previousCandidate
            appliedTrackSelectionKey = null
        }

        _state.value = _state.value.copy(
            switchState = PlaybackSwitchState.SWITCHING_SOURCE,
            status = PlaybackStatus.BUFFERING,
            statusMessage = "Переключаем источник…",
            currentPositionMs = position,
            activeStreamSelection = ActiveStreamSelection(
                requestedStreamId = request.requestedStreamId,
                requestedQuality = request.requestedQuality,
                requestedVoice = request.requestedVoice,
                source = candidate.provider,
            ),
        )
        scope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            if (!prepareCandidate(candidate, request, position, generation)) {
                handleCandidateFailure("SWITCH_PREPARE_FAILED", position, generation)
            }
        }
    }

    fun pausePlayback() {
        desiredPlayWhenReady = false
        if (player.playWhenReady || player.isPlaying) {
            player.pause()
        }
        publishSnapshot()
    }

    fun togglePlayPause() {
        if (desiredPlayWhenReady || player.playWhenReady) pausePlayback() else playPlayback()
        publishSnapshot()
    }

    fun playPlayback() {
        desiredPlayWhenReady = true
        player.play()
        publishSnapshot()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0L }
            ?: _state.value.totalDurationMs.takeIf { it > 0L }
            ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, duration)
        val percentage = if (duration != Long.MAX_VALUE && duration > 0L) {
            (target.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            _state.value.percentageWatched
        }
        _state.value = _state.value.copy(
            currentPositionMs = target,
            percentageWatched = percentage,
            lastUpdatedTimestamp = System.currentTimeMillis(),
        )
        player.seekTo(target)
    }

    fun setPlaybackSpeed(speed: Float): Boolean {
        if (!speed.isFinite() || speed < 0.25f || speed > 3f) return false
        player.setPlaybackSpeed(speed)
        return true
    }

    fun selectProvider(provider: String): Boolean {
        val request = playbackRequest ?: return false
        if (activeCandidate?.provider == provider && player.playbackState != Player.STATE_IDLE &&
            _state.value.switchState != PlaybackSwitchState.FAILED) return true
        val selected = StreamRanker.selectBest(candidates.filter { it.provider == provider },
            request.requestedVoice, request.requestedQuality, failedStreamIds = failedStreamIds.toSet(),
            context = requestContext(request)) ?: return false
        switchToStream(selected.toStreamOption(), player.currentPosition.coerceAtLeast(0))
        return true
    }

    fun setTrackPreferences(audioTrackId: String, subtitleTrackId: String?) {
        _state.value = _state.value.copy(
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            lastUpdatedTimestamp = System.currentTimeMillis(),
        )
    }

    fun stopAndClear() {
        startupHandoverJob?.cancel()
        discoveryJob?.cancel()
        webResolveJob?.cancel()
        nextPlaybackGeneration()
        watchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        desiredPlayWhenReady = false
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        playbackRequest = null
        candidates = emptyList()
        activeCandidate = null
        activeConsumedUri = null
        appliedTrackSelectionKey = null
        failedStreamIds.clear()
        problemTracker.reset()
        reloadAttemptedStreamIds.clear()
        _streamOptions.value = emptyList()
        _choices.value = PlaybackChoices()
        _state.value = PlaybackState()
    }

    fun retry() {
        val request = playbackRequest ?: return
        Log.i(TAG, "Retrying playback for mediaId=${request.mediaId}")
        val current = _state.value
        start(
            mediaId = request.mediaId,
            title = current.displayTitle.ifBlank { request.title },
            seasonNumber = request.seasonNumber,
            episodeNumber = request.episodeNumber,
            startPositionMs = current.currentPositionMs,
            audioTrackId = current.audioTrackId,
            subtitleTrackId = current.subtitleTrackId,
            contentYear = request.year,
            mediaType = request.mediaType,
            preferredQuality = request.requestedQuality,
            preferredVoice = request.requestedVoice,
            preferredStreamId = request.requestedStreamId,
        )
    }

    internal fun realPlaybackEvidence(): Pair<Boolean, Long> =
        (player.playbackState == Player.STATE_READY && player.isPlaying) to
            player.currentPosition.coerceAtLeast(0L)

    private fun publishSnapshot() {
        val current = _state.value
        if (!current.hasMedia) return
        val duration = player.duration.takeIf { it > 0L } ?: current.totalDurationMs
        val position = player.currentPosition.coerceAtLeast(0L)
        val percentage = if (duration > 0L) {
            (position.toDouble() / duration.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
        val transportStatus = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            Player.STATE_READY -> PlaybackStatus.READY
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> PlaybackStatus.IDLE
        }
        val preparingStates = setOf(
            PlaybackSwitchState.RESOLVING,
            PlaybackSwitchState.SEARCHING_SOURCE,
            PlaybackSwitchState.CONNECTING,
            PlaybackSwitchState.PREBUFFERING,
            PlaybackSwitchState.RECOVERING,
            PlaybackSwitchState.SWITCHING_SOURCE,
            PlaybackSwitchState.BUFFERING,
        )
        val status = if (
            transportStatus == PlaybackStatus.IDLE &&
            current.switchState in preparingStates
        ) {
            PlaybackStatus.BUFFERING
        } else {
            transportStatus
        }
        val observedChoices = playbackChoices(player.currentTracks, player.videoFormat?.height ?: 0)
        val selectedOrdinal = observedChoices.audio.indexOfFirst { it.selected }
        val automaticAudioSource = activeCandidate?.let { active ->
            candidates.firstOrNull { it.url == active.url && it.headers == active.headers &&
                it.userAgent == active.userAgent && it.audioTrackIndex == selectedOrdinal }
        }
        val selection = if (status == PlaybackStatus.READY && activeCandidate != null) {
            val selected = activeCandidate ?: return
            (current.activeStreamSelection ?: ActiveStreamSelection()).copy(
                activeStreamId = if (userSelectedAutoAudio) automaticAudioSource?.stableStreamId ?: selected.stableStreamId else selected.stableStreamId,
                activeQuality = player.videoFormat?.height?.takeIf { it > 0 }?.let { if (it >= 2160) "4K" else "${it}p" } ?: selected.quality,
                activeVoice = when {
                    requestedAudioTrack != null -> observedChoices.audio.firstOrNull { it.selected }?.label ?: selected.voice
                    userSelectedAutoAudio -> automaticAudioSource?.voice ?: observedChoices.audio.firstOrNull { it.selected }?.label ?: selected.voice
                    else -> selected.voice
                },
                source = selected.provider,
            )
        } else {
            current.activeStreamSelection
        }
        val switchState = when {
            current.switchState == PlaybackSwitchState.FAILED -> PlaybackSwitchState.FAILED
            status == PlaybackStatus.READY -> PlaybackSwitchState.READY
            status == PlaybackStatus.BUFFERING &&
                current.switchState in setOf(
                    PlaybackSwitchState.RECOVERING,
                    PlaybackSwitchState.SWITCHING_SOURCE,
                ) -> current.switchState
            status == PlaybackStatus.BUFFERING &&
                current.switchState != PlaybackSwitchState.SEARCHING_SOURCE -> PlaybackSwitchState.PREBUFFERING
            else -> current.switchState
        }
        val semanticMessage = when (switchState) {
            PlaybackSwitchState.SEARCHING_SOURCE -> "Ищем источник"
            PlaybackSwitchState.CONNECTING -> "Подключаемся"
            PlaybackSwitchState.PREBUFFERING -> "Готовим видео"
            PlaybackSwitchState.RECOVERING -> current.statusMessage ?: "Пытаемся восстановить воспроизведение…"
            PlaybackSwitchState.SWITCHING_SOURCE -> current.statusMessage ?: "Переключаем источник…"
            PlaybackSwitchState.READY -> null
            PlaybackSwitchState.FAILED -> current.statusMessage ?: "Не удалось найти стабильный источник."
            else -> current.statusMessage
        }
        _choices.value = observedChoices
        _state.value = current.copy(
            currentPositionMs = position,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            totalDurationMs = duration.coerceAtLeast(0L),
            percentageWatched = percentage,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            status = status,
            switchState = switchState,
            statusMessage = semanticMessage,
            activeStreamSelection = selection,
        )
    }

    fun release() {
        if (MoviaPlaybackRegistry.current === this) MoviaPlaybackRegistry.current = null
        watchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        frameProbe?.close()
        frameProbe = null
        scope.cancel()
        mediaSession.release()
        player.release()
        playbackRequest = null
        candidates = emptyList()
        activeCandidate = null
        activeConsumedUri = null
        appliedTrackSelectionKey = null
        _streamOptions.value = emptyList()
        _choices.value = PlaybackChoices()
        _state.value = PlaybackState()
    }
}
