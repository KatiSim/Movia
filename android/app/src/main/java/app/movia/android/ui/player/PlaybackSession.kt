package app.movia.android.ui.player

import android.content.Context
import android.net.Uri
import android.os.Looper
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
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.PlaybackStatus
import app.movia.android.domain.model.PlaybackSwitchState
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.sameRequestedVariant
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
private const val STARTUP_WATCHDOG_MS = 15_000L
private const val RELOAD_TIMEOUT_MS = 20_000L
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
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(15_000)
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
            300_000,
            360_000,
            2_500,
            5_000,
        )
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
    private var recoveryJob: Job? = null
    private var appliedTrackSelectionKey: String? = null

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

    val mediaSession: MediaSession = MediaSession.Builder(appContext, player).build()

    init {
        MoviaPlaybackRegistry.current = this
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) watchdogJob?.cancel()
                publishSnapshot()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) watchdogJob?.cancel()
                publishSnapshot()
            }

            override fun onTracksChanged(tracks: Tracks) {
                applyCandidateTrackOverrides(tracks)
                publishSnapshot()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                publishSnapshot()
            }

            override fun onPlayerError(error: PlaybackException) {
                watchdogJob?.cancel()
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

    private fun nextPlaybackGeneration(): Long {
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
        _streamOptions.value = candidates.map(StreamCandidate::toStreamOption)
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
            val location = locateProviderTrackIndex(typedGroups.map { it.length }, providerTrackIndex)
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

    private fun buildMediaItem(
        request: PlaybackRequest,
        candidate: StreamCandidate,
        consumedUri: String,
    ): MediaItem = MediaItem.Builder()
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

    /** Prepare one candidate; no URL preflight is performed. */
    private fun prepareCandidate(
        candidate: StreamCandidate,
        request: PlaybackRequest,
        resumePositionMs: Long,
        generation: Long,
    ): Boolean {
        if (!isCurrentGeneration(generation)) return false
        val uri = consumedUri(candidate, request) ?: return false
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
        player.stop()
        player.clearMediaItems()
        activeCandidate = null
        activeConsumedUri = null
        _state.value = _state.value.copy(
            status = PlaybackStatus.IDLE,
            switchState = PlaybackSwitchState.FAILED,
            isPlaying = false,
            playWhenReady = false,
            statusMessage = "Источники для данного тайтла временно недоступны",
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
            val refreshed = withTimeoutOrNull(RELOAD_TIMEOUT_MS) {
                DomainPlaybackResolver.reloadStreamCandidate(
                    failed,
                    request.copy(
                        startPositionMs = resumePositionMs.coerceAtLeast(0L),
                        attempt = request.attempt + 1,
                    ),
                )
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

        // Reload was unavailable or failed. Only now apply the original failure
        // to the problem memory, matching the verified Zona ordering.
        recordFailure(failed, failureClass)

        val currentRequest = playbackRequest
        if (currentRequest == null || !isCurrentGeneration(generation)) {
            failPlayback("REQUEST_UNAVAILABLE")
            return
        }
        val next = nextHealthyCandidates(currentRequest)
        for (candidate in next) {
            if (!isCurrentGeneration(generation)) return
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
    ) {
        val generation = nextPlaybackGeneration()
        watchdogJob?.cancel()
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
                DomainPlaybackResolver.resolveStreams(
                    request = request,
                    initialCandidates = seeds,
                )
            } catch (throwable: Throwable) {
                PlaybackResolverResult.Error("Резолвер потоков завершился с ошибкой", throwable)
            }
            if (!isCurrentGeneration(generation)) return@launch
            when (result) {
                is PlaybackResolverResult.Success -> {
                    val probed = ZonaMediaProbe.expand(appContext, result.candidates)
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
        watchdogJob?.cancel()
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
