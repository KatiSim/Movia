package app.movia.android.ui.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import app.movia.android.domain.model.ActiveStreamSelection
import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.PlaybackStatus
import app.movia.android.domain.model.PlaybackSwitchState
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.sameRequestedVariant
import app.movia.android.domain.model.withCanonicalStreamId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor

internal object MoviaPlaybackRegistry {
    @Volatile
    var current: PlaybackSession? = null
        private set

    @Synchronized
    fun obtain(context: Context): PlaybackSession {
        current?.let { return it }
        return PlaybackSession(context.applicationContext).also { session ->
            current = session
        }
    }

    @Synchronized
    fun clearIfCurrent(session: PlaybackSession) {
        if (current === session) current = null
    }

    @Synchronized
    fun releaseCurrent() {
        current?.release()
    }
}

private const val TAG = "MoviaPlayer"

object MoviaFileLogger {
    private var logFile: java.io.File? = null

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            logFile = java.io.File(dir, "movia_debug.log")
        } catch (_: Exception) {}
    }

    fun log(tag: String, message: String, isError: Boolean = false) {
        if (isError) {
            Log.e(tag, message)
        } else {
            Log.d(tag, message)
        }
        try {
            logFile?.appendText("[${System.currentTimeMillis()}] $tag: $message\n")
        } catch (_: Exception) {}
    }
}

private fun inferMimeType(uri: String): String? {
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

private fun drmUuidForScheme(rawScheme: String?): java.util.UUID? = when (rawScheme?.trim()?.lowercase()) {
    "widevine", "com.widevine.alpha" -> C.WIDEVINE_UUID
    "playready", "com.microsoft.playready" -> C.PLAYREADY_UUID
    "clearkey", "org.w3.clearkey" -> C.CLEARKEY_UUID
    else -> null
}

private fun isAllowedDrmLicenseUrl(rawUrl: String?): Boolean {
    val value = rawUrl?.trim().orEmpty()
    if (value.isBlank()) return false
    return value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("http://127.0.0.1:", ignoreCase = true) ||
        value.startsWith("http://localhost:", ignoreCase = true)
}

class DynamicHeaderDataSourceFactory(
    private val context: Context,
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return DynamicHeaderDataSource(context.applicationContext, userAgent)
    }
}

class DynamicHeaderDataSource(
    private val context: Context,
    private val userAgent: String
) : DataSource {
    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners.add(transferListener)
        delegate?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host?.lowercase().orEmpty()
        val headers = mutableMapOf<String, String>()
        headers["Accept"] = "*/*"

        when {
            host.contains("kodik") -> {
                headers["Referer"] = "https://kodik.info/"
                headers["Origin"] = "https://kodik.info"
            }
            host.contains("hdrezka") || host.contains("rezka") || host.contains("voidboost") -> {
                headers["Referer"] = "https://hdrezka.ag/"
                headers["Origin"] = "https://hdrezka.ag"
            }
            host.contains("collaps") -> {
                headers["Referer"] = "https://api.collaps.org/"
                headers["Origin"] = "https://api.collaps.org"
            }
            host.contains("alloha") -> {
                headers["Referer"] = "https://alloha.tv/"
                headers["Origin"] = "https://alloha.tv"
            }
            host == "127.0.0.1" || host == "localhost" -> {
                // Isolated localhost: no external Referer/Origin
            }
        }

        val isLocalGateway = host == "127.0.0.1" || host == "localhost"
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(if (isLocalGateway) 5_000 else 8_000)
            .setReadTimeoutMs(if (isLocalGateway) 45_000 else 15_000)
            .setDefaultRequestProperties(headers)

        val ds = DefaultDataSource.Factory(context, httpFactory).createDataSource()
        for (l in listeners) {
            ds.addTransferListener(l)
        }
        delegate = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        return delegate?.read(buffer, offset, length) ?: -1
    }

    override fun getUri(): android.net.Uri? {
        return delegate?.uri
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return delegate?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        delegate?.close()
        delegate = null
    }
}

class PlaybackSession(context: Context) {
    private val extractorsFactory = DefaultExtractorsFactory().apply {
        setConstantBitrateSeekingEnabled(true)
        // Keep Matroska Cues enabled. Disabling them makes MKV playback effectively
        // unseekable and forces seekTo() back to the beginning of the file.
        setMatroskaExtractorFlags(0)
    }

    private val dataSourceFactory = DynamicHeaderDataSourceFactory(context.applicationContext)
    private val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)

    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(mediaSourceFactory)
        .setSeekBackIncrementMs(10_000L)
        .setSeekForwardIncrementMs(10_000L)
        .build().apply {
            repeatMode = Player.REPEAT_MODE_OFF
            playWhenReady = false
        }

    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Main.immediate)
    private val playbackJob = SupervisorJob(rootJob)
    private val playbackScope = CoroutineScope(playbackJob + Dispatchers.Main.immediate)
    private var activeSource: String? = null
    private var alternativeStreams: List<StreamOption> = emptyList()
    private var alternativeStreamIndex: Int = 0
    private var totalCandidatesCount: Int = 0
    private var watchdogJob: Job? = null
    private var recoveryJob: Job? = null
    private var switchJob: Job? = null
    private var currentContentYear: Int? = null
    private var currentStreamOption: StreamOption? = null
    private var switchGeneration: Long = 0L
    private var switchInProgress: Boolean = false
    private var refreshAttempts: Int = 0
    private val attemptedStreamUrls = linkedSetOf<String>()

    private fun resetAndStartWatchdog(resumePos: Long, sourceIndex: Int) {
        watchdogJob?.cancel()
        val generation = switchGeneration
        watchdogJob = playbackScope.launch {
            delay(45000L)
            if (
                isActive &&
                generation == switchGeneration &&
                !switchInProgress &&
                player.playbackState != Player.STATE_READY &&
                !player.isPlaying
            ) {
                val liveResumePos = player.currentPosition.coerceAtLeast(0L).takeIf { it > 0L } ?: resumePos
                Log.w(
                    "MoviaStreamDebug",
                    "WATCHDOG_TIMEOUT source=" + sourceIndex + " position=" + liveResumePos
                )
                switchNextMirror(liveResumePos)
            }
        }
    }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    private val _streamOptions = MutableStateFlow<List<StreamOption>>(emptyList())
    val streamOptions: StateFlow<List<StreamOption>> = _streamOptions.asStateFlow()

    // Compatibility getters; all values are derived from the single StateFlow store.
    val activeTitle: String? get() = _state.value.displayTitle.takeIf { _state.value.hasMedia }
    val activeSourceUri: String? get() = activeSource
    val isPlaying: Boolean get() = _state.value.isPlaying
    val playWhenReady: Boolean get() = _state.value.playWhenReady
    val playbackState: Int
        get() = when (_state.value.status) {
            PlaybackStatus.IDLE -> Player.STATE_IDLE
            PlaybackStatus.BUFFERING -> Player.STATE_BUFFERING
            PlaybackStatus.READY -> Player.STATE_READY
            PlaybackStatus.ENDED -> Player.STATE_ENDED
            PlaybackStatus.FAILED -> Player.STATE_IDLE
        }

    val mediaSession: MediaSession = MediaSession.Builder(context.applicationContext, player).build()

    init {
        MoviaFileLogger.init(context.applicationContext)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) watchdogJob?.cancel()
                Log.d(
                    "MoviaStreamDebug",
                    "isPlaying=$isPlaying position=${player.currentPosition.coerceAtLeast(0L)} duration=${player.duration}"
                )
                publishSnapshot()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) watchdogJob?.cancel()
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                }
                Log.d(
                    "MoviaStreamDebug",
                    "state=$stateName position=${player.currentPosition.coerceAtLeast(0L)} buffered=${player.bufferedPosition.coerceAtLeast(0L)} duration=${player.duration}"
                )
                publishSnapshot()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publishSnapshot()
            override fun onPlayerError(error: PlaybackException) {
                watchdogJob?.cancel()
                val resumePos = player.currentPosition.coerceAtLeast(0L).takeIf { it > 0L } ?: _state.value.currentPositionMs
                Log.e("MoviaStreamDebug", "Ошибка воспроизведения ExoPlayer (${error.errorCodeName}): ${error.message} для тайтла: '${_state.value.displayTitle}'")
                _state.value = _state.value.copy(
                    status = PlaybackStatus.BUFFERING,
                    switchState = PlaybackSwitchState.BUFFERING,
                    statusMessage = "Проверяем другое зеркало выбранного варианта..."
                )
                switchNextMirror(resumePos)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                MoviaFileLogger.log(
                    "MoviaAnalytics",
                    "timestamp=" + System.currentTimeMillis() +
                        " event=MEDIA_ITEM_TRANSITION reason=" + reason +
                        " mediaId=" + (mediaItem?.mediaId ?: "none") +
                        " streamId=" + (_state.value.activeStreamSelection?.activeStreamId
                            ?: _state.value.activeStreamSelection?.requestedStreamId ?: "none") +
                        " position=" + player.currentPosition
                )
                publishSnapshot()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                MoviaFileLogger.log(
                    "MoviaAnalytics",
                    "timestamp=" + System.currentTimeMillis() +
                        " event=POSITION_DISCONTINUITY reason=" + reason +
                        " mediaId=" + (player.currentMediaItem?.mediaId ?: "none") +
                        " position=" + newPosition.positionMs +
                        " oldPosition=" + oldPosition.positionMs
                )
                publishSnapshot()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onEvents(
                player: Player,
                events: AnalyticsListener.Events,
            ) {
                val selection = _state.value.activeStreamSelection
                MoviaFileLogger.log(
                    "MoviaAnalytics",
                    "timestamp=" + System.currentTimeMillis() +
                        " events=" + events +
                        " mediaId=" + (player.currentMediaItem?.mediaId ?: "none") +
                        " streamId=" + (selection?.activeStreamId ?: selection?.requestedStreamId ?: "none") +
                        " quality=" + (selection?.activeQuality ?: selection?.requestedQuality ?: "none") +
                        " voice=" + (selection?.activeVoice ?: selection?.requestedVoice ?: "none") +
                        " source=" + (selection?.source ?: "none") +
                        " position=" + player.currentPosition
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

    private fun safeStreamLogLabel(rawUrl: String): String {
        return try {
            val parsed = java.net.URI(rawUrl)
            val host = parsed.host ?: "local"
            val fileName = parsed.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "stream"
            val safeFile = if (fileName.length > 96) fileName.take(93) + "..." else fileName
            "$host/$safeFile"
        } catch (_: Exception) {
            "stream"
        }
    }

    private fun streamPreferenceRank(stream: StreamOption): Int {
        val v = stream.voice.lowercase()
        return when {
            v.contains("дубляж") || v.contains("дублирован") || v.contains("dub.ru") -> 0
            v.contains("lostfilm") -> 1
            v.contains("red head sound") || v.contains("rhs") -> 2
            v.contains("hdrezka") || v.contains("rezka") -> 3
            v.contains("кубик") -> 4
            v.contains("кураж") -> 5
            v.contains("newstudio") -> 6
            v.contains("jaskier") || v.contains("яскьер") -> 6
            v.contains("alexfilm") || v.contains("tvshows") || v.contains("flarrow") || v.contains("le-vitation") -> 6
            v.contains("профессиональн") || v.contains("мво") || v.contains("двухголос") || v.contains("дво") -> 7
            v.contains("русск") || v.contains("rus") -> 8
            v.contains("укра") || v.contains("ukr") || v.contains("dnipro") -> 15
            v.contains("original") || v.contains("english") -> 20
            else -> 10
        }
    }

    private fun qualityRank(quality: String): Int {
        val q = quality.lowercase()
        return when {
            q.contains("2160") || q.contains("4k") -> 2160
            q.contains("1440") -> 1440
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            else -> 0
        }
    }

    private fun defaultQualityRank(quality: String): Int {
        val q = quality.lowercase()
        return when {
            q.contains("1080") -> 0
            q.contains("720") -> 1
            q.contains("2160") || q.contains("4k") -> 2
            q.contains("480") -> 3
            q.contains("360") -> 4
            else -> 5
        }
    }

    private fun isDirectStream(stream: StreamOption): Boolean {
        val u = stream.url.lowercase()
        return (u.startsWith("http://") || u.startsWith("https://")) &&
            !u.contains("127.0.0.1:8888/stream?") &&
            !u.contains("localhost:8888/stream?")
    }

    private fun sourcePreferenceRank(stream: StreamOption): Int {
        val s = stream.source?.lowercase().orEmpty()
        return when {
            isDirectStream(stream) || s.contains("zona") -> 0
            s == "rutor" -> 1
            s == "yts" -> 2
            s == "apibay" -> 3
            else -> 4
        }
    }

    private fun orderStreams(
        streams: List<StreamOption>,
        season: Int? = _state.value.seasonNumber,
        episode: Int? = _state.value.episodeNumber,
    ): List<StreamOption> = streams
        .map { it.withCanonicalStreamId(season, episode) }
        .distinctBy { it.streamId }
        .sortedWith(
            compareBy<StreamOption> { if (isDirectStream(it)) 0 else 1 }
                .thenBy { streamPreferenceRank(it) }
                .thenBy { sourcePreferenceRank(it) }
                .thenBy { defaultQualityRank(it.quality) }
                .thenByDescending { it.seeders }
        )

    private suspend fun isHtmlOrInvalidUrl(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext false
        }
        if (rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) {
            return@withContext false
        }

        val label = safeStreamLogLabel(rawUrl)
        fun probe(method: String, rangeFallback: Boolean = false): Boolean? {
            var connection: java.net.HttpURLConnection? = null
            return try {
                connection = (java.net.URL(rawUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = 4_000
                    readTimeout = 4_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                    setRequestProperty("Accept", "*/*")
                    if (rangeFallback) setRequestProperty("Range", "bytes=0-0")
                }
                val code = connection.responseCode
                val contentType = connection.contentType?.lowercase().orEmpty()
                val contentLength = connection.contentLengthLong
                when {
                    code == 405 || code == 501 -> null
                    code >= 400 -> {
                        Log.d("MoviaStreamDebug", "HEAD preflight $label rejected: HTTP $code")
                        false
                    }
                    contentType.contains("text/html") || contentType.contains("text/plain") -> {
                        Log.d("MoviaStreamDebug", "HEAD preflight $label rejected: contentType=$contentType")
                        false
                    }
                    else -> {
                        Log.d("MoviaStreamDebug", "HEAD preflight $label accepted: HTTP $code, type=$contentType, length=$contentLength")
                        true
                    }
                }
            } catch (e: Exception) {
                Log.d("MoviaStreamDebug", "Preflight $label error (${e.javaClass.simpleName}); fallback/player may retry")
                null
            } finally {
                connection?.disconnect()
            }
        }

        val headResult = probe("HEAD")
        if (headResult != null) return@withContext !headResult
        val rangeResult = probe("GET", rangeFallback = true)
        return@withContext rangeResult == false
    }

    private fun streamDisplayLabel(stream: StreamOption): String {
        val voice = stream.voice.trim().takeUnless { it.isBlank() || it.equals("Не указано", ignoreCase = true) }
        val quality = stream.quality.trim().takeUnless { it.isBlank() || it.equals("Не указано", ignoreCase = true) }
        return when {
            voice != null && quality != null -> "$voice ($quality)"
            voice != null -> voice
            quality != null -> quality
            !stream.source.isNullOrBlank() -> stream.source
            else -> "Основной поток"
        }
    }

    private fun genericStreamOption(url: String): StreamOption =
        _streamOptions.value.firstOrNull { it.url == url }
            ?: StreamOption(
                voice = "Не указано",
                quality = "Не указано",
                url = url,
                mimeType = inferMimeType(url),
            )

    private fun buildMediaItem(mediaId: String, resolvedUri: String, stream: StreamOption): MediaItem {
        val mime = stream.mimeType ?: inferMimeType(resolvedUri)
        val builder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(resolvedUri)
        if (mime != null) builder.setMimeType(mime)

        val drmUuid = drmUuidForScheme(stream.drmScheme)
        val licenseUrl = stream.drmLicenseUrl?.takeIf(::isAllowedDrmLicenseUrl)
        if (drmUuid != null && licenseUrl != null) {
            builder.setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(drmUuid)
                    .setLicenseUri(licenseUrl)
                    .build()
            )
        }
        return builder.build()
    }


    private fun switchNextMirror(resumePos: Long) {
        if (switchInProgress || recoveryJob?.isActive == true) return
        val generation = switchGeneration
        val candidates = alternativeStreams
        recoveryJob = playbackScope.launch {
            try {
                while (alternativeStreamIndex < candidates.size) {
                    if (generation != switchGeneration) return@launch
                    val candidate = candidates[alternativeStreamIndex++]
                    if (!attemptedStreamUrls.add(candidate.url)) continue
                    _state.value = _state.value.copy(
                        status = PlaybackStatus.BUFFERING,
                        switchState = PlaybackSwitchState.SWITCHING,
                        statusMessage = "Проверяем другое зеркало выбранного варианта...",
                    )
                    MoviaFileLogger.log(
                        "MoviaStreamDebug",
                        "FALLBACK from=" +
                            (_state.value.activeStreamSelection?.activeStreamId ?: "none") +
                            " to=" + candidate.streamId +
                            " quality=" + candidate.quality +
                            " voice=" + candidate.voice
                    )
                    if (performSwitch(candidate, resumePos, generation, isInitial = false)) {
                        return@launch
                    }
                }
                failSwitch(generation, "same_quality_voice_mirrors_exhausted")
            } finally {
                recoveryJob = null
            }
        }
    }

    private fun isStructurallyPlayableUrl(rawUrl: String): Boolean {
        val value = rawUrl.trim()
        if (value.isBlank()) return false
        if (value.startsWith("magnet:?", ignoreCase = true)) {
            val hash = Regex("(?:^|[?&])xt=urn:btih:([^&\\s]+)", RegexOption.IGNORE_CASE)
                .find(value)?.groupValues?.getOrNull(1).orEmpty()
            return Regex("^[0-9A-Fa-f]{40}$").matches(hash) ||
                Regex("^[A-Z2-7]{32}$", RegexOption.IGNORE_CASE).matches(hash)
        }
        return value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("file://", ignoreCase = true)
    }

    private fun parseStreamOptions(arr: org.json.JSONArray?): List<StreamOption> {
        if (arr == null) return emptyList()
        val result = mutableListOf<StreamOption>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val sObj = arr.optJSONObject(i) ?: continue
            val url = sObj.optString("url").takeIf { it.isNotBlank() }
                ?: sObj.optString("playback_url", "")
            val source = sObj.optString("source").takeIf { it.isNotBlank() }
            if (!isStructurallyPlayableUrl(url) || url in seen) continue
            if (url.startsWith("magnet:?", ignoreCase = true) && source.isNullOrBlank()) continue
            if (url.contains("archive.org") || url.contains("themoviedb.org")) continue
            seen.add(url)
            result.add(
                StreamOption(
                    voice = sObj.optString("voice", "Не указано"),
                    quality = sObj.optString("quality", "Не указано"),
                    seeders = sObj.optInt("seeders", 0),
                    url = url,
                    source = source,
                    streamId = sObj.optString("stream_id").takeIf { it.isNotBlank() }
                        ?: sObj.optString("streamId").takeIf { it.isNotBlank() }.orEmpty(),
                    providerItemId = sObj.optString("provider_item_id").takeIf { it.isNotBlank() }
                        ?: sObj.optString("providerItemId").takeIf { it.isNotBlank() },
                    infoHash = sObj.optString("info_hash").takeIf { it.isNotBlank() }
                        ?: sObj.optString("infoHash").takeIf { it.isNotBlank() },
                    fileIndex = sObj.optInt("file_index", -1).takeIf { it >= 0 }
                        ?: sObj.optInt("fileIndex", -1).takeIf { it >= 0 },
                    filePath = sObj.optString("file_path").takeIf { it.isNotBlank() }
                        ?: sObj.optString("filePath").takeIf { it.isNotBlank() },
                    seasonNumber = sObj.optInt("season", -1).takeIf { it > 0 },
                    episodeNumber = sObj.optInt("episode", -1).takeIf { it > 0 },
                    mimeType = sObj.optString("mime_type").takeIf { it.isNotBlank() }
                        ?: sObj.optString("mimeType").takeIf { it.isNotBlank() },
                    drmScheme = sObj.optString("drm_scheme").takeIf { it.isNotBlank() }
                        ?: sObj.optString("drmScheme").takeIf { it.isNotBlank() },
                    drmLicenseUrl = sObj.optString("license_url").takeIf { it.isNotBlank() }
                        ?: sObj.optString("drm_license_url").takeIf { it.isNotBlank() }
                        ?: sObj.optString("drmLicenseUrl").takeIf { it.isNotBlank() },
                )
            )
        }
        return result
    }

    private suspend fun fetchOnDemandStreams(
        mediaId: String,
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean = false,
    ): List<StreamOption> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<StreamOption>()
        try {
            val encodedId = URLEncoder.encode(mediaId, "UTF-8")
            val seasonParam = if (season != null) "?season=$season" else ""
            val episodeParam = if (episode != null) "${if (seasonParam.isEmpty()) "?" else "&"}episode=$episode" else ""
            val refreshParam = "${if (seasonParam.isEmpty() && episodeParam.isEmpty()) "?" else "&"}refresh=${if (forceRefresh) 1 else 0}"
            val url = "http://127.0.0.1:8888/api/movie/$encodedId/stream$seasonParam$episodeParam$refreshParam"
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 30000
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(jsonStr)
                candidates.addAll(parseStreamOptions(obj.optJSONArray("streams")))
            }
            conn.disconnect()
        } catch (_: Exception) {}

        if (candidates.isEmpty()) {
            try {
                val yParam = if (year != null && year > 0) "&year=$year" else ""
                val sParam = if (season != null) "&season=$season" else ""
                val eParam = if (episode != null) "&episode=$episode" else ""
                val cleanTitle = title.substringBefore(" · S").substringBefore(" (").trim()
                val refreshParam = "&refresh=${if (forceRefresh) 1 else 0}"
                val url = "http://127.0.0.1:8888/resolve?title=${URLEncoder.encode(cleanTitle, "UTF-8")}$yParam$sParam$eParam$refreshParam"
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 30000
                }
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val obj = org.json.JSONObject(jsonStr)
                    candidates.addAll(parseStreamOptions(obj.optJSONArray("streams")))
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        return@withContext orderStreams(candidates)
    }

    private fun resolvePlaybackUri(rawUri: String?, title: String): String? {
        if (rawUri.isNullOrBlank()) return null
        val trimmed = rawUri.trim()
        if (trimmed == "https://archive.org" || trimmed == "https://themoviedb.org" || trimmed == "https://archive.org/" || trimmed == "https://themoviedb.org/") {
            return null
        }
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            if (!isStructurallyPlayableUrl(trimmed)) return null
            return try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                val seasonParam = _state.value.seasonNumber?.let { "&season=$it" }.orEmpty()
                val episodeParam = _state.value.episodeNumber?.let { "&episode=$it" }.orEmpty()
                "http://127.0.0.1:8888/stream?magnet=$encoded&format=raw$seasonParam$episodeParam"
            } catch (_: Exception) {
                null
            }
        }
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("file://")) {
            if (trimmed.contains("127.0.0.1:8888/stream?") && !trimmed.contains("format=")) {
                return "$trimmed&format=raw"
            }
            return trimmed
        }
        return null
    }


    private fun choosePreferredStream(
        candidates: List<StreamOption>,
        preferredQuality: String,
        preferredVoice: String,
    ): StreamOption? {
        if (candidates.isEmpty()) return null
        val requestedQuality = preferredQuality.trim()
        val qualityPool = if (
            requestedQuality.isBlank() ||
            requestedQuality.equals("Auto", ignoreCase = true)
        ) {
            candidates
        } else {
            val requestedRank = qualityRank(requestedQuality)
            candidates.filter { qualityRank(it.quality) == requestedRank && requestedRank > 0 }
                .ifEmpty { candidates }
        }
        val requestedVoice = preferredVoice.trim()
        return qualityPool.firstOrNull {
            requestedVoice.isNotBlank() &&
                !requestedVoice.equals("Auto", ignoreCase = true) &&
                it.voice.trim().equals(requestedVoice, ignoreCase = true)
        } ?: qualityPool.firstOrNull()
    }

    private fun requestedSelectionFor(
        stream: StreamOption,
        activeStreamId: String? = null,
        fallbackReason: String? = null,
    ): ActiveStreamSelection {
        val current = _state.value.activeStreamSelection
        return ActiveStreamSelection(
            requestedStreamId = current?.requestedStreamId ?: stream.streamId,
            activeStreamId = activeStreamId,
            requestedQuality = current?.requestedQuality ?: stream.quality,
            requestedVoice = current?.requestedVoice ?: stream.voice,
            activeQuality = activeStreamId?.let { stream.quality },
            activeVoice = activeStreamId?.let { stream.voice },
            source = stream.source,
            fallbackReason = fallbackReason,
        )
    }

    private suspend fun awaitReady(generation: Long, timeoutMs: Long = 45_000L): Boolean {
        if (generation != switchGeneration) return false
        if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_READY) return true
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                var finished = false
                lateinit var listener: Player.Listener

                fun finish(result: Boolean) {
                    if (finished) return
                    finished = true
                    player.removeListener(listener)
                    continuation.resume(result)
                }

                listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> finish(true)
                            Player.STATE_IDLE -> if (player.playerError != null) finish(false)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        finish(false)
                    }
                }
                player.addListener(listener)
                if (generation != switchGeneration) {
                    finish(false)
                } else if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_READY) {
                    finish(true)
                }
                continuation.invokeOnCancellation {
                    player.removeListener(listener)
                }
            }
        } ?: false
    }

    private suspend fun performSwitch(
        candidate: StreamOption,
        resumePositionMs: Long,
        generation: Long,
        isInitial: Boolean,
    ): Boolean {
        if (generation != switchGeneration) return false
        val resolvedUri = resolvePlaybackUri(candidate.url, _state.value.displayTitle) ?: return false
        if (isHtmlOrInvalidUrl(resolvedUri)) return false

        switchInProgress = true
        try {
            val shouldResume = isInitial || player.isPlaying || player.playWhenReady
            val item = buildMediaItem(_state.value.mediaId, resolvedUri, candidate)
            MoviaFileLogger.log(
                "MoviaStreamDebug",
                "SWITCH_START generation=" + generation +
                    " streamId=" + candidate.streamId +
                    " source=" + candidate.source +
                    " quality=" + candidate.quality +
                    " voice=" + candidate.voice +
                    " position=" + resumePositionMs
            )
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(item)
            player.playWhenReady = shouldResume
            player.prepare()

            if (!awaitReady(generation)) {
                MoviaFileLogger.log(
                    "MoviaStreamDebug",
                    "SWITCH_FAILED generation=" + generation + " streamId=" + candidate.streamId + " reason=not_ready",
                    true
                )
                return false
            }
            if (generation != switchGeneration) return false

            val target = resumePositionMs.coerceAtLeast(0L)
            if (target > 0L) {
                Log.d("MoviaStreamDebug", "SWITCH_RESUME seekTo=" + target + " streamId=" + candidate.streamId)
                player.seekTo(target)
            }
            player.playWhenReady = shouldResume
            if (shouldResume) player.play()

            currentStreamOption = candidate
            activeSource = candidate.url
            val selection = requestedSelectionFor(candidate, candidate.streamId)
            _state.value = _state.value.copy(
                status = PlaybackStatus.READY,
                switchState = PlaybackSwitchState.READY,
                statusMessage = null,
                currentPositionMs = target,
                activeStreamSelection = selection,
            )
            MoviaFileLogger.log(
                "MoviaStreamDebug",
                "SWITCH_READY generation=" + generation +
                    " streamId=" + candidate.streamId +
                    " mediaId=" + (player.currentMediaItem?.mediaId ?: "none") +
                    " uri=" + (player.currentMediaItem?.localConfiguration?.uri ?: "none")
            )
            publishSnapshot()
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            MoviaFileLogger.log(
                "MoviaStreamDebug",
                "SWITCH_FAILED generation=" + generation +
                    " streamId=" + candidate.streamId +
                    " reason=" + error.javaClass.simpleName + ":" + error.message,
                true
            )
            return false
        } finally {
            switchInProgress = false
        }
    }

    private fun failSwitch(generation: Long, reason: String) {
        if (generation != switchGeneration) return
        val selection = _state.value.activeStreamSelection
        _state.value = _state.value.copy(
            status = PlaybackStatus.FAILED,
            switchState = PlaybackSwitchState.FAILED,
            statusMessage = "Сейчас не удалось найти доступный источник видео",
            activeStreamSelection = selection?.copy(
                activeStreamId = null,
                activeQuality = null,
                activeVoice = null,
                fallbackReason = reason,
            ),
        )
        player.stop()
        player.clearMediaItems()
        currentStreamOption = null
        activeSource = null
        MoviaFileLogger.log(
            "MoviaStreamDebug",
            "FALLBACK_FAILED generation=" + generation + " reason=" + reason,
            true
        )
        publishSnapshot()
    }


    fun start(
        mediaId: String,
        title: String,
        contentYear: Int? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        sourceUri: String? = null,
        startPositionMs: Long = 0L,
        audioTrackId: String = _state.value.audioTrackId,
        subtitleTrackId: String? = _state.value.subtitleTrackId,
        preferredQuality: String = "Auto",
        preferredVoice: String = "Auto",
        candidateStreams: List<String> = emptyList(),
        candidateStreamOptions: List<StreamOption> = emptyList(),
    ) {
        playbackJob.cancelChildren()
        recoveryJob?.cancel()
        recoveryJob = null
        switchGeneration += 1L
        val generation = switchGeneration
        currentContentYear = contentYear
        refreshAttempts = 0
        attemptedStreamUrls.clear()
        currentStreamOption = null
        activeSource = null

        val validOptions = candidateStreamOptions
            .filter {
                isStructurallyPlayableUrl(it.url) &&
                    !it.url.contains("archive.org") &&
                    !it.url.contains("themoviedb.org")
            }
            .map { it.withCanonicalStreamId(seasonNumber, episodeNumber) }
            .distinctBy { it.streamId }
        val optionsByUrl = validOptions.associateBy { it.url }
        val initialCandidates = (listOfNotNull(sourceUri) + candidateStreams)
            .filter {
                isStructurallyPlayableUrl(it) &&
                    !it.contains("archive.org") &&
                    !it.contains("themoviedb.org")
            }
            .distinct()
            .map { optionsByUrl[it] ?: genericStreamOption(it).withCanonicalStreamId(seasonNumber, episodeNumber) }
            .distinctBy { it.streamId }

        _streamOptions.value = orderStreams(validOptions + initialCandidates, seasonNumber, episodeNumber)
        _state.value = PlaybackState(
            mediaId = mediaId,
            displayTitle = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            currentPositionMs = startPositionMs.coerceAtLeast(0L),
            bufferedPositionMs = startPositionMs.coerceAtLeast(0L),
            totalDurationMs = 0L,
            percentageWatched = 0f,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            isPlaying = false,
            playWhenReady = true,
            status = PlaybackStatus.BUFFERING,
            statusMessage = "Поиск доступных источников...",
            switchState = PlaybackSwitchState.RESOLVING,
        )

        playbackScope.launch {
            val localOnly = initialCandidates.firstOrNull()?.url?.startsWith("file://", ignoreCase = true) == true
            val onDemand = if (localOnly) {
                emptyList()
            } else {
                fetchOnDemandStreams(
                    mediaId, title, contentYear, seasonNumber, episodeNumber, forceRefresh = false
                )
            }
            if (generation != switchGeneration) return@launch

            val allCandidates = orderStreams(
                (initialCandidates + onDemand)
                    .filter { isStructurallyPlayableUrl(it.url) }
                    .map { it.withCanonicalStreamId(seasonNumber, episodeNumber) }
                    .distinctBy { it.streamId },
                seasonNumber,
                episodeNumber,
            )
            _streamOptions.value = allCandidates
            totalCandidatesCount = allCandidates.size

            val primaryStream = choosePreferredStream(allCandidates, preferredQuality, preferredVoice)
            if (primaryStream == null) {
                failSwitch(generation, "no_playable_candidates")
                return@launch
            }

            attemptedStreamUrls.add(primaryStream.url)
            alternativeStreams = allCandidates.filter {
                it.streamId != primaryStream.streamId &&
                    it.sameRequestedVariant(primaryStream, seasonNumber, episodeNumber)
            }
            alternativeStreamIndex = 0
            val requested = requestedSelectionFor(primaryStream)
            _state.value = _state.value.copy(
                status = PlaybackStatus.BUFFERING,
                switchState = PlaybackSwitchState.SWITCHING,
                statusMessage = "Подключение: " + streamDisplayLabel(primaryStream),
                activeStreamSelection = requested,
            )

            if (!performSwitch(primaryStream, startPositionMs, generation, isInitial = true) &&
                generation == switchGeneration
            ) {
                switchNextMirror(startPositionMs)
            }
        }
    }

    fun switchToStream(streamUrl: String, resumePositionMs: Long = -1L) {
        if (streamUrl.isBlank()) return
        switchToStream(genericStreamOption(streamUrl), resumePositionMs)
    }


    fun switchToStream(stream: StreamOption, resumePositionMs: Long = -1L) {
        if (!isStructurallyPlayableUrl(stream.url) || !_state.value.hasMedia) return

        val candidate = stream.withCanonicalStreamId(
            _state.value.seasonNumber,
            _state.value.episodeNumber,
        )
        val position = if (resumePositionMs >= 0L) {
            resumePositionMs
        } else {
            player.currentPosition.coerceAtLeast(0L)
        }
        switchJob?.cancel()
        switchJob = null
        val generation = ++switchGeneration
        recoveryJob?.cancel()
        recoveryJob = null
        attemptedStreamUrls.clear()
        attemptedStreamUrls.add(candidate.url)
        currentStreamOption?.let { attemptedStreamUrls.add(it.url) }

        val knownOptions = orderStreams(
            (_streamOptions.value + candidate)
                .map { it.withCanonicalStreamId(_state.value.seasonNumber, _state.value.episodeNumber) }
                .distinctBy { it.streamId },
            _state.value.seasonNumber,
            _state.value.episodeNumber,
        )
        _streamOptions.value = knownOptions
        alternativeStreams = knownOptions.filter {
            it.streamId != candidate.streamId &&
                it.sameRequestedVariant(candidate, _state.value.seasonNumber, _state.value.episodeNumber)
        }
        alternativeStreamIndex = 0
        totalCandidatesCount = knownOptions.size

        _state.value = _state.value.copy(
            status = PlaybackStatus.BUFFERING,
            switchState = PlaybackSwitchState.SWITCHING,
            statusMessage = "Переключение потока: " + streamDisplayLabel(candidate),
            activeStreamSelection = ActiveStreamSelection(
                requestedStreamId = candidate.streamId,
                activeStreamId = _state.value.activeStreamSelection?.activeStreamId,
                requestedQuality = candidate.quality,
                requestedVoice = candidate.voice,
                activeQuality = _state.value.activeStreamSelection?.activeQuality,
                activeVoice = _state.value.activeStreamSelection?.activeVoice,
                source = candidate.source,
                fallbackReason = null,
            ),
            currentPositionMs = position,
        )
        val previousSelection = _state.value.activeStreamSelection
        MoviaFileLogger.log(
            "MoviaStreamDebug",
            "USER_SELECT old=" + (previousSelection?.activeStreamId ?: "none") +
                " new=" + candidate.streamId +
                " quality=" + candidate.quality +
                " voice=" + candidate.voice +
                " position=" + position
        )

        switchJob = playbackScope.launch {
            if (generation != switchGeneration) return@launch
            if (!performSwitch(candidate, position, generation, isInitial = false) &&
                generation == switchGeneration
            ) {
                switchNextMirror(position)
            }
        }.also { job ->
            job.invokeOnCompletion {
                if (switchJob === job) switchJob = null
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        publishSnapshot()
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0L } ?: _state.value.totalDurationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
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
        Log.d("MoviaStreamDebug", "SEEK_REQUEST target=" + target)
        Log.d("MoviaStreamDebug", "seekTo target=$target current=${player.currentPosition.coerceAtLeast(0L)} duration=$duration")
        player.seekTo(target)
        publishSnapshot()
    }

    fun setTrackPreferences(audioTrackId: String, subtitleTrackId: String?) {
        _state.value = _state.value.copy(
            audioTrackId = audioTrackId,
            subtitleTrackId = subtitleTrackId,
            lastUpdatedTimestamp = System.currentTimeMillis(),
        )
    }

    fun stopAndClear() {
        // A playback exit must terminate the entire attempt graph, not only ExoPlayer.
        // Otherwise a delayed watchdog/on-demand resolver can wake up and start a mirror
        // after the UI has already left the player.
        watchdogJob?.cancel()
        watchdogJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        switchJob?.cancel()
        switchJob = null
        switchGeneration += 1L
        switchInProgress = false
        playbackJob.cancelChildren()

        player.playWhenReady = false
        player.pause()
        player.stop()
        player.clearMediaItems()

        activeSource = null
        alternativeStreams = emptyList()
        alternativeStreamIndex = 0
        totalCandidatesCount = 0
        attemptedStreamUrls.clear()
        refreshAttempts = 0
        currentContentYear = null
        currentStreamOption = null
        _streamOptions.value = emptyList()
        _state.value = PlaybackState()
    }

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
        _state.value = current.copy(
            currentPositionMs = position,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            totalDurationMs = duration.coerceAtLeast(0L),
            percentageWatched = percentage,
            lastUpdatedTimestamp = System.currentTimeMillis(),
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            status = when {
                current.switchState == PlaybackSwitchState.FAILED -> PlaybackStatus.FAILED
                current.switchState == PlaybackSwitchState.RESOLVING ||
                    current.switchState == PlaybackSwitchState.SWITCHING ||
                    current.switchState == PlaybackSwitchState.BUFFERING -> PlaybackStatus.BUFFERING
                else -> when (player.playbackState) {
                    Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                    Player.STATE_READY -> PlaybackStatus.READY
                    Player.STATE_ENDED -> PlaybackStatus.ENDED
                    else -> PlaybackStatus.IDLE
                }
            },
        )
    }

    fun release() {
        MoviaPlaybackRegistry.clearIfCurrent(this)
        scope.cancel()
        mediaSession.release()
        player.release()
        activeSource = null
        _streamOptions.value = emptyList()
        _state.value = PlaybackState()
    }
}
