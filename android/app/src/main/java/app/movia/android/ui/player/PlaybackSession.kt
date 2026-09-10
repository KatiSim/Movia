package app.movia.android.ui.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import app.movia.android.MainActivity
import app.movia.android.domain.model.ActiveStreamSelection
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.PlaybackStatus
import app.movia.android.domain.model.PlaybackSwitchState
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.sameRequestedVariant
import app.movia.android.domain.playback.DomainPlaybackResolver
import app.movia.android.domain.playback.PLAYBACK_READY_TARGET_MS
import app.movia.android.domain.playback.PLAYBACK_RECOVERY_TARGET_MS
import app.movia.android.domain.playback.PLAYBACK_RESOLVER_TOTAL_MS
import app.movia.android.domain.playback.PLAYBACK_USER_ERROR_MESSAGE
import app.movia.android.domain.playback.playbackMediaProbeBudgetMs
import app.movia.android.domain.playback.remainingPlaybackReadyBudgetMs
import app.movia.android.domain.playback.remainingPlaybackRecoveryBudgetMs
import app.movia.android.domain.playback.PlaybackRequest
import app.movia.android.domain.playback.PlaybackResolverResult
import app.movia.android.domain.playback.StreamCandidate
import app.movia.android.domain.playback.StreamFailureClass
import app.movia.android.domain.playback.StreamFailureClassifier
import app.movia.android.domain.playback.StreamDeduplicator
import app.movia.android.domain.playback.StreamProblemTracker
import app.movia.android.domain.playback.StreamRanker
import app.movia.android.domain.playback.StreamVariantSelection
import app.movia.android.domain.playback.StreamRankingContext
import app.movia.android.domain.playback.openWithSingleRetry
import app.movia.android.domain.playback.StreamRequestProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
private const val STARTUP_WATCHDOG_MS = 5_000L
private const val P2P_STARTUP_WATCHDOG_MS = 9_000L
private const val P2P_WARM_RETRY_MIN_REMAINING_MS = 2_000L
private const val ADJACENT_PREWARM_REMAINING_MS = 90_000L
private const val RELOAD_TIMEOUT_MS = 3_000L
private const val STALL_WATCHDOG_MS = 10_000L
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


internal data class TrackFormatDescriptor(val label: String = "", val language: String = "")
internal data class TrackGroupDescriptor(val id: String = "", val formats: List<TrackFormatDescriptor>)
internal data class AdaptiveVideoTrackDescriptor(
    val groupId: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val width: Int,
    val height: Int,
    val bitrate: Int = 0,
)

internal fun buildAdaptiveVideoVariants(
    candidates: List<StreamCandidate>,
    activeCandidate: StreamCandidate?,
    videoTracks: List<AdaptiveVideoTrackDescriptor>,
): List<StreamCandidate> {
    val active = activeCandidate ?: return candidates
    val normalizedTransport = active.transport.trim().lowercase()
    val directAdaptive = normalizedTransport in setOf("hls", "dash", "direct") &&
        (active.url.contains(".m3u8", true) || active.url.contains(".mpd", true) || normalizedTransport in setOf("hls", "dash"))
    if (!directAdaptive) return candidates

    val actualTracks = videoTracks
        .filter { it.height >= 240 && it.width > 0 }
        .groupBy { it.height }
        .values
        .mapNotNull { sameHeight -> sameHeight.maxByOrNull { it.bitrate } }
        .sortedBy { it.height }
    if (actualTracks.isEmpty()) return candidates

    val baseCandidates = candidates.filter { candidate ->
        samePlaybackLocator(candidate, active) &&
            candidate.videoTrackIndex == null &&
            StreamVariantSelection.isAllowed(candidate)
    }
    if (baseCandidates.isEmpty()) return candidates

    val sameLocator: (StreamCandidate) -> Boolean = { samePlaybackLocator(it, active) }
    val retained = candidates
        .filterNot { sameLocator(it) && it.transportMetadata["movia_adaptive_variant"] == "1" }
        .map { candidate ->
            if (sameLocator(candidate) && candidate.videoTrackIndex == null) {
                candidate.copy(unavailableQuality = true)
            } else candidate
        }

    val variants = buildList {
        for (base in baseCandidates) {
            for (track in actualTracks) {
                val metadata = base.transportMetadata.toMutableMap().apply {
                    put("movia_adaptive_variant", "1")
                    put("zona_video_group_index", track.groupIndex.toString())
                    track.groupId.trim().takeIf { it.isNotBlank() }?.let { put("zona_video_group_id", it) }
                }
                add(
                    base.copy(
                        stableStreamId = "${base.stableStreamId}:v${track.groupIndex}t${track.trackIndex}h${track.height}",
                        quality = StreamVariantSelection.canonicalQualityLabel("${track.height}p"),
                        resolution = "${track.width}x${track.height}",
                        resolutionWidth = track.width,
                        resolutionHeight = track.height,
                        videoTrackIndex = track.trackIndex,
                        unavailableQuality = false,
                        transportMetadata = metadata,
                    ),
                )
            }
        }
    }
    return retained + variants
}


internal fun locateProviderTrackByMetadata(
    groups: List<TrackGroupDescriptor>,
    providerIndex: Int,
    expectedLanguage: String? = null,
): TrackOverrideLocation? {
    if (providerIndex < 0) return null
    val expected = expectedLanguage?.trim()?.lowercase().orEmpty()
    val suffix = Regex("(?:^|[^0-9])${providerIndex}$")
    val exact = buildList {
        groups.forEachIndexed { groupOrdinal, group ->
            group.formats.forEachIndexed { trackIndex, format ->
                val identity = listOf(group.id, format.label).joinToString(" ").lowercase()
                if (suffix.containsMatchIn(identity)) {
                    add(Triple(groupOrdinal, trackIndex, format.language.trim().lowercase()))
                }
            }
        }
    }
    val preferred = exact.firstOrNull { expected.isNotBlank() && it.third.startsWith(expected) }
        ?: exact.firstOrNull()
    if (preferred != null) return TrackOverrideLocation(preferred.first, preferred.second)
    return locateProviderTrackIndex(groups.map { it.formats.size }, providerIndex)
}

internal fun samePlaybackLocator(left: StreamCandidate, right: StreamCandidate): Boolean =
    left.url.trim() == right.url.trim() &&
        left.headers == right.headers &&
        left.userAgent == right.userAgent &&
        left.mimeType == right.mimeType &&
        left.drmScheme == right.drmScheme &&
        left.drmLicenseUrl == right.drmLicenseUrl &&
        left.transport.equals(right.transport, ignoreCase = true)

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

    fun setRequestProfile(profile: StreamRequestProfile) {
        requestProfile = profile
    }

    override fun createDataSource(): DataSource = DynamicHeaderDataSource(
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
            val httpFactory = DefaultHttpDataSource.Factory()
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

internal fun normalizePlaybackArtworkUrl(rawUrl: String?): String? {
    val value = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("/") && !value.startsWith("//") -> "https://image.tmdb.org/t/p/w780$value"
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("content://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true) -> value
        else -> null
    }
}

internal fun playbackNotificationTitle(request: PlaybackRequest): String =
    if (request.isSeries && request.seasonNumber != null && request.episodeNumber != null) {
        "${request.title} · S${request.seasonNumber.toString().padStart(2, '0')}E${request.episodeNumber.toString().padStart(2, '0')}"
    } else {
        request.title
    }

internal fun startupWatchdogMsForCandidate(candidate: StreamCandidate): Long {
    val transport = candidate.transport.trim().lowercase()
    val rawUrl = candidate.url.trim()
    val p2p = transport in setOf("torrent", "p2p", "torrent_p2p", "magnet", "local_gateway") ||
        rawUrl.startsWith("magnet:", ignoreCase = true) ||
        rawUrl.contains("127.0.0.1:8888/stream", ignoreCase = true) ||
        rawUrl.contains("localhost:8888/stream", ignoreCase = true)
    return if (p2p) P2P_STARTUP_WATCHDOG_MS else STARTUP_WATCHDOG_MS
}

internal fun shouldRetryWarmedP2pCandidate(
    candidate: StreamCandidate?,
    reason: String,
    remainingBudgetMs: Long,
    alreadyRetried: Boolean,
): Boolean {
    val current = candidate ?: return false
    if (alreadyRetried || reason != "STARTUP_TIMEOUT") return false
    if (remainingBudgetMs < P2P_WARM_RETRY_MIN_REMAINING_MS) return false
    val transport = current.transport.trim().lowercase()
    val rawUrl = current.url.trim()
    val p2p = transport in setOf("torrent", "p2p", "torrent_p2p", "magnet", "local_gateway") ||
        rawUrl.startsWith("magnet:", ignoreCase = true) ||
        rawUrl.contains("127.0.0.1:8888/stream", ignoreCase = true) ||
        rawUrl.contains("localhost:8888/stream", ignoreCase = true)
    return p2p
}

internal fun shouldPrewarmAdjacentEpisode(
    request: PlaybackRequest?,
    status: PlaybackStatus,
    positionMs: Long,
    durationMs: Long,
    alreadyPrewarmedGeneration: Long?,
): Boolean {
    val current = request ?: return false
    if (!current.isSeries || current.seasonNumber == null || current.episodeNumber == null) return false
    if (status != PlaybackStatus.READY || durationMs <= 0L || positionMs < 0L) return false
    if (alreadyPrewarmedGeneration == current.generationId) return false
    val remaining = durationMs - positionMs
    return remaining in 1..ADJACENT_PREWARM_REMAINING_MS
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

    private var playbackGeneration = 0L
    private var adjacentPrewarmGeneration: Long? = null
    private var playbackRequest: PlaybackRequest? = null
    private var candidates: List<StreamCandidate> = emptyList()
    private var activeCandidate: StreamCandidate? = null
    private var activeConsumedUri: String? = null
    private val failedStreamIds = linkedSetOf<String>()
    private val problemTracker = StreamProblemTracker(maxEntries = MAX_PROBLEM_MEMORY)
    private val reloadAttemptedStreamIds = linkedSetOf<String>()
    private val p2pWarmRetryStreamIds = linkedSetOf<String>()
    private var recoveryAttemptCount = 0
    private var recoveryAttemptBudget = 1
    private var watchdogJob: Job? = null
    private var readyDeadlineJob: Job? = null
    private var recoveryDeadlineJob: Job? = null
    private var stallWatchdogJob: Job? = null
    private var recoveryJob: Job? = null
    private var appliedTrackSelectionKey: String? = null
    private var readyBudgetStartedAtMs: Long = 0L
    private var recoveryBudgetStartedAtMs: Long = 0L

    // Compatibility getters; state and candidate metadata remain authoritative.
    val activeTitle: String? get() = _state.value.displayTitle.takeIf { _state.value.hasMedia }
    val activeSourceUri: String? get() = activeConsumedUri
    val isPlaying: Boolean get() = _state.value.isPlaying
    val playWhenReady: Boolean get() = _state.value.playWhenReady
    val playbackState: Int
        get() = when (_state.value.status) {
            PlaybackStatus.IDLE -> Player.STATE_IDLE
            PlaybackStatus.BUFFERING -> Player.STATE_BUFFERING
            PlaybackStatus.READY -> Player.STATE_READY
            PlaybackStatus.ENDED -> Player.STATE_ENDED
        }

    private val sessionActivity: PendingIntent = PendingIntent.getActivity(
        appContext,
        1001,
        Intent(appContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_FROM_PLAYBACK_NOTIFICATION
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val mediaSession: MediaSession = MediaSession.Builder(appContext, player)
        .setSessionActivity(sessionActivity)
        .build()

    init {
        MoviaPlaybackRegistry.current = this
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    watchdogJob?.cancel()
                    stallWatchdogJob?.cancel()
                    completeReadyBudget()
                    completeRecoveryBudget()
                }
                publishSnapshot()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        watchdogJob?.cancel()
                        stallWatchdogJob?.cancel()
                        // HLS/DASH rendition dimensions may only be populated once Media3 is READY.
                        materializeCurrentAdaptiveVideoVariants(player.currentTracks)
                        completeReadyBudget()
                        completeRecoveryBudget()
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
                materializeCurrentAdaptiveVideoVariants(tracks)
                applyCandidateTrackOverrides(tracks)
                publishSnapshot()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
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
                if (_state.value.hasMedia) {
                    publishSnapshot()
                    maybeScheduleAdjacentPrewarm()
                }
                delay(250L)
            }
        }
    }

    private fun maybeScheduleAdjacentPrewarm() {
        val request = playbackRequest ?: return
        val state = _state.value
        if (!shouldPrewarmAdjacentEpisode(
                request = request,
                status = state.status,
                positionMs = state.currentPositionMs,
                durationMs = state.totalDurationMs,
                alreadyPrewarmedGeneration = adjacentPrewarmGeneration,
            )
        ) return
        adjacentPrewarmGeneration = request.generationId
        scope.launch {
            DomainPlaybackResolver.prewarmNextEpisode(request)
        }
    }

    private fun nextPlaybackGeneration(): Long {
        playbackGeneration += 1L
        return playbackGeneration
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        generation == playbackGeneration

    private fun beginReadyBudget(generation: Long) {
        readyBudgetStartedAtMs = SystemClock.elapsedRealtime()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = scope.launch {
            delay(PLAYBACK_READY_TARGET_MS)
            if (!isActive || !isCurrentGeneration(generation) || readyBudgetStartedAtMs <= 0L) {
                return@launch
            }
            if (player.playbackState != Player.STATE_READY && !player.isPlaying) {
                Log.w(TAG, "Absolute READY deadline fired after ${PLAYBACK_READY_TARGET_MS}ms")
                failPlayback("READY_DEADLINE")
            }
        }
    }

    private fun completeReadyBudget() {
        readyBudgetStartedAtMs = 0L
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
    }

    private fun beginRecoveryBudget(generation: Long) {
        if (!isCurrentGeneration(generation) || readyBudgetStartedAtMs > 0L || recoveryBudgetStartedAtMs > 0L) return
        recoveryBudgetStartedAtMs = SystemClock.elapsedRealtime()
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = scope.launch {
            delay(PLAYBACK_RECOVERY_TARGET_MS)
            if (!isActive || !isCurrentGeneration(generation) || recoveryBudgetStartedAtMs <= 0L) {
                return@launch
            }
            Log.w(TAG, "Absolute recovery deadline fired after ${PLAYBACK_RECOVERY_TARGET_MS}ms")
            failPlayback("RECOVERY_DEADLINE")
        }
    }

    private fun completeRecoveryBudget() {
        recoveryBudgetStartedAtMs = 0L
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        recoveryAttemptCount = 0
    }

    private fun remainingReadyBudgetMs(): Long {
        val startedAt = readyBudgetStartedAtMs
        if (startedAt <= 0L) return Long.MAX_VALUE
        return remainingPlaybackReadyBudgetMs(startedAt, SystemClock.elapsedRealtime())
    }

    private fun remainingRecoveryBudgetMs(): Long {
        val startedAt = recoveryBudgetStartedAtMs
        if (startedAt <= 0L) return Long.MAX_VALUE
        return remainingPlaybackRecoveryBudgetMs(startedAt, SystemClock.elapsedRealtime())
    }

    private fun remainingAttemptBudgetMs(): Long = minOf(
        remainingReadyBudgetMs(),
        remainingRecoveryBudgetMs(),
    )

    private fun readyBudgetExpired(): Boolean =
        readyBudgetStartedAtMs > 0L && remainingReadyBudgetMs() <= 0L

    private fun attemptBudgetExpired(): Boolean =
        (readyBudgetStartedAtMs > 0L || recoveryBudgetStartedAtMs > 0L) && remainingAttemptBudgetMs() <= 0L

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
        _streamOptions.value = candidates
            .filter(StreamVariantSelection::isAllowed)
            .map(StreamCandidate::toStreamOption)
    }

    private fun requestContext(request: PlaybackRequest): StreamRankingContext =
        StreamRankingContext(
            requestedVoice = request.requestedVoice,
            requestedQuality = request.requestedQuality,
            strictRequestedVoice = request.strictRequestedVoice,
            strictRequestedQuality = request.strictRequestedQuality,
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
        return exact ?: StreamRanker.selectBest(
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
        if (failureClass == StreamFailureClass.NETWORK) {
            // A provider may expose one physical HLS/DASH locator as many logical
            // voice/quality variants. Once the locator itself fails on the
            // network, retrying every sibling variant only burns startup time.
            // Skip sibling variants for this playback generation, while keeping
            // the longer-lived problem tracker threshold unchanged.
            candidates.filter { samePlaybackLocator(it, candidate) }.forEach { sibling ->
                failedStreamIds += sibling.stableStreamId
            }
            while (failedStreamIds.size > MAX_PROBLEM_MEMORY) {
                failedStreamIds.remove(failedStreamIds.first())
            }
        }
        if (problemTracker.shouldMarkProblem(candidate, failureClass)) {
            markProblem(candidate)
        }
        publishCandidateOptions()
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

    private fun materializeCurrentAdaptiveVideoVariants(tracks: Tracks) {
        val active = activeCandidate ?: return
        val videoTracks = buildList {
            tracks.groups.forEachIndexed { groupIndex, group ->
                if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
                for (trackIndex in 0 until group.length) {
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.mediaTrackGroup.getFormat(trackIndex)
                    val width = format.width.takeIf { it > 0 } ?: continue
                    val height = format.height.takeIf { it > 0 } ?: continue
                    add(
                        AdaptiveVideoTrackDescriptor(
                            groupId = group.mediaTrackGroup.id,
                            groupIndex = groupIndex,
                            trackIndex = trackIndex,
                            width = width,
                            height = height,
                            bitrate = format.bitrate.takeIf { it > 0 } ?: 0,
                        ),
                    )
                }
            }
        }
        if (videoTracks.isNotEmpty()) {
            Log.i(TAG, "Adaptive video tracks id=${active.stableStreamId} count=${videoTracks.size} heights=${videoTracks.map { it.height }.distinct().sorted()}")
        }
        val updated = buildAdaptiveVideoVariants(candidates, active, videoTracks)
        if (updated != candidates) {
            candidates = updated
            publishCandidateOptions()
        }

        // Auto starts from provider metadata before Media3 knows the actual adaptive rendition.
        // Once a real video track is selected, expose that factual quality instead of the stale provider label.
        if (active.videoTrackIndex == null) {
            val selectedVideo = buildList {
                tracks.groups.forEachIndexed { groupIndex, group ->
                    if (group.type != C.TRACK_TYPE_VIDEO) return@forEachIndexed
                    for (trackIndex in 0 until group.length) {
                        if (!group.isTrackSelected(trackIndex)) continue
                        val format = group.mediaTrackGroup.getFormat(trackIndex)
                        val height = format.height.takeIf { it > 0 } ?: continue
                        add(Triple(groupIndex, trackIndex, height))
                    }
                }
            }.firstOrNull()
            if (selectedVideo != null) {
                val (groupIndex, trackIndex, height) = selectedVideo
                val actual = candidates.firstOrNull { candidate ->
                    candidate.transportMetadata["movia_adaptive_variant"] == "1" &&
                        samePlaybackLocator(candidate, active) &&
                        candidate.videoTrackIndex == trackIndex &&
                        candidate.transportMetadata["zona_video_group_index"]?.toIntOrNull() == groupIndex &&
                        candidate.audioTrackIndex == active.audioTrackIndex &&
                        candidate.voice.equals(active.voice, ignoreCase = true) &&
                        StreamVariantSelection.qualityHeight(candidate.quality) == height &&
                        StreamVariantSelection.isAllowed(candidate)
                }
                if (actual != null) {
                    activeCandidate = actual
                    _state.value = _state.value.copy(
                        activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                            activeStreamId = actual.stableStreamId,
                            activeQuality = actual.quality,
                            activeVoice = actual.voice,
                            source = actual.provider,
                        ),
                    )
                }
            }
        }
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

        fun groupAndTrackFor(
            type: Int,
            preferredId: String?,
            preferredIndex: Int?,
            providerTrackIndex: Int,
        ): Pair<Tracks.Group, Int>? {
            preferredId?.let { id ->
                tracks.groups.firstOrNull { group ->
                    group.type == type && group.mediaTrackGroup.id == id
                }?.takeIf { providerTrackIndex in 0 until it.length }
                    ?.let { return it to providerTrackIndex }
            }
            preferredIndex?.let { index ->
                tracks.groups.getOrNull(index)
                    ?.takeIf { it.type == type && providerTrackIndex in 0 until it.length }
                    ?.let { return it to providerTrackIndex }
            }

            // Provider indexes describe the logical rendition order. Media3 may
            // expose that order as one multi-track group or as several one-track
            // groups. Flatten audio/video groups so both representations map to
            // the same provider index. If duplicate failover groups exist, the
            // primary group set is encountered first.
            val typedGroups = tracks.groups.filter { it.type == type }
            val descriptors = typedGroups.map { group ->
                TrackGroupDescriptor(
                    id = group.mediaTrackGroup.id,
                    formats = (0 until group.length).map { trackIndex ->
                        val format = group.mediaTrackGroup.getFormat(trackIndex)
                        TrackFormatDescriptor(
                            label = format.label.orEmpty(),
                            language = format.language.orEmpty(),
                        )
                    },
                )
            }
            val expectedLanguage = if (type == C.TRACK_TYPE_AUDIO) candidate.language else null
            val location = locateProviderTrackByMetadata(descriptors, providerTrackIndex, expectedLanguage)
                ?: return null
            return typedGroups[location.groupOrdinal] to location.trackIndex
        }

        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        var applied = false
        if (videoTrackIndex != null) {
            groupAndTrackFor(C.TRACK_TYPE_VIDEO, videoGroupId, videoGroupIndex, videoTrackIndex)?.let { (group, trackIndex) ->
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                )
                Log.i(TAG, "Applied video track override id=${candidate.stableStreamId} providerIndex=$videoTrackIndex group=${group.mediaTrackGroup.id} track=$trackIndex")
                applied = true
            }
        }
        if (audioTrackIndex != null) {
            groupAndTrackFor(C.TRACK_TYPE_AUDIO, audioGroupId, audioGroupIndex, audioTrackIndex)?.let { (group, trackIndex) ->
                builder.setOverrideForType(
                    TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
                )
                val format = group.mediaTrackGroup.getFormat(trackIndex)
                Log.i(
                    TAG,
                    "Applied audio track override id=${candidate.stableStreamId} providerIndex=$audioTrackIndex group=${group.mediaTrackGroup.id} track=$trackIndex label=${format.label.orEmpty()} language=${format.language.orEmpty()}",
                )
                applied = true
            }
        }
        if (applied) {
            appliedTrackSelectionKey = key
            player.trackSelectionParameters = builder.build()
            return true
        }
        return false
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

    private fun notificationSubtitle(candidate: StreamCandidate): String? =
        listOf(candidate.voice, candidate.quality)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Не указано", ignoreCase = true) && !it.equals("Auto", ignoreCase = true) }
            .distinct()
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }

    private fun buildMediaItem(
        request: PlaybackRequest,
        candidate: StreamCandidate,
        consumedUri: String,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(request.canonicalEpisodeKey)
        .setUri(consumedUri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(playbackNotificationTitle(request))
                .setDisplayTitle(playbackNotificationTitle(request))
                .apply {
                    notificationSubtitle(candidate)?.let(::setSubtitle)
                    normalizePlaybackArtworkUrl(request.artworkUrl)?.let { setArtworkUri(Uri.parse(it)) }
                }
                .build(),
        )
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

    private fun startWatchdog(
        candidate: StreamCandidate,
        resumePositionMs: Long,
        generation: Long,
    ) {
        watchdogJob?.cancel()
        val candidateId = candidate.stableStreamId
        watchdogJob = scope.launch {
            val remainingMs = remainingAttemptBudgetMs()
            val candidateWaitMs = minOf(startupWatchdogMsForCandidate(candidate), remainingMs)
            if (candidateWaitMs > 0L) delay(candidateWaitMs)
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
        if (!isCurrentGeneration(generation) || attemptBudgetExpired()) return false
        val uri = consumedUri(candidate, request) ?: return false
        stallWatchdogJob?.cancel()
        activeCandidate = candidate
        activeConsumedUri = uri
        dataSourceFactory.setRequestProfile(StreamRequestProfile.from(candidate, uri))
        _state.value = _state.value.copy(
            status = PlaybackStatus.BUFFERING,
            switchState = PlaybackSwitchState.RESOLVING,
            statusMessage = "Подключение к потоку...",
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
            player.playWhenReady = true
            player.play()
            MoviaPlaybackService.ensureStarted(appContext)
            startWatchdog(candidate, resumePositionMs, generation)
            publishSnapshot()
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "Candidate preparation failed: id=${candidate.stableStreamId} msg=${throwable.message}", throwable)
            false
        }
    }

    private fun nextHealthyCandidates(request: PlaybackRequest): List<StreamCandidate> =
        StreamRanker.fallbackOrder(
            candidates = candidates,
            context = requestContext(request),
        ).filter {
            !failedStreamIds.contains(it.stableStreamId) && !it.isProblematic
        }

    private fun failPlayback(reason: String) {
        watchdogJob?.cancel()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        readyBudgetStartedAtMs = 0L
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        recoveryBudgetStartedAtMs = 0L
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        recoveryJob = null
        player.stop()
        player.clearMediaItems()
        activeCandidate = null
        activeConsumedUri = null
        _state.value = _state.value.copy(
            status = PlaybackStatus.IDLE,
            switchState = PlaybackSwitchState.FAILED,
            isPlaying = false,
            playWhenReady = false,
            statusMessage = PLAYBACK_USER_ERROR_MESSAGE,
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
        if (attemptBudgetExpired()) {
            failPlayback(if (readyBudgetStartedAtMs > 0L) "READY_DEADLINE_$reason" else "RECOVERY_DEADLINE_$reason")
            return
        }
        val failed = activeCandidate
        recoveryAttemptCount += 1
        if (recoveryAttemptCount > recoveryAttemptBudget) {
            recordFailure(failed, failureClass)
            failPlayback("EXHAUSTED_$reason")
            return
        }

        _state.value = _state.value.copy(
            status = PlaybackStatus.BUFFERING,
            switchState = PlaybackSwitchState.RESOLVING,
            statusMessage = "Восстанавливаем поток...",
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
            val reloadBudgetMs = minOf(RELOAD_TIMEOUT_MS, remainingAttemptBudgetMs())
            val refreshed = if (reloadBudgetMs > 0L) withTimeoutOrNull(reloadBudgetMs) {
                DomainPlaybackResolver.reloadStreamCandidate(
                    failed,
                    request.copy(
                        startPositionMs = resumePositionMs.coerceAtLeast(0L),
                        attempt = request.attempt + 1,
                    ),
                )
            } else null
            if (attemptBudgetExpired()) {
                failPlayback(if (readyBudgetStartedAtMs > 0L) "READY_DEADLINE_$reason" else "RECOVERY_DEADLINE_$reason")
                return
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
                    statusMessage = "Обновляем ссылку потока...",
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

        // A cold P2P request often leaves TorrServer metadata/head pieces warm
        // even when the first Media3 attempt misses the short startup watchdog.
        // Retry the same logical candidate once inside the existing absolute READY
        // budget before switching to another equally-cold torrent.
        if (failed != null && request != null && shouldRetryWarmedP2pCandidate(
                candidate = failed,
                reason = reason,
                remainingBudgetMs = remainingAttemptBudgetMs(),
                alreadyRetried = p2pWarmRetryStreamIds.contains(failed.stableStreamId),
            )
        ) {
            p2pWarmRetryStreamIds += failed.stableStreamId
            _state.value = _state.value.copy(
                statusMessage = "Повторяем прогретый P2P-поток...",
                activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                    source = failed.provider,
                    fallbackReason = "P2P_WARM_RETRY",
                ),
            )
            if (prepareCandidate(failed, request, resumePositionMs, generation)) return
        }

        // Reload/warm retry was unavailable or failed. Only now apply the
        // original failure to problem memory before normal fallback ordering.
        recordFailure(failed, failureClass)

        val currentRequest = playbackRequest
        if (currentRequest == null || !isCurrentGeneration(generation)) {
            failPlayback("REQUEST_UNAVAILABLE")
            return
        }
        val next = nextHealthyCandidates(currentRequest)
        for (candidate in next) {
            if (!isCurrentGeneration(generation)) return
            if (attemptBudgetExpired()) {
                failPlayback(if (readyBudgetStartedAtMs > 0L) "READY_DEADLINE_$reason" else "RECOVERY_DEADLINE_$reason")
                return
            }
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
        beginRecoveryBudget(generation)
        if (attemptBudgetExpired()) {
            failPlayback(if (readyBudgetStartedAtMs > 0L) "READY_DEADLINE_$reason" else "RECOVERY_DEADLINE_$reason")
            return
        }
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
        artworkUrl: String? = null,
        preferredQuality: String? = null,
        preferredVoice: String? = null,
        strictPreferredQuality: Boolean = false,
        strictPreferredVoice: Boolean = false,
        preferredStreamId: String? = null,
        candidateStreamOptions: List<StreamOption> = emptyList(),
    ) {
        val generation = nextPlaybackGeneration()
        completeRecoveryBudget()
        beginReadyBudget(generation)
        watchdogJob?.cancel()
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        failedStreamIds.clear()
        problemTracker.reset()
        reloadAttemptedStreamIds.clear()
        p2pWarmRetryStreamIds.clear()
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
            artworkUrl = artworkUrl,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            requestedVoice = preferredVoice?.trim()?.takeIf { it.isNotBlank() },
            requestedQuality = preferredQuality?.trim()?.takeIf { it.isNotBlank() },
            strictRequestedVoice = strictPreferredVoice,
            strictRequestedQuality = strictPreferredQuality,
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
        _streamOptions.value = seeds
            .filter(StreamVariantSelection::isAllowed)
            .map(StreamCandidate::toStreamOption)
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
            statusMessage = "Поиск доступных источников...",
            switchState = PlaybackSwitchState.RESOLVING,
            activeStreamSelection = ActiveStreamSelection(
                requestedStreamId = request.requestedStreamId,
                requestedQuality = request.requestedQuality,
                requestedVoice = request.requestedVoice,
            ),
        )
        scope.launch {
            val result = try {
                withTimeoutOrNull(PLAYBACK_RESOLVER_TOTAL_MS) {
                    DomainPlaybackResolver.resolveStreams(
                        request = request,
                        initialCandidates = seeds,
                    )
                } ?: PlaybackResolverResult.Error("Таймаут резолвера потоков (${PLAYBACK_RESOLVER_TOTAL_MS / 1000}с)")
            } catch (throwable: Throwable) {
                PlaybackResolverResult.Error("Резолвер потоков завершился с ошибкой", throwable)
            }
            if (!isCurrentGeneration(generation)) return@launch
            when (result) {
                is PlaybackResolverResult.Success -> {
                    if (readyBudgetExpired()) {
                        failPlayback("READY_DEADLINE_RESOLVER")
                        return@launch
                    }
                    val probeBudgetMs = playbackMediaProbeBudgetMs(remainingReadyBudgetMs())
                    val probed = if (probeBudgetMs > 0L) {
                        withTimeoutOrNull(probeBudgetMs) {
                            ZonaMediaProbe.expand(appContext, result.candidates)
                        } ?: result.candidates
                    } else {
                        result.candidates
                    }
                    if (readyBudgetExpired()) {
                        failPlayback("READY_DEADLINE_PROBE")
                        return@launch
                    }
                    candidates = StreamRanker.rankCandidates(
                        StreamDeduplicator.deduplicate(probed),
                        context = requestContext(request),
                    )
                    publishCandidateOptions()
                    recoveryAttemptBudget = (candidates.size.coerceAtLeast(1) * 2) + 1
                    val selected = selectInitialCandidate(request)
                    if (selected == null) {
                        failPlayback("NO_HEALTHY_CANDIDATE")
                        return@launch
                    }
                    _state.value = _state.value.copy(
                        activeStreamSelection = (_state.value.activeStreamSelection ?: ActiveStreamSelection()).copy(
                            source = selected.provider,
                        ),
                    )
                    if (!prepareCandidate(selected, request, request.startPositionMs, generation)) {
                        handleCandidateFailure("PREPARE_FAILED", request.startPositionMs, generation)
                    }
                }
                is PlaybackResolverResult.NoSource -> failPlayback("NO_SOURCE")
                is PlaybackResolverResult.Error -> failPlayback("RESOLVER_ERROR")
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
        if (stream.url.isBlank() || !_state.value.hasMedia) return
        val generation = nextPlaybackGeneration()
        completeRecoveryBudget()
        beginReadyBudget(generation)
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
            requestedQuality = stream.quality.trim().takeIf { it.isNotBlank() && !it.equals("Auto", true) },
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
                completeReadyBudget()
                return
            }
            activeCandidate = previousCandidate
            appliedTrackSelectionKey = null
        }

        _state.value = _state.value.copy(
            switchState = PlaybackSwitchState.RESOLVING,
            status = PlaybackStatus.BUFFERING,
            statusMessage = "Переключение потока...",
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

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
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

    fun setTrackPreferences(audioTrackId: String, subtitleTrackId: String?) {
        _state.value = _state.value.copy(
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            lastUpdatedTimestamp = System.currentTimeMillis(),
        )
    }

    fun stopAndClear() {
        nextPlaybackGeneration()
        watchdogJob?.cancel()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        readyBudgetStartedAtMs = 0L
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        recoveryBudgetStartedAtMs = 0L
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
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
        val status = if (
            transportStatus == PlaybackStatus.IDLE &&
            current.switchState in setOf(PlaybackSwitchState.RESOLVING, PlaybackSwitchState.BUFFERING)
        ) {
            PlaybackStatus.BUFFERING
        } else {
            transportStatus
        }
        val selection = if (status == PlaybackStatus.READY && activeCandidate != null) {
            val selected = activeCandidate ?: return
            (current.activeStreamSelection ?: ActiveStreamSelection()).copy(
                activeStreamId = selected.stableStreamId,
                activeQuality = selected.quality,
                activeVoice = selected.voice,
                source = selected.provider,
            )
        } else {
            current.activeStreamSelection
        }
        val switchState = when {
            current.switchState == PlaybackSwitchState.FAILED -> PlaybackSwitchState.FAILED
            status == PlaybackStatus.READY -> PlaybackSwitchState.READY
            status == PlaybackStatus.BUFFERING -> PlaybackSwitchState.BUFFERING
            else -> current.switchState
        }
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
            activeStreamSelection = selection,
        )
    }

    fun release() {
        if (MoviaPlaybackRegistry.current === this) MoviaPlaybackRegistry.current = null
        watchdogJob?.cancel()
        readyDeadlineJob?.cancel()
        readyDeadlineJob = null
        readyBudgetStartedAtMs = 0L
        recoveryDeadlineJob?.cancel()
        recoveryDeadlineJob = null
        recoveryBudgetStartedAtMs = 0L
        stallWatchdogJob?.cancel()
        recoveryJob?.cancel()
        scope.cancel()
        mediaSession.release()
        player.release()
        playbackRequest = null
        candidates = emptyList()
        activeCandidate = null
        activeConsumedUri = null
        appliedTrackSelectionKey = null
        _streamOptions.value = emptyList()
        _state.value = PlaybackState()
    }
}
