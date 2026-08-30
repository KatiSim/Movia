@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.movia.android.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.movia.android.R
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.ContentType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaGlowLuminescence
import app.movia.android.ui.theme.MoviaOnBrandAmber

private val PLAYER_CENTER_CONTROL_SIZE = 66.1.dp
private val PLAYER_CENTER_ICON_SIZE = 33.1.dp
private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private data class SelectableTrackOption(
    val label: String,
    val override: TrackSelectionOverride,
    val selected: Boolean,
)

private data class SubtitleTrackOption(
    val label: String,
    val override: TrackSelectionOverride,
    val selected: Boolean,
)

private enum class PlayerSettingsPicker {
    AUDIO,
    SUBTITLES,
    SPEED,
    QUALITY,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    session: PlaybackSession,
    title: String,
    onMinimize: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    preferredAudio: String = "Auto",
    preferredQuality: String = "Auto",
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    subtitlesEnabled: Boolean = false,
    autoNextEnabled: Boolean = true,
    persistentSeekButtons: Boolean = false,
    onSubtitlesChanged: (Boolean) -> Unit,
    onSubtitleTrackIdChanged: (String?) -> Unit,
    onNextEpisode: () -> Unit,
    onSelectEpisode: (Int, Int) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val rootView = LocalView.current
    val gestureScope = rememberCoroutineScope()
    val playback by session.state.collectAsState()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val baseTitle = remember(title) { title.substringBefore(" · S").substringBefore(" · E") }
    val mediaContent = remember(baseTitle) { DemoCatalogRepository.findByTitle(baseTitle) }
    val isSeries = mediaContent?.type == ContentType.SERIES
    val currentSeason = playback.seasonNumber ?: 1
    val currentEpisode = playback.episodeNumber
    val player = session.player
    var videoAspectRatio by remember { mutableFloatStateOf(moviaVideoAspectRatio(player.videoSize)) }
    val inPictureInPicture = MoviaPiPState.isInPictureInPicture

    // The player owns an immersive fullscreen window while it is visible.
    // Restore system bars when the composable leaves so the rest of Movia behaves normally.
    DisposableEffect(activity, inPictureInPicture) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (!inPictureInPicture) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    var sourceRectHint by remember { mutableStateOf<Rect?>(null) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var settingsOpen by remember { mutableStateOf(false) }
    var settingsPicker by remember { mutableStateOf<PlayerSettingsPicker?>(null) }
    var episodesScreenOpen by remember { mutableStateOf(false) }
    var episodesScreenSeason by remember(title) { mutableIntStateOf(currentSeason) }
    var audioTracks by remember { mutableStateOf(buildAudioTrackOptions(player.currentTracks)) }
    var videoQualityTracks by remember { mutableStateOf(buildVideoQualityTrackOptions(player.currentTracks)) }
    var subtitleTracks by remember { mutableStateOf(buildSubtitleTrackOptions(player.currentTracks)) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember(title) { mutableLongStateOf(playback.currentPositionMs) }
    val positionMs = if (scrubbing) scrubPositionMs else playback.currentPositionMs
    val bufferedPositionMs = playback.bufferedPositionMs
    val durationMs = playback.totalDurationMs
    var scrubPreviewFrame by remember(title) { mutableStateOf<ImageBitmap?>(null) }
    var seekFeedbackDirection by remember { mutableIntStateOf(0) }
    var seekFeedbackTick by remember { mutableIntStateOf(0) }
    var seekFeedbackSeconds by remember { mutableIntStateOf(10) }
    var pendingCenterTapJob by remember { mutableStateOf<Job?>(null) }
    var gestureFeedbackLabel by remember { mutableStateOf<String?>(null) }
    var gestureFeedbackPercent by remember { mutableIntStateOf(0) }
    var gestureFeedbackTick by remember { mutableIntStateOf(0) }

    val resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    val effectiveAudioPreference = preferredAudio.takeIf { value ->
        value == "Auto" || audioTracks.any { it.label == value }
    } ?: "Auto"
    val effectiveQualityPreference = preferredQuality.takeIf { value ->
        value == "Auto" || videoQualityTracks.any { it.label == value }
    } ?: "Auto"
    val selectedAudioLabel = audioTracks.firstOrNull { it.selected }?.label
    val selectedQualityLabel = videoQualityTracks.firstOrNull { it.selected }?.label
    val audioAutoLabel = selectedAudioLabel?.let { "Авто · $it" } ?: "Авто"
    val qualityAutoLabel = selectedQualityLabel?.let { "Авто · $it" } ?: "Авто"

    fun showControls() {
        if (!inPictureInPicture) {
            controlsVisible = true
            interactionTick++
        }
    }

    fun seekBy(deltaMs: Long, stackedSeconds: Int = 10) {
        val maxPosition = max(0L, player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val target = (session.state.value.currentPositionMs + deltaMs).coerceIn(0L, maxPosition)
        session.seekTo(target)
        scrubPositionMs = target
        seekFeedbackDirection = if (deltaMs < 0L) -1 else 1
        seekFeedbackSeconds = stackedSeconds
        seekFeedbackTick++
        rootView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        showControls()
    }

    fun selectAudio(value: String) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (value != "Auto") {
            audioTracks.firstOrNull { it.label == value }?.let { builder.setOverrideForType(it.override) }
        }
        player.trackSelectionParameters = builder.build()
        onAudioSelected(value)
        showControls()
    }

    fun selectQuality(value: String) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (value != "Auto") {
            videoQualityTracks.firstOrNull { it.label == value }?.let { builder.setOverrideForType(it.override) }
        }
        player.trackSelectionParameters = builder.build()
        onQualitySelected(value)
        showControls()
    }

    fun updateSourceRect(view: PlayerView) {
        val rect = Rect()
        val sourceView = view.videoSurfaceView ?: view
        if (sourceView.getGlobalVisibleRect(rect) && !rect.isEmpty) {
            sourceRectHint = rect
        }
    }

    fun enterPictureInPicture() {
        activity?.let { host ->
            controlsVisible = false
            settingsOpen = false
            settingsPicker = null
            val params = buildMoviaPictureInPictureParams(
                context = context,
                sourceRectHint = sourceRectHint,
                isPlaying = playback.isPlaying,
                title = displayPlayerTitle(title),
                autoEnter = playback.playWhenReady,
            )
            host.setPictureInPictureParams(params)
            host.enterPictureInPictureMode(params)
        }
    }

    LaunchedEffect(inPictureInPicture) {
        if (inPictureInPicture) {
            controlsVisible = false
            settingsOpen = false
            settingsPicker = null
            episodesScreenOpen = false
        }
    }

    LaunchedEffect(currentSeason) {
        episodesScreenSeason = currentSeason
    }

    LaunchedEffect(activity, sourceRectHint, playback.isPlaying, playback.playWhenReady, title) {
        activity?.setPictureInPictureParams(
            buildMoviaPictureInPictureParams(
                context = context,
                sourceRectHint = sourceRectHint,
                isPlaying = playback.isPlaying,
                title = displayPlayerTitle(title),
                autoEnter = playback.playWhenReady,
            ),
        )
    }

    LaunchedEffect(speed) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    LaunchedEffect(subtitlesEnabled) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
    }

    LaunchedEffect(preferredAudio, audioTracks) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        audioTracks.firstOrNull { preferredAudio != "Auto" && it.label == preferredAudio }?.let {
            builder.setOverrideForType(it.override)
        }
        player.trackSelectionParameters = builder.build()
    }

    LaunchedEffect(preferredQuality, videoQualityTracks) {
        val builder = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        videoQualityTracks.firstOrNull { preferredQuality != "Auto" && it.label == preferredQuality }?.let {
            builder.setOverrideForType(it.override)
        }
        player.trackSelectionParameters = builder.build()
    }

    LaunchedEffect(scrubbing, scrubPositionMs / 5_000L, session.activeSourceUri) {
        if (!scrubbing) {
            scrubPreviewFrame = null
        } else {
            delay(120L)
            scrubPreviewFrame = loadScrubPreviewFrame(
                context = context.applicationContext,
                sourceUri = session.activeSourceUri,
                positionMs = scrubPositionMs,
            )
        }
    }

    LaunchedEffect(
        controlsVisible,
        interactionTick,
        settingsOpen,
        episodesScreenOpen,
        scrubbing,
        playback.isPlaying,
        playback.playWhenReady,
        playback.status,
        playbackError,
    ) {
        val activePlayback = playback.isPlaying ||
            (playback.playWhenReady && playback.status == app.movia.android.domain.model.PlaybackStatus.BUFFERING)
        if (controlsVisible && activePlayback && playbackError == null &&
            !settingsOpen && !episodesScreenOpen && !scrubbing
        ) {
            delay(3_500L)
            controlsVisible = false
        } else if (!activePlayback || playbackError != null) {
            controlsVisible = true
        }
    }

    LaunchedEffect(player, title, autoNextEnabled) {
        if (!autoNextEnabled) return@LaunchedEffect
        while (true) {
            delay(500L)
            if (player.playbackState == Player.STATE_ENDED) {
                onNextEpisode()
                break
            }
        }
    }

    DisposableEffect(player, title) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = error.errorCodeName
                showControls()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                showControls()
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = buildAudioTrackOptions(tracks)
                videoQualityTracks = buildVideoQualityTrackOptions(tracks)
                subtitleTracks = buildSubtitleTrackOptions(tracks)
                showControls()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspectRatio = moviaVideoAspectRatio(videoSize)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(seekFeedbackTick) {
        if (seekFeedbackTick > 0) {
            delay(650L)
            seekFeedbackDirection = 0
        }
    }

    LaunchedEffect(gestureFeedbackTick) {
        if (gestureFeedbackTick > 0) {
            delay(850L)
            gestureFeedbackLabel = null
        }
    }

    val selectedSubtitleLabel = subtitleTracks.firstOrNull { it.selected }?.label
    val audioSummary = if (effectiveAudioPreference == "Auto") audioAutoLabel else effectiveAudioPreference
    val qualitySummary = if (effectiveQualityPreference == "Auto") qualityAutoLabel else effectiveQualityPreference
    val speedSummary = "${formatSpeed(speed)}×"
    val subtitleSummary = when {
        subtitleTracks.isEmpty() -> "Нет"
        !subtitlesEnabled -> "Нет"
        playback.subtitleTrackId == "Auto" -> "Авто"
        !playback.subtitleTrackId.isNullOrBlank() -> playback.subtitleTrackId.orEmpty()
        selectedSubtitleLabel != null -> selectedSubtitleLabel
        else -> "Авто"
    }
    val subtitlePickerSelected = when {
        subtitleTracks.isEmpty() || !subtitlesEnabled -> "Нет"
        playback.subtitleTrackId == "Auto" -> "Авто"
        !playback.subtitleTrackId.isNullOrBlank() -> playback.subtitleTrackId.orEmpty()
        selectedSubtitleLabel != null -> selectedSubtitleLabel
        else -> "Авто"
    }

    fun selectSubtitleTrack(option: SubtitleTrackOption?) {
        val builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (option == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            onSubtitlesChanged(false)
            onSubtitleTrackIdChanged(null)
        } else {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(option.override)
            onSubtitlesChanged(true)
            onSubtitleTrackIdChanged(option.label)
        }
        player.trackSelectionParameters = builder.build()
        settingsPicker = null
    }

    fun selectAutomaticSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        onSubtitlesChanged(true)
        onSubtitleTrackIdChanged("Auto")
        settingsPicker = null
    }

    val leavePlayer: () -> Unit = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }
    val exitFullscreen: () -> Unit = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        showControls()
    }
    val closePlaybackSettings: () -> Unit = {
        settingsPicker = null
        settingsOpen = false
        showControls()
    }
    val handlePlayerBack: () -> Unit = {
        when {
            settingsOpen && settingsPicker != null -> settingsPicker = null
            settingsOpen -> closePlaybackSettings()
            episodesScreenOpen -> {
                episodesScreenOpen = false
                showControls()
            }
            isLandscape -> exitFullscreen()
            else -> leavePlayer()
        }
    }

    BackHandler(onBack = handlePlayerBack)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background),
    ) {
        val landscapeVideoHorizontalInset = if (isLandscape && videoAspectRatio > 0f && maxHeight > 0.dp) {
            val fittedVideoWidth = (maxHeight * videoAspectRatio).coerceAtMost(maxWidth)
            ((maxWidth - fittedVideoWidth) / 2f).coerceAtLeast(0.dp)
        } else {
            0.dp
        }
        // Static Movia cinema backdrop. It never mirrors the movie frame. A broad,
        // soft gold-luminescent light source sits behind the central video and fades
        // continuously into the deep dark theme toward every edge.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to scheme.background,
                            0.24f to scheme.background,
                            0.50f to scheme.surface,
                            0.76f to scheme.background,
                            1.00f to scheme.background,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to scheme.onSurface.copy(alpha = 0.14f),
                            0.16f to MoviaGlowLuminescence.copy(alpha = 0.13f),
                            0.38f to MoviaGlowLuminescence.copy(alpha = 0.10f),
                            0.62f to MoviaGlowLuminescence.copy(alpha = 0.045f),
                            0.82f to MoviaGlowLuminescence.copy(alpha = 0.018f),
                            1.00f to Color.Transparent,
                        ),
                        center = Offset.Unspecified,
                        radius = 1250f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.00f to scheme.onSurface.copy(alpha = 0.026f),
                            0.28f to MoviaGlowLuminescence.copy(alpha = 0.020f),
                            0.64f to Color.Transparent,
                            1.00f to Color.Transparent,
                        ),
                        center = Offset.Unspecified,
                        radius = 760f,
                    ),
                ),
        )

        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext)
                    .inflate(
                        R.layout.view_movia_player,
                        FrameLayout(viewContext),
                        false,
                    ) as PlayerView).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setKeepContentOnPlayerReset(true)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    this.player = player
                    addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                        updateSourceRect(view as PlayerView)
                    }
                    post { updateSourceRect(this) }
                }
            },
            update = {
                it.player = player
                it.resizeMode = resizeMode
                it.post { updateSourceRect(it) }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (inPictureInPicture && durationMs > 0L) {
            val pipProgress = (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(scheme.background.copy(alpha = 0.42f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pipProgress)
                        .background(MoviaBrandAmber),
                )
            }
        }

        if (!inPictureInPicture && !episodesScreenOpen && !settingsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activity, isLandscape) {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        var lastTapAt = 0L
                        var lastTapDirection = 0
                        var tapChainCount = 0
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y
                            var previousX = startX
                            var previousY = startY
                            var totalX = 0f
                            var totalY = 0f
                            var verticalDrag = false
                            val centerGesture = startX in (size.width * 0.35f)..(size.width * 0.65f)
                            val brightnessGesture = !centerGesture && startX < size.width / 2f
                            // Keep the entire lower player chrome + transient system gesture area free.
                            // Brightness/volume/swipe-down may only start inside the video gesture zone.
                            val topGestureGuard = if (isLandscape) 72.dp.toPx() else 96.dp.toPx()
                            val bottomGestureGuard = if (isLandscape) 124.dp.toPx() else 196.dp.toPx()
                            val verticalGesturesAllowed = startY > topGestureGuard &&
                                startY < size.height - bottomGestureGuard

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val dx = change.position.x - previousX
                                val dy = change.position.y - previousY
                                totalX += dx
                                totalY += dy

                                if (!verticalDrag && verticalGesturesAllowed &&
                                    abs(totalY) > viewConfiguration.touchSlop &&
                                    abs(totalY) > abs(totalX)
                                ) {
                                    verticalDrag = true
                                }

                                if (verticalDrag && change.pressed) {
                                    change.consume()
                                    val delta = -dy / size.height.toFloat()
                                    if (centerGesture) {
                                        // Center vertical gesture is reserved for swipe-down close.
                                    } else if (brightnessGesture) {
                                        activity?.let { host ->
                                            val attrs = host.window.attributes
                                            val systemBrightness = Settings.System.getInt(
                                                context.contentResolver,
                                                Settings.System.SCREEN_BRIGHTNESS,
                                                128,
                                            ) / 255f
                                            val current = attrs.screenBrightness.takeIf { it >= 0f } ?: systemBrightness
                                            val next = (current + delta * 1.6f).coerceIn(0.02f, 1f)
                                            attrs.screenBrightness = next
                                            host.window.attributes = attrs
                                            gestureFeedbackLabel = "Яркость"
                                            gestureFeedbackPercent = (next * 100f).roundToInt()
                                            gestureFeedbackTick++
                                            showControls()
                                        }
                                    } else {
                                        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            .coerceAtLeast(1)
                                        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val next = (currentVolume + delta * maxVolume * 1.8f)
                                            .roundToInt()
                                            .coerceIn(0, maxVolume)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                        gestureFeedbackLabel = "Громкость"
                                        gestureFeedbackPercent = ((next * 100f) / maxVolume).roundToInt()
                                        gestureFeedbackTick++
                                        showControls()
                                    }
                                }

                                previousX = change.position.x
                                previousY = change.position.y
                                if (!change.pressed) {
                                    if (verticalDrag && centerGesture && !isLandscape && totalY > 120.dp.toPx()) {
                                        pendingCenterTapJob?.cancel()
                                        rootView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        if (playback.playWhenReady && activity != null) {
                                            enterPictureInPicture()
                                        } else {
                                            onMinimize()
                                        }
                                    } else if (!verticalDrag &&
                                        abs(totalY) < viewConfiguration.touchSlop &&
                                        abs(totalX) < viewConfiguration.touchSlop
                                    ) {
                                        val now = SystemClock.uptimeMillis()
                                        if (centerGesture) {
                                            // Delay the single tap so rapid gesture sequences do not flash the overlay.
                                            pendingCenterTapJob?.cancel()
                                            pendingCenterTapJob = gestureScope.launch {
                                                delay(280L)
                                                controlsVisible = !controlsVisible
                                                if (controlsVisible) interactionTick++
                                            }
                                            lastTapAt = 0L
                                            tapChainCount = 0
                                        } else {
                                            pendingCenterTapJob?.cancel()
                                            val direction = if (startX < size.width * 0.35f) -1 else 1
                                            if (now - lastTapAt <= 600L && direction == lastTapDirection) {
                                                tapChainCount += 1
                                                if (tapChainCount >= 2) {
                                                    val seconds = (tapChainCount - 1) * 10
                                                    seekBy(direction * 10_000L, seconds)
                                                }
                                            } else {
                                                tapChainCount = 1
                                                lastTapDirection = direction
                                            }
                                            lastTapAt = now
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    },
            )
        }


        AnimatedVisibility(
            visible = !inPictureInPicture && controlsVisible && !settingsOpen && !episodesScreenOpen,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Reference-style cinema dimmer: one soft layer instead of separate heavy bands.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(scheme.background.copy(alpha = 0.20f)),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.22f)
                        .background(
                            Brush.verticalGradient(
                                listOf(scheme.background.copy(alpha = 0.34f), Color.Transparent),
                            ),
                        ),
                )

                PlayerTopBar(
                    isLandscape = isLandscape,
                    onBack = handlePlayerBack,
                    onPictureInPicture = { enterPictureInPicture() },
                    onSettings = {
                        settingsPicker = null
                        settingsOpen = true
                        controlsVisible = false
                    },
                    onInteraction = ::showControls,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                Surface(
                    color = Color.Transparent,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(PLAYER_CENTER_CONTROL_SIZE)
                        .border(1.dp, MoviaBrandAmber.copy(alpha = 0.48f), CircleShape),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.00f to MoviaGlowLuminescence.copy(alpha = 0.20f),
                                        0.38f to scheme.surfaceContainer.copy(alpha = 0.62f),
                                        0.72f to scheme.surfaceContainer.copy(alpha = 0.44f),
                                        1.00f to scheme.surface.copy(alpha = 0.28f),
                                    ),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (playback.status == app.movia.android.domain.model.PlaybackStatus.BUFFERING) {
                            CircularProgressIndicator(
                                color = scheme.onSurface,
                                trackColor = scheme.onSurface.copy(alpha = 0.14f),
                                strokeWidth = 2.6.dp,
                                modifier = Modifier.size(31.7.dp),
                            )
                        } else {
                            IconButton(
                                onClick = {
                                    session.togglePlayPause()
                                    showControls()
                                },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (playback.isPlaying) "Пауза" else "Воспроизвести",
                                    tint = scheme.onSurface.copy(alpha = 0.96f),
                                    modifier = Modifier.size(PLAYER_CENTER_ICON_SIZE),
                                )
                            }
                        }
                    }
                }

            if (persistentSeekButtons) {
                Surface(
                    onClick = { seekBy(-10_000L) },
                    shape = CircleShape,
                    color = scheme.background.copy(alpha = 0.62f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 32.dp)
                        .size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Replay10, "Назад на 10 секунд", tint = scheme.onSurface, modifier = Modifier.size(34.dp))
                    }
                }
                Surface(
                    onClick = { seekBy(10_000L) },
                    shape = CircleShape,
                    color = scheme.background.copy(alpha = 0.62f),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 32.dp)
                        .size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Forward10, "Вперёд на 10 секунд", tint = scheme.onSurface, modifier = Modifier.size(34.dp))
                    }
                }
            }

            AnimatedVisibility(
                visible = seekFeedbackDirection < 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-100).dp),
            ) {
                SeekFeedbackBubble(forward = false, pulseKey = seekFeedbackTick, seconds = seekFeedbackSeconds)
            }

            AnimatedVisibility(
                visible = seekFeedbackDirection > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = 100.dp),
            ) {
                SeekFeedbackBubble(forward = true, pulseKey = seekFeedbackTick, seconds = seekFeedbackSeconds)
            }

            gestureFeedbackLabel?.let { label ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = scheme.background.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = if (gestureFeedbackPercent > 0) "$label · $gestureFeedbackPercent%" else label,
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                }
            }

            PlayerTimeline(
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                isScrubbing = scrubbing,
                scrubPreview = scrubPreviewFrame,
                onScrub = { value ->
                    scrubbing = true
                    scrubPositionMs = value
                    showControls()
                },
                onScrubFinished = {
                    session.seekTo(scrubPositionMs)
                    scrubbing = false
                    showControls()
                },
                onToggleFullscreen = {
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    showControls()
                },
                showEpisodes = isSeries,
                onEpisodes = {
                    episodesScreenSeason = currentSeason
                    episodesScreenOpen = true
                    controlsVisible = false
                },
                isLandscape = isLandscape,
                landscapeVideoHorizontalInset = landscapeVideoHorizontalInset,
                onInteraction = ::showControls,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
            }
        }


        if (!inPictureInPicture && episodesScreenOpen && isSeries && mediaContent != null) {
            PlayerEpisodeSelectionScreen(
                seasonEpisodeCounts = mediaContent.seasonEpisodeCounts,
                selectedSeason = episodesScreenSeason,
                currentSeason = currentSeason,
                currentEpisode = currentEpisode,
                onSeasonSelected = { episodesScreenSeason = it },
                onBack = {
                    episodesScreenOpen = false
                    showControls()
                },
                onSelectEpisode = { season, episode ->
                    episodesScreenOpen = false
                    onSelectEpisode(season, episode)
                    showControls()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(20f),
            )
        }

        if (!inPictureInPicture && settingsOpen) {
            val audioOptions = buildDisplayOptions(audioAutoLabel, audioTracks.map { it.label })
            val qualityOptions = buildDisplayOptions(qualityAutoLabel, videoQualityTracks.map { it.label })
            val subtitleOptions = if (subtitleTracks.isEmpty()) {
                listOf("Нет")
            } else {
                (listOf("Нет", "Авто") + subtitleTracks.map { it.label }).distinct()
            }
            val speedOptions = SPEEDS.map { "${formatSpeed(it)}×" }

            PlayerPlaybackSettingsScreen(
                picker = settingsPicker,
                audioValue = audioSummary,
                subtitleValue = subtitleSummary,
                speedValue = speedSummary,
                qualityValue = qualitySummary,
                audioOptions = audioOptions,
                subtitleOptions = subtitleOptions,
                speedOptions = speedOptions,
                qualityOptions = qualityOptions,
                selectedAudio = audioSummary,
                selectedSubtitle = subtitlePickerSelected,
                selectedSpeed = speedSummary,
                selectedQuality = qualitySummary,
                onBack = {
                    if (settingsPicker != null) {
                        settingsPicker = null
                    } else {
                        closePlaybackSettings()
                    }
                },
                onOpenPicker = { settingsPicker = it },
                onAudioSelected = { value ->
                    selectAudio(if (value == audioAutoLabel) "Auto" else value)
                    settingsPicker = null
                },
                onSubtitleSelected = { value ->
                    when (value) {
                        "Нет" -> selectSubtitleTrack(null)
                        "Авто" -> selectAutomaticSubtitles()
                        else -> subtitleTracks.firstOrNull { it.label == value }?.let(::selectSubtitleTrack)
                    }
                    settingsPicker = null
                },
                onSpeedSelected = { value ->
                    speed = value.removeSuffix("×").toFloatOrNull() ?: 1f
                    settingsPicker = null
                },
                onQualitySelected = { value ->
                    selectQuality(if (value == qualityAutoLabel) "Auto" else value)
                    settingsPicker = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(30f),
            )
        }

        if (!inPictureInPicture) playbackError?.let { error ->
            Text(
                text = "Ошибка воспроизведения: $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                     .padding(horizontal = 24.dp, vertical = 80.dp),
            )
        }
    }
}

private fun Modifier.playerSwipeDownBack(
    gestureKey: Any?,
    onBack: () -> Unit,
): Modifier = pointerInput(gestureKey, onBack) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val topGestureZone = 112.dp.toPx()
        if (down.position.y > topGestureZone) {
            do {
                val event = awaitPointerEvent()
            } while (event.changes.any { it.pressed })
            return@awaitEachGesture
        }

        var previous = down.position
        var totalX = 0f
        var totalY = 0f
        var released = false
        while (!released) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            totalX += change.position.x - previous.x
            totalY += change.position.y - previous.y
            previous = change.position
            released = !change.pressed
        }

        val minDistance = 96.dp.toPx()
        val intentionalDown = totalY >= minDistance &&
            totalY > abs(totalX) * 1.35f
        if (intentionalDown) onBack()
    }
}

private fun Modifier.playerSeasonHorizontalSwipe(
    selectedSeason: Int,
    seasonCount: Int,
    onSeasonSelected: (Int) -> Unit,
): Modifier = pointerInput(selectedSeason, seasonCount, onSeasonSelected) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var previous = down.position
        var totalX = 0f
        var totalY = 0f
        var released = false
        while (!released) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: break
            totalX += change.position.x - previous.x
            totalY += change.position.y - previous.y
            previous = change.position
            released = !change.pressed
        }

        val minDistance = 88.dp.toPx()
        val intentionalHorizontal = abs(totalX) >= minDistance &&
            abs(totalX) > abs(totalY) * 1.35f
        if (intentionalHorizontal) {
            val target = if (totalX < 0f) selectedSeason + 1 else selectedSeason - 1
            if (target in 1..seasonCount) onSeasonSelected(target)
        }
    }
}

@Composable
private fun PlayerPlaybackSettingsScreen(
    picker: PlayerSettingsPicker?,
    audioValue: String,
    subtitleValue: String,
    speedValue: String,
    qualityValue: String,
    audioOptions: List<String>,
    subtitleOptions: List<String>,
    speedOptions: List<String>,
    qualityOptions: List<String>,
    selectedAudio: String,
    selectedSubtitle: String,
    selectedSpeed: String,
    selectedQuality: String,
    onBack: () -> Unit,
    onOpenPicker: (PlayerSettingsPicker) -> Unit,
    onAudioSelected: (String) -> Unit,
    onSubtitleSelected: (String) -> Unit,
    onSpeedSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pickerTitle = when (picker) {
        PlayerSettingsPicker.AUDIO -> "Аудиодорожка"
        PlayerSettingsPicker.SUBTITLES -> "Субтитры"
        PlayerSettingsPicker.SPEED -> "Скорость"
        PlayerSettingsPicker.QUALITY -> "Качество"
        null -> "Настройки воспроизведения"
    }

    Column(
        modifier = modifier
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .playerSwipeDownBack(
                gestureKey = picker,
                onBack = onBack,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = if (picker == null) "Назад в плеер" else "Назад к настройкам воспроизведения",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = pickerTitle,
                color = scheme.onBackground,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
            )

            if (picker == null) {
                PlayerSettingsSectionLabel("АУДИО И СУБТИТРЫ")
                PlayerSettingsNavigationRow(
                    title = "Аудиодорожка",
                    value = audioValue,
                    onClick = { onOpenPicker(PlayerSettingsPicker.AUDIO) },
                )
                PlayerSettingsNavigationRow(
                    title = "Субтитры",
                    value = subtitleValue,
                    onClick = { onOpenPicker(PlayerSettingsPicker.SUBTITLES) },
                )

                PlayerSettingsSectionLabel("ВОСПРОИЗВЕДЕНИЕ")
                PlayerSettingsNavigationRow(
                    title = "Скорость",
                    value = speedValue,
                    onClick = { onOpenPicker(PlayerSettingsPicker.SPEED) },
                )

                PlayerSettingsSectionLabel("ВИДЕО")
                PlayerSettingsNavigationRow(
                    title = "Качество",
                    value = qualityValue,
                    onClick = { onOpenPicker(PlayerSettingsPicker.QUALITY) },
                )
            } else {
                val (options, selected, onSelected) = when (picker) {
                    PlayerSettingsPicker.AUDIO -> Triple(audioOptions, selectedAudio, onAudioSelected)
                    PlayerSettingsPicker.SUBTITLES -> Triple(subtitleOptions, selectedSubtitle, onSubtitleSelected)
                    PlayerSettingsPicker.SPEED -> Triple(speedOptions, selectedSpeed, onSpeedSelected)
                    PlayerSettingsPicker.QUALITY -> Triple(qualityOptions, selectedQuality, onQualitySelected)
                }
                options.forEach { option ->
                    PlayerSettingsOptionRow(
                        label = option,
                        selected = option == selected,
                        onClick = { onSelected(option) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsSectionLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PlayerSettingsNavigationRow(
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    color = scheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = value,
                    color = MoviaBrandAmber,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = scheme.onSurface.copy(alpha = 0.72f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun PlayerSettingsOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MoviaBrandAmber.copy(alpha = 0.14f) else scheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .border(
                width = 1.dp,
                color = if (selected) MoviaBrandAmber else MoviaBorderSubtle,
                shape = RoundedCornerShape(14.dp),
            )
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = if (selected) MoviaBrandAmber else scheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "✓",
                    color = MoviaBrandAmber,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun PlayerEpisodeSelectionScreen(
    seasonEpisodeCounts: List<Int>,
    selectedSeason: Int,
    currentSeason: Int,
    currentEpisode: Int?,
    onSeasonSelected: (Int) -> Unit,
    onBack: () -> Unit,
    onSelectEpisode: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val availableSeasons = (1..seasonEpisodeCounts.size.coerceAtLeast(1)).toList()
    val safeSeason = selectedSeason.coerceIn(1, availableSeasons.size)
    val episodeCount = seasonEpisodeCounts.getOrNull(safeSeason - 1) ?: 0
    val episodeScrollState = rememberScrollState()
    LaunchedEffect(safeSeason) {
        episodeScrollState.scrollTo(0)
    }

    Column(
        modifier = modifier
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .playerSwipeDownBack(
                gestureKey = safeSeason,
                onBack = onBack,
            )
            .playerSeasonHorizontalSwipe(
                selectedSeason = safeSeason,
                seasonCount = availableSeasons.size,
                onSeasonSelected = onSeasonSelected,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            PlayerGlassAction(
                onClick = onBack,
                icon = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Назад к плееру",
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(availableSeasons) { season ->
                MoviaChoiceChip(
                    label = "Сезон $season",
                    selected = safeSeason == season,
                    onClick = { onSeasonSelected(season) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(episodeScrollState)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (1..episodeCount).forEach { episode ->
                val selected = safeSeason == currentSeason && episode == currentEpisode
                Surface(
                    onClick = { onSelectEpisode(safeSeason, episode) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) {
                        MoviaBrandAmber.copy(alpha = 0.12f)
                    } else {
                        scheme.surfaceContainer.copy(alpha = 0.58f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 58.dp)
                        .border(
                            width = 1.dp,
                            color = if (selected) MoviaBrandAmber else MoviaBorderSubtle,
                            shape = RoundedCornerShape(14.dp),
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = scheme.surfaceContainer,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = MoviaBrandAmber,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                        Text(
                            text = "$episode. Эпизод $episode",
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    isLandscape: Boolean,
    onBack: () -> Unit,
    onPictureInPicture: () -> Unit,
    onSettings: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isLandscape) {
                    Modifier
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                         .padding(horizontal = 24.dp, vertical = 16.dp)
                } else {
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(
                                WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                            ),
                        )
                         .padding(horizontal = 16.dp, vertical = 16.dp)
                }
            ),
    ) {
        PlayerGlassAction(
            onClick = { onInteraction(); onBack() },
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "Назад",
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerGlassAction(
                onClick = {
                    onInteraction()
                    onPictureInPicture()
                },
                icon = Icons.Outlined.PictureInPictureAlt,
                contentDescription = "Picture-in-Picture",
                iconSize = 22.dp,
            )
            PlayerGlassAction(
                onClick = {
                    onSettings()
                },
                icon = Icons.Outlined.Settings,
                contentDescription = "Настройки плеера",
            )
        }
    }
}

@Composable
private fun PlayerGlassAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceContainer.copy(alpha = 0.62f),
        modifier = modifier
            .size(52.dp)
            .border(1.dp, MoviaBorderSubtle, CircleShape),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = scheme.onSurface.copy(alpha = 0.94f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun PlayerRoundSecondaryAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceContainer.copy(alpha = 0.62f),
        modifier = modifier
            .size(48.dp)
            .border(1.dp, MoviaBorderSubtle, CircleShape),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = scheme.onSurface.copy(alpha = 0.94f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerTimeline(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    scrubPreview: ImageBitmap?,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onToggleFullscreen: () -> Unit,
    showEpisodes: Boolean,
    onEpisodes: () -> Unit,
    isLandscape: Boolean,
    landscapeVideoHorizontalInset: androidx.compose.ui.unit.Dp,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val safeDuration = max(1L, durationMs)
    val thumbDiameter by animateDpAsState(
        targetValue = if (isScrubbing) 14.dp else 10.dp,
        animationSpec = tween(durationMillis = 120),
        label = "playerScrubberThumb",
    )
    val playedFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val remainingMs = max(0L, durationMs - positionMs)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val timelineInset = 5.dp
    val landscapeSafeLeft = if (isLandscape) {
        with(density) {
            max(
                WindowInsets.displayCutout.getLeft(density, layoutDirection),
                WindowInsets.navigationBarsIgnoringVisibility.getLeft(density, layoutDirection),
            ).toDp()
        }
    } else {
        0.dp
    }
    val landscapeSafeRight = if (isLandscape) {
        with(density) {
            max(
                WindowInsets.displayCutout.getRight(density, layoutDirection),
                WindowInsets.navigationBarsIgnoringVisibility.getRight(density, layoutDirection),
            ).toDp()
        }
    } else {
        0.dp
    }
    val outerStart = if (isLandscape) {
        (maxOf(landscapeVideoHorizontalInset + 5.dp, landscapeSafeLeft) - timelineInset)
            .coerceAtLeast(0.dp)
    } else {
        0.dp
    }
    val outerEnd = if (isLandscape) {
        (maxOf(landscapeVideoHorizontalInset + 5.dp, landscapeSafeRight) - timelineInset)
            .coerceAtLeast(0.dp)
    } else {
        0.dp
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isLandscape) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom),
                    )
                } else {
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .windowInsetsPadding(
                            WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom),
                        )
                }
            )
            .padding(start = outerStart, end = outerEnd),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val timelineWidth = maxWidth
            val thumbCenterX = timelineInset +
                (timelineWidth - timelineInset * 2f) * playedFraction
            val previewWidth = 120.dp
            val previewX = (thumbCenterX - previewWidth / 2f)
                .coerceIn(0.dp, maxWidth - previewWidth)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .semantics {
                            contentDescription = "Позиция воспроизведения"
                            stateDescription = "${formatTime(positionMs)} из ${formatTime(durationMs)}"
                            progressBarRangeInfo = ProgressBarRangeInfo(
                                current = positionMs.coerceIn(0L, safeDuration).toFloat(),
                                range = 0f..safeDuration.toFloat(),
                            )
                            setProgress { target ->
                                onScrub(target.toLong().coerceIn(0L, safeDuration))
                                onScrubFinished()
                                true
                            }
                        }
                        .pointerInput(safeDuration) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val trackInsetPx = timelineInset.toPx()
                                val usableWidth = (size.width - trackInsetPx * 2f).coerceAtLeast(1f)
                                fun positionForX(x: Float): Long {
                                    val fraction = ((x - trackInsetPx) / usableWidth).coerceIn(0f, 1f)
                                    return (safeDuration * fraction).toLong()
                                }
                                onScrub(positionForX(down.position.x))
                                do {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    onScrub(positionForX(change.position.x))
                                    change.consume()
                                } while (event.changes.any { it.pressed })
                                onScrubFinished()
                            }
                        }
                        .drawBehind {
                            val centerY = size.height / 2f
                            val trackInsetPx = timelineInset.toPx()
                            val trackStart = trackInsetPx
                            val trackEnd = size.width - trackInsetPx
                            val trackWidthPx = (trackEnd - trackStart).coerceAtLeast(1f)
                            val playedX = trackStart + trackWidthPx * playedFraction
                            val bufferedX = trackStart + trackWidthPx * bufferedFraction
                            val stroke = 3.dp.toPx()
                            drawLine(
                                color = MoviaBorderSubtle,
                                start = Offset(trackStart, centerY),
                                end = Offset(trackEnd, centerY),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = scheme.onSurface.copy(alpha = 0.34f),
                                start = Offset(trackStart, centerY),
                                end = Offset(bufferedX.coerceIn(trackStart, trackEnd), centerY),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                            drawLine(
                                color = MoviaBrandAmber,
                                start = Offset(trackStart, centerY),
                                end = Offset(playedX.coerceIn(trackStart, trackEnd), centerY),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round,
                            )
                            val radius = thumbDiameter.toPx() / 2f
                            if (isScrubbing) {
                                drawCircle(
                                    color = MoviaGlowLuminescence.copy(alpha = 0.14f),
                                    radius = radius + 3.dp.toPx(),
                                    center = Offset(playedX.coerceIn(trackStart, trackEnd), centerY),
                                )
                            }
                            drawCircle(
                                color = scheme.onSurface,
                                radius = radius,
                                center = Offset(playedX.coerceIn(trackStart, trackEnd), centerY),
                            )
                        },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = timelineInset),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTime(positionMs),
                        color = scheme.onSurface.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "−${formatTime(remainingMs)}",
                        color = scheme.onSurface.copy(alpha = 0.78f),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (showEpisodes) {
                        PlayerRoundSecondaryAction(
                            onClick = {
                                onInteraction()
                                onEpisodes()
                            },
                            icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                            contentDescription = "Выбор сезона и серий",
                        )
                    }
                    PlayerRoundSecondaryAction(
                        onClick = {
                            onInteraction()
                            onToggleFullscreen()
                        },
                        icon = if (isLandscape) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        contentDescription = if (isLandscape) {
                            "Свернуть из полноэкранного режима"
                        } else {
                            "Развернуть на весь экран"
                        },
                    )
                }
            }

            if (isScrubbing) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = scheme.surface.copy(alpha = 0.93f),
                    modifier = Modifier
                        .offset(x = previewX, y = (-74).dp)
                        .width(previewWidth)
                        .height(68.dp)
                        .zIndex(4f)
                        .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(10.dp)),
                ) {
                    if (scrubPreview != null) {
                        Image(
                            bitmap = scrubPreview,
                            contentDescription = "Предпросмотр кадра ${formatTime(positionMs)}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(scheme.background.copy(alpha = 0.44f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = formatTime(positionMs),
                                color = scheme.onSurface.copy(alpha = 0.92f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun moviaVideoAspectRatio(videoSize: VideoSize): Float {
    if (videoSize.width <= 0 || videoSize.height <= 0) return 0f
    val pixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
    val displayAspect = (videoSize.width.toFloat() * pixelRatio) / videoSize.height.toFloat()
    return displayAspect.takeIf { it.isFinite() && it > 0f } ?: 0f
}

private fun buildAudioTrackOptions(tracks: Tracks): List<SelectableTrackOption> {
    val result = mutableListOf<SelectableTrackOption>()
    tracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val language = format.language
                ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                ?.let(::displayLanguage)
                ?.takeIf { it.isNotBlank() }
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: language
                ?: "Аудио ${result.size + 1}"
            result += SelectableTrackOption(
                label = label,
                override = TrackSelectionOverride(group.mediaTrackGroup, index),
                selected = group.isTrackSelected(index),
            )
        }
    }
    return result.distinctBy { it.label }
}

private fun buildVideoQualityTrackOptions(tracks: Tracks): List<SelectableTrackOption> {
    val result = mutableListOf<SelectableTrackOption>()
    tracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val label = when {
                format.height >= 2160 -> "4K"
                format.height > 0 -> "${format.height}p"
                format.label?.isNotBlank() == true -> format.label.orEmpty()
                else -> "Видео ${result.size + 1}"
            }
            result += SelectableTrackOption(
                label = label,
                override = TrackSelectionOverride(group.mediaTrackGroup, index),
                selected = group.isTrackSelected(index),
            )
        }
    }
    return result.distinctBy { it.label }
        .sortedWith(compareByDescending<SelectableTrackOption> { qualityRank(it.label) }.thenBy { it.label })
}

private fun qualityRank(label: String): Int = when {
    label == "4K" -> 2160
    label.endsWith("p") -> label.removeSuffix("p").toIntOrNull() ?: 0
    else -> 0
}

private fun buildDisplayOptions(autoLabel: String, tracks: List<String>): List<String> =
    (listOf(autoLabel) + tracks).distinct()

private fun buildSubtitleTrackOptions(tracks: Tracks): List<SubtitleTrackOption> {
    val result = mutableListOf<SubtitleTrackOption>()
    tracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_TEXT) return@forEach
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val format = group.getTrackFormat(index)
            val label = format.label?.takeIf { it.isNotBlank() }
                ?: format.language?.takeIf { it.isNotBlank() }?.let(::displayLanguage)
                ?: "Дорожка ${result.size + 1}"
            result += SubtitleTrackOption(
                label = label,
                override = TrackSelectionOverride(group.mediaTrackGroup, index),
                selected = group.isTrackSelected(index),
            )
        }
    }
    return result.distinctBy { it.label to it.override.mediaTrackGroup.id }
}

private fun displayLanguage(languageTag: String): String {
    val locale = Locale.forLanguageTag(languageTag)
    val name = locale.getDisplayLanguage(Locale("ru"))
    return name.replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale("ru")) else it.toString()
    }
}


@Composable
private fun MoviaChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            MoviaBrandAmber.copy(alpha = 0.20f)
        } else {
            scheme.onSurface.copy(alpha = 0.09f)
        },
        contentColor = if (selected) {
            scheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.94f)
        },
        modifier = Modifier
            .widthIn(min = 56.dp)
            .heightIn(min = 48.dp)
            .border(
                width = 1.dp,
                color = if (selected) {
                    MoviaBrandAmber.copy(alpha = 0.40f)
                } else {
                    scheme.onSurface.copy(alpha = 0.06f)
                },
                shape = RoundedCornerShape(12.dp),
            )
            .semantics { this.selected = selected },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SeekFeedbackBubble(
    forward: Boolean,
    pulseKey: Int,
    seconds: Int,
) {
    val scheme = MaterialTheme.colorScheme
    val rippleScale = remember { Animatable(0.76f) }
    val rippleAlpha = remember { Animatable(0.58f) }
    LaunchedEffect(pulseKey) {
        rippleScale.snapTo(0.76f)
        rippleAlpha.snapTo(0.58f)
        rippleScale.animateTo(1.10f, animationSpec = tween(280))
        rippleAlpha.animateTo(0f, animationSpec = tween(200))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(152.dp),
    ) {
        Box(
            modifier = Modifier
                .size(129.dp)
                .graphicsLayer {
                    scaleX = rippleScale.value
                    scaleY = rippleScale.value
                    alpha = rippleAlpha.value
                }
                .drawBehind {
                    drawArc(
                        color = scheme.onSurface.copy(alpha = 0.70f),
                        startAngle = if (forward) -70f else 110f,
                        sweepAngle = 140f,
                        useCenter = false,
                        style = Stroke(width = 2.9.dp.toPx(), cap = StrokeCap.Round),
                    )
                },
        )
        Surface(
            shape = CircleShape,
            color = scheme.background.copy(alpha = 0.54f),
            modifier = Modifier.size(66.7.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10,
                    contentDescription = if (forward) {
                        "Перемотка вперёд на $seconds секунд"
                    } else {
                        "Перемотка назад на $seconds секунд"
                    },
                    tint = scheme.onSurface,
                    modifier = Modifier.size(36.8.dp),
                )
            }
        }
    }
}

private fun displayPlayerTitle(title: String): String {
    val match = Regex("""^(.*) · S(\d{2})E(\d{2})(?: · Эпизод \d+)?$""").matchEntire(title)
    if (match != null) {
        val base = match.groupValues[1].trim()
        val episode = match.groupValues[3].toIntOrNull() ?: return base
        return "$base · Эпизод $episode"
    }
    return title
        .replace(Regex(""" · Эпизод \d+$"""), "")
        .trim()
        .trimEnd('·')
        .trim()
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = max(0L, milliseconds) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
