package app.movia.android.ui.player

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import app.movia.android.domain.model.ActiveStreamSelection
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.PlaybackState
import app.movia.android.domain.model.PlaybackStatus
import app.movia.android.domain.model.PlaybackSwitchState
import app.movia.android.domain.model.StreamOption
import app.movia.android.domain.model.withCanonicalStreamId
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
import kotlinx.coroutines.withContext
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
    var current: PlaybackSession? = null
        internal set

    @Synchronized
    fun obtain(context: Context): PlaybackSession =
        current ?: PlaybackSession(context.applicationContext)
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

class DynamicHeaderDataSourceFactory(
    private val context: Context,
    private val userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
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

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(15_000)
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
        setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeSource: String? = null
    private var alternativeStreams: List<String> = emptyList()
    private var alternativeStreamIndex: Int = 0
    private var totalCandidatesCount: Int = 0
    private var watchdogJob: Job? = null
    private var playbackGeneration: Long = 0L

    @Synchronized
    private fun nextPlaybackGeneration(): Long {
        playbackGeneration += 1L
        return playbackGeneration
    }

    @Synchronized
    private fun isCurrentGeneration(generation: Long): Boolean =
        generation == playbackGeneration

    private fun resetAndStartWatchdog(resumePos: Long, sourceIndex: Int, generation: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(35000L)
            if (
                isActive &&
                isCurrentGeneration(generation) &&
                player.playbackState != Player.STATE_READY &&
                !player.isPlaying
            ) {
                Log.w("MoviaStreamDebug", "Stream $sourceIndex timed out (35s), switching to next mirror...")
                player.stop()
                player.clearMediaItems()
                switchNextMirror(resumePos, generation)
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
        }

    val mediaSession: MediaSession = MediaSession.Builder(context.applicationContext, player).build()

    init {
        MoviaPlaybackRegistry.current = this
        MoviaFileLogger.init(context.applicationContext)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) watchdogJob?.cancel()
                publishSnapshot()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) watchdogJob?.cancel()
                publishSnapshot()
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publishSnapshot()
            override fun onPlayerError(error: PlaybackException) {
                watchdogJob?.cancel()
                val resumePos = _state.value.currentPositionMs
                Log.e("MoviaStreamDebug", "Ошибка воспроизведения ExoPlayer (${error.errorCodeName}): ${error.message} для тайтла: '${_state.value.displayTitle}'")
                _state.value = _state.value.copy(
                    status = PlaybackStatus.BUFFERING,
                    statusMessage = "Поиск альтернативного зеркала..."
                )
                switchNextMirror(resumePos)
            }
        })
        scope.launch {
            while (isActive) {
                if (_state.value.hasMedia) publishSnapshot()
                delay(250L)
            }
        }
    }

    private suspend fun isHtmlOrInvalidUrl(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (!rawUrl.startsWith("http://", ignoreCase = true) && !rawUrl.startsWith("https://", ignoreCase = true)) {
            Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: Non-HTTP, allowing")
            return@withContext false
        }
        // Local companion streamer endpoints (127.0.0.1 / localhost) are always trusted
        if (rawUrl.contains("127.0.0.1") || rawUrl.contains("localhost")) {
            Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: Local companion streamer, allowed immediately")
            return@withContext false
        }
        // HLS playlist manifests (.m3u8) should not be probed with byte Range requests, let ExoPlayer handle natively
        val lowerUrl = rawUrl.substringBefore("?").lowercase()
        if (lowerUrl.endsWith(".m3u8") || lowerUrl.contains("m3u8") || lowerUrl.contains("/hls/")) {
            Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: HLS manifest stream, allowed directly")
            return@withContext false
        }
        try {
            val url = java.net.URL(rawUrl)
            val connection = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = true
                setRequestProperty("Range", "bytes=0-512")
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                setRequestProperty("Accept", "*/*")
            }
            connection.connect()
            val code = connection.responseCode
            val contentType = connection.contentType?.lowercase().orEmpty()
            if (code >= 400 || contentType.contains("text/html") || contentType.contains("text/plain")) {
                connection.disconnect()
                Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: Rejected (Code: $code, ContentType: $contentType)")
                return@withContext true
            }
            // Sample first 256 bytes to inspect HTML doctype / script tags
            val sample = ByteArray(256)
            val readBytes = try {
                connection.inputStream.use { it.read(sample) }
            } catch (_: Exception) {
                0
            }
            connection.disconnect()
            if (readBytes > 0) {
                val headStr = String(sample, 0, readBytes, Charsets.UTF_8).lowercase()
                if (headStr.contains("<!doctype") || headStr.contains("<html") || headStr.contains("<body") || headStr.contains("<script")) {
                    Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: Rejected (HTML signatures detected in payload)")
                    return@withContext true
                }
            }
            Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: Valid media stream ($contentType)")
        } catch (e: Exception) {
            // On network timeout or connection delay, DO NOT reject the stream; let ExoPlayer try directly
            Log.d("MoviaStreamDebug", "Testing URL: $rawUrl | Result: Preflight timeout/error (${e.message}), passing to ExoPlayer")
            return@withContext false
        }
        return@withContext false
    }

    private fun extractVoiceLabel(rawUri: String): String {
        val decoded = try { java.net.URLDecoder.decode(rawUri, "UTF-8") } catch (_: Exception) { rawUri }
        return when {
            decoded.contains("Дубляж", ignoreCase = true) -> "Дубляж (1080p)"
            decoded.contains("LostFilm", ignoreCase = true) -> "LostFilm (1080p)"
            decoded.contains("HDRezka", ignoreCase = true) -> "HDRezka (1080p)"
            decoded.contains("Red Head Sound", ignoreCase = true) -> "Red Head Sound (720p)"
            decoded.contains("Kodik", ignoreCase = true) -> "Kodik (1080p)"
            decoded.contains("OpenHLS", ignoreCase = true) -> "Официальный HLS (1080p)"
            decoded.contains("1080", ignoreCase = true) -> "FullHD 1080p"
            decoded.contains("720", ignoreCase = true) -> "HD 720p"
            decoded.contains("4k", ignoreCase = true) || decoded.contains("2160", ignoreCase = true) -> "Ultra HD 4K"
            else -> "Основной поток (1080p)"
        }
    }

    private fun switchNextMirror(resumePos: Long, generation: Long = playbackGeneration) {
        scope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            while (isCurrentGeneration(generation) && alternativeStreamIndex < alternativeStreams.size) {
                val currentIndex = alternativeStreamIndex + 1
                val nextUri = alternativeStreams[alternativeStreamIndex++]
                val voiceLabel = extractVoiceLabel(nextUri)
                _state.value = _state.value.copy(
                    statusMessage = "Запускаем вариант озвучки: $currentIndex из $totalCandidatesCount\n$voiceLabel"
                )
                val resolvedUri = resolvePlaybackUri(nextUri, _state.value.displayTitle)
                if (!resolvedUri.isNullOrBlank()) {
                    if (!isCurrentGeneration(generation)) return@launch
                    if (isHtmlOrInvalidUrl(resolvedUri)) {
                        Log.e("MoviaStreamDebug", "HTML-заглушка пропущена в зеркале: $resolvedUri")
                        continue
                    }
                    activeSource = nextUri
                    val selected = _streamOptions.value.firstOrNull { it.url == nextUri }
                    _state.value = _state.value.copy(
                        switchState = PlaybackSwitchState.RESOLVING,
                        activeStreamSelection = (_state.value.activeStreamSelection
                            ?: ActiveStreamSelection()).copy(
                            activeStreamId = null,
                            activeQuality = null,
                            activeVoice = null,
                            source = selected?.source ?: nextUri,
                            fallbackReason = null,
                        ),
                    )
                    val mime = inferMimeType(resolvedUri)
                    Log.e("MoviaStreamDebug", "Попытка запуска альтернативного URL: $resolvedUri для тайтла: '${_state.value.displayTitle}'")
                    val nextItem = MediaItem.Builder()
                        .setMediaId(_state.value.mediaId)
                        .setUri(resolvedUri)
                        .apply {
                            if (mime != null) setMimeType(mime)
                        }
                        .build()
                    player.setMediaItem(nextItem)
                    player.prepare()
                    if (resumePos > 0L) {
                        player.seekTo(resumePos)
                    }
                    player.playWhenReady = true
                    player.play()
                    resetAndStartWatchdog(resumePos, currentIndex, generation)
                    publishSnapshot()
                    return@launch
                }
            }

            if (!isCurrentGeneration(generation)) return@launch
            Log.e("MoviaStreamDebug", "Все доступные источники для '${_state.value.displayTitle}' исчерпаны или недоступны.")
            _state.value = _state.value.copy(
                status = PlaybackStatus.IDLE,
                statusMessage = "Источники для данного тайтла временно недоступны"
            )
            player.stop()
            player.clearMediaItems()
            activeSource = null
            publishSnapshot()
        }
    }

    private suspend fun fetchOnDemandStreams(mediaId: String, season: Int?, episode: Int?): List<String> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<String>()
        try {
            val encodedId = URLEncoder.encode(mediaId, "UTF-8")
            val seasonParam = if (season != null) "season=$season" else ""
            val episodeParam = if (episode != null) "episode=$episode" else ""
            val query = listOf(seasonParam, episodeParam)
                .filter { it.isNotBlank() }
                .joinToString("&")
            val url = "http://127.0.0.1:8888/api/movie/$encodedId/stream" +
                if (query.isNotBlank()) "?$query" else ""
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 4000
            }
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = org.json.JSONObject(jsonStr)
                val arr = obj.optJSONArray("streams")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val sObj = arr.optJSONObject(i) ?: continue
                        val u = sObj.optString("url").takeIf { it.isNotBlank() } ?: sObj.optString("playback_url", "")
                        if (u.isNotBlank() && !u.contains("archive.org") && !u.contains("themoviedb.org")) {
                            candidates.add(u)
                        }
                    }
                }
            }
            conn.disconnect()
        } catch (_: Exception) {}

        return@withContext candidates
    }

    private fun resolvePlaybackUri(rawUri: String?, title: String): String? {
        if (rawUri.isNullOrBlank()) return null
        val trimmed = rawUri.trim()
        if (trimmed == "https://archive.org" || trimmed == "https://themoviedb.org" || trimmed == "https://archive.org/" || trimmed == "https://themoviedb.org/") {
            return null
        }
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return try {
                val encoded = URLEncoder.encode(trimmed, "UTF-8")
                "http://127.0.0.1:8888/stream?magnet=$encoded&format=raw"
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
        val initialCandidates = (listOfNotNull(sourceUri) + candidateStreams)
            .filter { it.isNotBlank() && !it.contains("archive.org") && !it.contains("themoviedb.org") }
            .distinct()

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
        )

        scope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            var allCandidates = initialCandidates
            if (allCandidates.isEmpty()) {
                val onDemand = fetchOnDemandStreams(mediaId, seasonNumber, episodeNumber)
                if (!isCurrentGeneration(generation)) return@launch
                allCandidates = onDemand.filter { it.isNotBlank() }.distinct()
            }

            if (!isCurrentGeneration(generation)) return@launch
            val optionCandidates = (
                candidateStreamOptions.filter { it.url.isNotBlank() } +
                    allCandidates.map { uri ->
                        StreamOption(
                            voice = "Не указано",
                            quality = "Не указано",
                            url = uri,
                            source = if (uri.startsWith("magnet:", ignoreCase = true)) "torrent_p2p" else "direct",
                        )
                    }
                )
                .distinctBy { it.url.trim() }
                .map { it.withCanonicalStreamId(seasonNumber, episodeNumber) }
            _streamOptions.value = optionCandidates

            val requestedId = preferredStreamId?.trim()?.takeIf { it.isNotBlank() }
            val requestedQuality = preferredQuality?.trim()?.takeIf { it.isNotBlank() }
            val requestedVoice = preferredVoice?.trim()?.takeIf { it.isNotBlank() }
            val preferred = requestedId?.let { id ->
                optionCandidates.firstOrNull { it.streamId == id }
            } ?: optionCandidates.firstOrNull { option ->
                (requestedQuality == null || option.quality.equals(requestedQuality, ignoreCase = true)) &&
                    (requestedVoice == null || option.voice.equals(requestedVoice, ignoreCase = true))
            } ?: optionCandidates.firstOrNull()
            val primarySource = preferred?.url ?: allCandidates.firstOrNull()
            totalCandidatesCount = allCandidates.size

            _state.value = _state.value.copy(
                switchState = PlaybackSwitchState.RESOLVING,
                activeStreamSelection = ActiveStreamSelection(
                    requestedStreamId = preferred?.streamId ?: requestedId,
                    requestedQuality = requestedQuality,
                    requestedVoice = requestedVoice,
                    source = preferred?.source ?: primarySource,
                ),
            )

            if (primarySource == null) {
                Log.e("MoviaStreamDebug", "Запуск тайтла: '$title' — нет доступных источников.")
                activeSource = null
                alternativeStreams = emptyList()
                alternativeStreamIndex = 0
                _state.value = _state.value.copy(
                    status = PlaybackStatus.IDLE,
                    switchState = PlaybackSwitchState.FAILED,
                    activeStreamSelection = _state.value.activeStreamSelection?.copy(
                        fallbackReason = "NO_SOURCE",
                    ),
                    statusMessage = "Источники для данного тайтла временно недоступны",
                )
                player.stop()
                player.clearMediaItems()
                publishSnapshot()
                return@launch
            }

            val sameMedia = _state.value.displayTitle == title && activeSource == primarySource && player.mediaItemCount > 0
            activeSource = primarySource
            alternativeStreams = allCandidates.drop(1)
            alternativeStreamIndex = 0

            val voiceLabel = extractVoiceLabel(primarySource)
            val initialStatusMessage = if (totalCandidatesCount > 1) {
                "Запускаем вариант озвучки: 1 из $totalCandidatesCount\n$voiceLabel"
            } else {
                "Подключение к потоку..."
            }

            _state.value = _state.value.copy(
                statusMessage = initialStatusMessage
            )

            if (!sameMedia) {
                val resolvedUri = resolvePlaybackUri(primarySource, title)
                if (!resolvedUri.isNullOrBlank()) {
                    if (isHtmlOrInvalidUrl(resolvedUri)) {
                        Log.e("MoviaStreamDebug", "HTML-заглушка пропущена: $resolvedUri")
                        switchNextMirror(startPositionMs, generation)
                        return@launch
                    }
                    val mime = inferMimeType(resolvedUri)
                    Log.e("MoviaStreamDebug", "Попытка запуска URL: $resolvedUri для тайтла: '$title' (StreamType: $mime)")
                    val item = MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setUri(resolvedUri)
                        .apply {
                            if (mime != null) setMimeType(mime)
                        }
                        .build()
                    player.setMediaItem(item)
                    player.prepare()
                    if (startPositionMs > 0L) player.seekTo(startPositionMs)
                    player.playWhenReady = true
                    player.play()
                    resetAndStartWatchdog(startPositionMs, 1, generation)
                    publishSnapshot()
                } else {
                    Log.e("MoviaStreamDebug", "Ошибка: пустой или невалидный URL для тайтла: '$title' (primarySource: $primarySource)")
                    switchNextMirror(startPositionMs, generation)
                }
            } else {
                publishSnapshot()
            }
        }
    }

    fun switchToStream(streamUrl: String, resumePositionMs: Long = -1L) {
        if (streamUrl.isBlank()) return
        val generation = nextPlaybackGeneration()
        watchdogJob?.cancel()
        val resolvedUri = resolvePlaybackUri(streamUrl, _state.value.displayTitle.orEmpty()) ?: return
        val position = if (resumePositionMs >= 0L) resumePositionMs else player.currentPosition.coerceAtLeast(0L)
        activeSource = streamUrl
        val selected = _streamOptions.value.firstOrNull { it.url == streamUrl }
        _state.value = _state.value.copy(
            switchState = PlaybackSwitchState.RESOLVING,
            activeStreamSelection = (_state.value.activeStreamSelection
                ?: ActiveStreamSelection()).copy(
                requestedStreamId = selected?.streamId,
                requestedQuality = selected?.quality,
                requestedVoice = selected?.voice,
                activeStreamId = null,
                activeQuality = null,
                activeVoice = null,
                source = selected?.source ?: streamUrl,
                fallbackReason = null,
            ),
        )
        scope.launch {
            if (!isCurrentGeneration(generation)) return@launch
            if (isHtmlOrInvalidUrl(resolvedUri)) {
                if (!isCurrentGeneration(generation)) return@launch
                Log.e("MoviaStreamDebug", "HTML-заглушка пропущена в switchToStream: $resolvedUri")
                return@launch
            }
            val mime = inferMimeType(resolvedUri)
            val mediaItem = MediaItem.Builder()
                .setMediaId(_state.value.mediaId)
                .setUri(resolvedUri)
                .apply {
                    if (mime != null) setMimeType(mime)
                }
                .build()
            player.stop()
            player.clearMediaItems()
            player.setMediaItem(mediaItem)
            player.prepare()
            if (position > 0L) {
                player.seekTo(position)
            }
            player.playWhenReady = true
            player.play()
            _state.value = _state.value.copy(
                status = PlaybackStatus.BUFFERING,
                switchState = PlaybackSwitchState.BUFFERING,
                statusMessage = "Переключение потока...",
                currentPositionMs = position,
            )
            Log.d("MoviaStreamDebug", "switchToStream: switched to $resolvedUri at pos=$position ms")
            publishSnapshot()
        }
    }

    fun switchToStream(stream: app.movia.android.domain.model.StreamOption, resumePositionMs: Long = -1L) {
        switchToStream(stream.url, resumePositionMs)
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
        player.stop()
        player.clearMediaItems()
        activeSource = null
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
        val status = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
            Player.STATE_READY -> PlaybackStatus.READY
            Player.STATE_ENDED -> PlaybackStatus.ENDED
            else -> PlaybackStatus.IDLE
        }
        val selected = _streamOptions.value.firstOrNull { it.url == activeSource }
        val selection = if (status == PlaybackStatus.READY && selected != null) {
            (current.activeStreamSelection ?: ActiveStreamSelection()).copy(
                activeStreamId = selected.streamId,
                activeQuality = selected.quality,
                activeVoice = selected.voice,
                source = selected.source ?: selected.url,
                fallbackReason = null,
            )
        } else {
            current.activeStreamSelection
        }
        val switchState = when {
            status == PlaybackStatus.READY -> PlaybackSwitchState.READY
            status == PlaybackStatus.BUFFERING &&
                current.switchState != PlaybackSwitchState.FAILED -> PlaybackSwitchState.BUFFERING
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
        if (MoviaPlaybackRegistry.current === this) {
            MoviaPlaybackRegistry.current = null
        }
        scope.cancel()
        mediaSession.release()
        player.release()
        activeSource = null
        _streamOptions.value = emptyList()
        _state.value = PlaybackState()
    }
}
