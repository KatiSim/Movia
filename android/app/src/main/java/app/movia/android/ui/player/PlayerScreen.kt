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
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    RESIZE,
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
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    onSubtitlesChanged: (Boolean) -> Unit = {},
    onSubtitleTrackIdChanged: (String?) -> Unit = {},
    onPreviousEpisode: () -> Unit = {},
    onNextEpisode: () -> Unit = {},
    onSelectEpisode: (Int, Int) -> Unit = { _, _ -> },
    onAutoNextChanged: (Boolean) -> Unit = {},
    onPersistentSeekButtonsChanged: (Boolean) -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val rootView = LocalView.current
    val gestureScope = rememberCoroutineScope()
    val playback by session.state.collectAsState()
    val sessionStreams by session.streamOptions.collectAsState()
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val baseTitle = remember(title) { title.substringBefore(" · S").substringBefore(" · E") }
    val mediaContent = remember(baseTitle) { DemoCatalogRepository.findByTitle(baseTitle) }
    val isSeries = mediaContent?.type == ContentType.SERIES || mediaContent?.seasonEpisodeCounts?.isNotEmpty() == true
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
    var controlsLocked by remember { mutableStateOf(false) }
    var lockButtonDimmed by remember { mutableStateOf(false) }
    var suppressControlsUntilTap by remember { mutableStateOf(false) }
    var closingBySwipe by remember { mutableStateOf(false) }
    val playerTranslationY = remember { Animatable(0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var sleepTimerStopsAtEnd by remember { mutableStateOf(false) }
    val activeVoice = playback.activeStreamSelection?.activeVoice
        ?: playback.activeStreamSelection?.requestedVoice
        ?: preferredAudio.takeUnless { it.equals("Auto", ignoreCase = true) }
    val activeQuality = playback.activeStreamSelection?.activeQuality
        ?: playback.activeStreamSelection?.requestedQuality
        ?: preferredQuality.takeUnless { it.equals("Auto", ignoreCase = true) }
    val resizeModeSummary = when (resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Заполнение"
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Растянуть"
        else -> "16:9"
    }
    val resizeModeOptions = listOf("16:9 (Исходный)", "Заполнение (Zoom)", "Растянуть (Fill)")
    val sleepTimerValue = when {
        sleepTimerStopsAtEnd -> "Конец"
        sleepTimerMinutes == 15 -> "15 мин"
        sleepTimerMinutes == 30 -> "30 мин"
        else -> "Выкл"
    }
    val sleepTimerOptions = listOf("Выкл", "15 мин", "30 мин", "Конец")

    fun showControls() {
        if (!inPictureInPicture) {
            suppressControlsUntilTap = false
            controlsVisible = true
            interactionTick++
        }
    }

    fun toggleControls() {
        if (!inPictureInPicture && !controlsLocked) {
            suppressControlsUntilTap = false
            controlsVisible = !controlsVisible
            if (controlsVisible) interactionTick++
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
        controlsLocked,
        suppressControlsUntilTap,
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
        if (controlsVisible && !controlsLocked && activePlayback && playbackError == null &&
            !settingsOpen && !episodesScreenOpen && !scrubbing
        ) {
            delay(3_500L)
            val stillPlaying = playback.isPlaying ||
                (playback.playWhenReady && playback.status == app.movia.android.domain.model.PlaybackStatus.BUFFERING)
            if (controlsVisible && stillPlaying && !controlsLocked &&
                playbackError == null && !settingsOpen && !episodesScreenOpen && !scrubbing
            ) {
                // One shared gate hides the top bar, center controls and timeline together.
                controlsVisible = false
            }
        } else if (!controlsLocked && !suppressControlsUntilTap &&
            (!activePlayback || playbackError != null)
        ) {
            controlsVisible = true
        }
    }

    LaunchedEffect(controlsLocked) {
        if (controlsLocked) {
            controlsVisible = false
            lockButtonDimmed = false
            delay(3_500L)
            if (controlsLocked) lockButtonDimmed = true
        } else {
            lockButtonDimmed = false
        }
    }

    LaunchedEffect(player, title, autoNextEnabled, sleepTimerStopsAtEnd) {
        if (!autoNextEnabled || sleepTimerStopsAtEnd) return@LaunchedEffect
        while (true) {
            delay(500L)
            if (player.playbackState == Player.STATE_ENDED) {
                onNextEpisode()
                break
            }
        }
    }

    LaunchedEffect(player, sleepTimerMinutes) {
        if (sleepTimerMinutes <= 0) return@LaunchedEffect
        delay(sleepTimerMinutes * 60_000L)
        player.pause()
        sleepTimerMinutes = 0
    }

    DisposableEffect(player, title) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                playbackError = error.errorCodeName
                showControls()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    playbackError = null
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    playbackError = null
                }
                showControls()
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTracks = buildAudioTrackOptions(tracks)
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

    fun selectEmbeddedAudioTrack(option: SelectableTrackOption) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(option.override)
            .build()
        audioTracks = buildAudioTrackOptions(player.currentTracks)
        showControls()
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
        if (controlsLocked) {
            controlsLocked = false
            lockButtonDimmed = false
            suppressControlsUntilTap = false
            controlsVisible = true
            interactionTick++
        } else {
            when {
                settingsOpen -> closePlaybackSettings()
                episodesScreenOpen -> {
                    episodesScreenOpen = false
                    showControls()
                }
                isLandscape -> exitFullscreen()
                else -> leavePlayer()
            }
        }
    }

    BackHandler(onBack = handlePlayerBack)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(scheme.background)
            .graphicsLayer { translationY = playerTranslationY.value },
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

        if (!inPictureInPicture && !episodesScreenOpen && !settingsOpen && !controlsLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activity, isLandscape) {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        var lastTapAt = 0L
                        var lastTapDirection = 0
                        var tapChainCount = 0
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
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
                            val topGestureGuard = if (isLandscape) 96.dp.toPx() else 168.dp.toPx()
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
                                        if (!closingBySwipe) {
                                            closingBySwipe = true
                                            leavePlayer()
                                        }
                                    } else if (!verticalDrag &&
                                        abs(totalY) < viewConfiguration.touchSlop &&
                                        abs(totalX) < viewConfiguration.touchSlop
                                    ) {
                                        val now = SystemClock.uptimeMillis()
                                        if (centerGesture) {
                                            pendingCenterTapJob?.cancel()
                                            toggleControls()
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
                                                // A single tap anywhere toggles the full player chrome.
                                                toggleControls()
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


        // Persistent middle layer: a combined G-shaped scrim over the video/AI cover.
        // It contains no pointer-input or click handler, so taps reach the gesture surface
        // below and the controls above remain interactive.
        if (!inPictureInPicture && !episodesScreenOpen && !settingsOpen && !controlsLocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Transparent,
                                0.46f to Color.Transparent,
                                0.70f to Color.Black.copy(alpha = 0.30f),
                                1.00f to Color.Black.copy(alpha = 0.75f),
                            ),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val bottomRightScrim = Brush.linearGradient(
                            colorStops = arrayOf(
                                0.00f to Color.Black.copy(alpha = 0.60f),
                                0.30f to Color.Black.copy(alpha = 0.20f),
                                0.60f to Color.Transparent,
                            ),
                            start = Offset(size.width, size.height),
                            end = Offset.Zero,
                        )
                        onDrawBehind {
                            drawRect(bottomRightScrim)
                        }
                    },
            )
        }

        val transientControlsVisible =
            !inPictureInPicture && controlsVisible && !controlsLocked && !settingsOpen && !episodesScreenOpen

        AnimatedVisibility(
            visible = transientControlsVisible,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlayerTopBar(
                    title = baseTitle,
                    contextLabel = playerContextLabel(baseTitle, currentSeason, currentEpisode),
                    locked = false,
                    onLockToggle = {
                        controlsLocked = true
                        suppressControlsUntilTap = false
                        controlsVisible = false
                        lockButtonDimmed = false
                        interactionTick++
                    },
                    isLandscape = isLandscape,
                    onBack = handlePlayerBack,
                    onPictureInPicture = { enterPictureInPicture() },
                    onSettings = {
                        settingsPicker = null
                        settingsOpen = true
                        controlsVisible = false
                    },
                    onInteraction = ::showControls,
                    onEpisodes = if (isSeries && mediaContent != null) {
                        {
                            episodesScreenSeason = currentSeason
                            episodesScreenOpen = true
                            controlsVisible = false
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSeries) {
                        Surface(
                            onClick = {
                                if (hasPreviousEpisode) {
                                    onPreviousEpisode()
                                    showControls()
                                }
                            },
                            enabled = hasPreviousEpisode,
                            shape = CircleShape,
                            color = scheme.surfaceContainer.copy(alpha = 0.82f),
                            border = BorderStroke(1.dp, MoviaBorderSubtle),
                            modifier = Modifier
                                .size(48.dp)
                                .alpha(if (hasPreviousEpisode) 1.0f else 0.25f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.SkipPrevious,
                                    contentDescription = "Предыдущая серия",
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                        if (persistentSeekButtons) {
                            Spacer(modifier = Modifier.width(16.dp))
                        } else {
                            Spacer(modifier = Modifier.width(44.dp))
                        }
                    }

                    if (persistentSeekButtons) {
                        Surface(
                            onClick = { seekBy(-10_000L) },
                            shape = CircleShape,
                            color = scheme.surfaceContainer.copy(alpha = 0.82f),
                            border = BorderStroke(1.dp, MoviaBorderSubtle),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Replay10,
                                    contentDescription = "Назад на 10 секунд",
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(20.dp))
                    }

                    Surface(
                        color = Color.Transparent,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(PLAYER_CENTER_CONTROL_SIZE)
                            .border(1.dp, MoviaBrandAmber.copy(alpha = 0.48f), CircleShape),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0.00f to MoviaGlowLuminescence.copy(alpha = 0.30f),
                                            0.38f to scheme.surfaceContainer.copy(alpha = 0.92f),
                                            0.72f to scheme.surfaceContainer.copy(alpha = 0.72f),
                                            1.00f to scheme.surface.copy(alpha = 0.50f),
                                        ),
                                    ),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (playback.status == app.movia.android.domain.model.PlaybackStatus.BUFFERING) {
                                MoviaLoadingSpinner(
                                    color = Color(0xFFE5A93C),
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp),
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
                        Spacer(modifier = Modifier.width(20.dp))
                        Surface(
                            onClick = { seekBy(10_000L) },
                            shape = CircleShape,
                            color = scheme.surfaceContainer.copy(alpha = 0.82f),
                            border = BorderStroke(1.dp, MoviaBorderSubtle),
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.Forward10,
                                    contentDescription = "Вперёд на 10 секунд",
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    }

                    if (isSeries) {
                        if (persistentSeekButtons) {
                            Spacer(modifier = Modifier.width(16.dp))
                        } else {
                            Spacer(modifier = Modifier.width(44.dp))
                        }
                        Surface(
                            onClick = {
                                if (hasNextEpisode) {
                                    onNextEpisode()
                                    showControls()
                                }
                            },
                            enabled = hasNextEpisode,
                            shape = CircleShape,
                            color = scheme.surfaceContainer.copy(alpha = 0.82f),
                            border = BorderStroke(1.dp, MoviaBorderSubtle),
                            modifier = Modifier
                                .size(48.dp)
                                .alpha(if (hasNextEpisode) 1.0f else 0.25f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = "Следующая серия",
                                    tint = scheme.onSurface,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                    }
                }

            AnimatedVisibility(
                visible = seekFeedbackDirection < 0,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (-100).dp),
            ) {
                SeekFeedbackBubble(forward = false, pulseKey = seekFeedbackTick, seconds = seekFeedbackSeconds)
            }

            AnimatedVisibility(
                visible = seekFeedbackDirection > 0,
                enter = EnterTransition.None,
                exit = ExitTransition.None,
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
                onOpenEpisodes = if (isSeries && mediaContent != null) {
                    {
                        episodesScreenSeason = currentSeason
                        episodesScreenOpen = true
                        controlsVisible = false
                    }
                } else null,
                onToggleFullscreen = {
                    activity?.requestedOrientation = if (isLandscape) {
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    }
                    showControls()
                },
                isLandscape = isLandscape,
                isSeries = isSeries,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            }
        }


        if (!inPictureInPicture && controlsLocked) {
            PlayerTopBar(
                title = baseTitle,
                contextLabel = playerContextLabel(baseTitle, currentSeason, currentEpisode),
                locked = true,
                onLockToggle = {
                    controlsLocked = false
                    lockButtonDimmed = false
                    suppressControlsUntilTap = false
                    controlsVisible = true
                    interactionTick++
                },
                isLandscape = isLandscape,
                lockButtonAlpha = if (lockButtonDimmed) 0.28f else 1f,
                onBack = {},
                onPictureInPicture = {},
                onSettings = {},
                onInteraction = {},
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(40f),
            )
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
            val contentStreams = sessionStreams.filter { it.url.isNotBlank() }
            val embeddedAudioOptions = audioTracks.map { it.label }
            val selectedEmbeddedAudio = audioTracks.firstOrNull { it.selected }?.label
            val streamQualities = StreamSettingsSelection.qualityOptions(contentStreams).ifEmpty {
                listOfNotNull(
                    activeQuality?.takeIf { it.isNotBlank() },
                    preferredQuality.takeUnless { it.equals("Auto", ignoreCase = true) },
                ).distinct().ifEmpty { listOf("Авто") }
            }

            val currentQuality = activeQuality
                ?.takeIf { active -> streamQualities.any { it.equals(active, ignoreCase = true) } }
                ?: preferredQuality.takeUnless { it.equals("Auto", ignoreCase = true) }
                    ?.takeIf { preferred -> streamQualities.any { it.equals(preferred, ignoreCase = true) } }
                ?: streamQualities.first()

            val streamVoices = StreamSettingsSelection.voiceOptions(contentStreams, currentQuality).ifEmpty {
                listOfNotNull(
                    activeVoice?.takeIf { it.isNotBlank() },
                    preferredAudio.takeUnless { it.equals("Auto", ignoreCase = true) },
                ).distinct().ifEmpty { listOf("Авто") }
            }

            val currentVoice = activeVoice
                ?.takeIf { active -> streamVoices.any { it.equals(active, ignoreCase = true) } }
                ?: preferredAudio.takeUnless { it.equals("Auto", ignoreCase = true) }
                    ?.takeIf { preferred -> streamVoices.any { it.equals(preferred, ignoreCase = true) } }
                ?: streamVoices.first()

            StreamSettingsScreen(
                audioOptions = streamVoices,
                qualityOptions = streamQualities,
                selectedAudio = currentVoice,
                selectedQuality = currentQuality,
                embeddedAudioOptions = embeddedAudioOptions,
                selectedEmbeddedAudio = selectedEmbeddedAudio,
                autoNextEnabled = autoNextEnabled,
                persistentSeekButtons = persistentSeekButtons,
                onBack = {
                    settingsOpen = false
                    showControls()
                },
                onAudioSelected = { newVoice ->
                    StreamSettingsSelection.select(contentStreams, newVoice, currentQuality)?.let { matchedStream ->
                        session.switchToStream(matchedStream, session.state.value.currentPositionMs)
                    }
                    onAudioSelected(newVoice)
                    showControls()
                },
                onEmbeddedAudioSelected = { label ->
                    audioTracks.firstOrNull { it.label == label }?.let(::selectEmbeddedAudioTrack)
                },
                onQualitySelected = { newQuality ->
                    val voicesForQuality = StreamSettingsSelection.voiceOptions(contentStreams, newQuality)
                    val voiceForQuality = currentVoice.takeIf { current ->
                        voicesForQuality.any { it.equals(current, ignoreCase = true) }
                    } ?: voicesForQuality.firstOrNull()
                    StreamSettingsSelection.select(contentStreams, voiceForQuality, newQuality)?.let { matchedStream ->
                        session.switchToStream(matchedStream, session.state.value.currentPositionMs)
                    }
                    onQualitySelected(newQuality)
                    voiceForQuality?.let(onAudioSelected)
                    showControls()
                },
                onAutoNextChanged = onAutoNextChanged,
                onPersistentSeekButtonsChanged = onPersistentSeekButtonsChanged,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(50f),
            )
        }

        if (!inPictureInPicture && playback.statusMessage == "Источники для данного тайтла временно недоступны") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = scheme.surfaceContainerHigh.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, MoviaBorderSubtle),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp)
                    .zIndex(20f),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = "Источники временно недоступны",
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Не удалось найти рабочий поток для выбранного тайтла. Попробуйте выбрать другую озвучку или повторите попытку позже.",
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Surface(
                        onClick = handlePlayerBack,
                        color = MoviaBrandAmber,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Вернуться назад",
                            color = Color.Black,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        } else if (!inPictureInPicture && playback.status == app.movia.android.domain.model.PlaybackStatus.BUFFERING && !playback.isPlaying) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color(0xFFE5A93C).copy(alpha = 0.35f)),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 130.dp)
                    .zIndex(15f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MoviaLoadingSpinner(
                        color = Color(0xFFE5A93C),
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = playback.statusMessage ?: "Поиск доступных источников...",
                        color = Color(0xFFE5A93C),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (!inPictureInPicture && playback.status == app.movia.android.domain.model.PlaybackStatus.IDLE && !playback.isPlaying && !playback.hasMedia) playbackError?.let { error ->
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
    gestureKey: Any? = null,
    minDistanceDp: androidx.compose.ui.unit.Dp = 70.dp,
    topZoneDp: androidx.compose.ui.unit.Dp = 180.dp,
    onBack: () -> Unit,
): Modifier = pointerInput(gestureKey, onBack) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val topGestureZone = topZoneDp.toPx()
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

        val minDistance = minDistanceDp.toPx()
        val intentionalDown = totalY >= minDistance &&
            totalY > abs(totalX) * 1.25f
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
private fun MoviaLoadingSpinner(
    modifier: Modifier = Modifier,
    color: Color = MoviaBrandAmber,
    strokeWidth: androidx.compose.ui.unit.Dp = 3.dp,
) {
    var rotation by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var previousFrame = 0L
        while (true) {
            withFrameNanos { frameNanos ->
                if (previousFrame != 0L) {
                    val deltaSeconds = (frameNanos - previousFrame).coerceAtMost(100_000_000L) / 1_000_000_000f
                    rotation = (rotation + 300f * deltaSeconds) % 360f
                }
                previousFrame = frameNanos
            }
        }
    }
    Canvas(modifier = modifier) {
        val stroke = strokeWidth.toPx()
        drawArc(
            color = color.copy(alpha = 0.20f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = 245f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun StreamSettingsScreen(
    audioOptions: List<String>,
    qualityOptions: List<String>,
    selectedAudio: String,
    selectedQuality: String,
    embeddedAudioOptions: List<String> = emptyList(),
    selectedEmbeddedAudio: String? = null,
    autoNextEnabled: Boolean,
    persistentSeekButtons: Boolean,
    onBack: () -> Unit,
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onEmbeddedAudioSelected: (String) -> Unit = {},
    onAutoNextChanged: (Boolean) -> Unit,
    onPersistentSeekButtonsChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .playerSwipeDownBack(
                gestureKey = "stream_settings",
                minDistanceDp = 70.dp,
                topZoneDp = 180.dp,
                onBack = onBack,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Drag-полоска (индикатор свайпа вниз)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(scheme.onSurfaceVariant.copy(alpha = 0.40f)),
            )
        }

        // Фиксированный Header
        Text(
            text = "Настройки воспроизведения",
            color = scheme.onBackground,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        PlayerSettingsSectionLabel("КАЧЕСТВО ВИДЕО")
        PlayerSettingsChipsRow(qualityOptions, selectedQuality, onQualitySelected)

        PlayerSettingsSectionLabel("ОЗВУЧКА РЕЛИЗА")
        PlayerSettingsChipsRow(audioOptions, selectedAudio, onAudioSelected)

        if (embeddedAudioOptions.isNotEmpty()) {
            PlayerSettingsSectionLabel("ВСТРОЕННЫЕ АУДИОДОРОЖКИ")
            PlayerSettingsChipsRow(
                options = embeddedAudioOptions,
                selected = selectedEmbeddedAudio ?: embeddedAudioOptions.first(),
                onSelected = onEmbeddedAudioSelected,
            )
        }

        PlayerSettingsSectionLabel("УПРАВЛЕНИЕ И ПЕРЕХОДЫ")
        PlayerSettingsToggleRow(
            title = "Автопереход к следующей серии",
            subtitle = "Автоматически воспроизводить следующую серию после окончания",
            checked = autoNextEnabled,
            onCheckedChange = onAutoNextChanged,
        )
        PlayerSettingsToggleRow(
            title = "Кнопки перемотки ±10 сек",
            subtitle = "Показывать кнопки быстрой перемотки в центре оверлея",
            checked = persistentSeekButtons,
            onCheckedChange = onPersistentSeekButtonsChanged,
        )
    }
}

@Composable
private fun PlayerSettingsChipsRow(
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(options) { option ->
            val isSelected = option == selected
            Surface(
                onClick = { onSelected(option) },
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MoviaBrandAmber.copy(alpha = 0.16f) else scheme.surfaceContainer,
                modifier = Modifier
                    .heightIn(min = 46.dp)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) MoviaBrandAmber else MoviaBorderSubtle,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .semantics { this.selected = isSelected },
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        color = if (isSelected) MoviaBrandAmber else scheme.onSurface.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSettingsToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = { onCheckedChange(!checked) },
        shape = RoundedCornerShape(14.dp),
        color = scheme.surfaceContainer.copy(alpha = 0.72f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = scheme.onSurface.copy(alpha = 0.64f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MoviaOnBrandAmber,
                    checkedTrackColor = MoviaBrandAmber,
                    uncheckedThumbColor = scheme.onSurface.copy(alpha = 0.74f),
                    uncheckedTrackColor = scheme.onSurface.copy(alpha = 0.18f),
                ),
            )
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
    val initialPage = (selectedSeason - 1).coerceIn(0, availableSeasons.size - 1)
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { availableSeasons.size })
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        val pageSeason = pagerState.currentPage + 1
        if (pageSeason != selectedSeason) {
            onSeasonSelected(pageSeason)
        }
    }

    LaunchedEffect(selectedSeason) {
        val targetPage = (selectedSeason - 1).coerceIn(0, availableSeasons.size - 1)
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    Column(
        modifier = modifier
            .background(scheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Фиксированная верхняя зона (Drag-полоска + Header со свайпом вниз)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSwipeDownBack(
                    gestureKey = "episodes_header",
                    minDistanceDp = 70.dp,
                    topZoneDp = 180.dp,
                    onBack = onBack,
                )
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Drag-полоска (индикатор свайпа вниз)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.40f)),
                )
            }

            // Фиксированный Header: [←]  Сезоны и серии  [ ✕ ]
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGlassAction(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Назад к плееру",
                )
                Text(
                    text = "Сезоны и серии",
                    color = scheme.onBackground,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                PlayerGlassAction(
                    onClick = onBack,
                    icon = Icons.Filled.Close,
                    contentDescription = "Закрыть",
                )
            }
        }

        // Горизонтальный ряд сезонов
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(availableSeasons) { season ->
                val isSelected = (pagerState.currentPage + 1) == season
                MoviaChoiceChip(
                    label = "$season сезон",
                    selected = isSelected,
                    onClick = {
                        coroutineScope.launch {
                            pagerState.scrollToPage(season - 1)
                        }
                    },
                )
            }
        }

        // Вертикальный список серий для выбранного сезона
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            val seasonNumber = page + 1
            val episodeCount = seasonEpisodeCounts.getOrNull(page) ?: 0
            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(listState, onBack) {
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
                            val isAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                            val minDistance = 70.dp.toPx()
                            if (isAtTop && totalY >= minDistance && totalY > abs(totalX) * 1.35f) {
                                onBack()
                            }
                        }
                    },
            ) {
                items(episodeCount) { index ->
                    val episode = index + 1
                    val selected = seasonNumber == currentSeason && episode == currentEpisode
                    Surface(
                        onClick = { onSelectEpisode(seasonNumber, episode) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) {
                            MoviaBrandAmber.copy(alpha = 0.14f)
                        } else {
                            scheme.surfaceContainer.copy(alpha = 0.58f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .border(
                                width = 1.dp,
                                color = if (selected) MoviaBrandAmber else MoviaBorderSubtle,
                                shape = RoundedCornerShape(14.dp),
                            ),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            // [Превью / Иконка воспроизведения]
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MoviaBrandAmber.copy(alpha = 0.22f) else scheme.surfaceContainerHigh.copy(alpha = 0.88f),
                                border = BorderStroke(1.dp, if (selected) MoviaBrandAmber.copy(alpha = 0.48f) else MoviaBorderSubtle),
                                modifier = Modifier.size(width = 48.dp, height = 38.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = if (selected) MoviaBrandAmber else scheme.onSurface.copy(alpha = 0.90f),
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }

                            // [Название и Описание серии]
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = "$episode. Эпизод $episode",
                                    color = if (selected) MoviaBrandAmber else scheme.onSurface,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Сезон $seasonNumber • Эпизод $episode",
                                    color = scheme.onSurfaceVariant.copy(alpha = 0.78f),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }

                            // [Длительность]
                            Text(
                                text = "45 мин",
                                color = if (selected) MoviaBrandAmber.copy(alpha = 0.90f) else scheme.onSurfaceVariant.copy(alpha = 0.70f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    contextLabel: String,
    locked: Boolean,
    onLockToggle: () -> Unit,
    isLandscape: Boolean,
    onBack: () -> Unit,
    onPictureInPicture: () -> Unit,
    onSettings: () -> Unit,
    onInteraction: () -> Unit,
    onEpisodes: (() -> Unit)? = null,
    lockButtonAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val animatedLockButtonAlpha = if (locked) lockButtonAlpha else 1f
    val horizontalPadding = if (isLandscape) 24.dp else 16.dp
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(horizontal = horizontalPadding, vertical = 8.dp),
    ) {
        if (locked) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                PlayerGlassAction(
                    onClick = onLockToggle,
                    icon = Icons.Filled.LockOpen,
                    contentDescription = "Разблокировать управление",
                    iconSize = 22.dp,
                    modifier = Modifier.alpha(animatedLockButtonAlpha),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerGlassAction(
                    onClick = { onInteraction(); onBack() },
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Назад",
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (onEpisodes != null) {
                                Modifier.clickable {
                                    onInteraction()
                                    onEpisodes()
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    Text(
                        text = title,
                        color = scheme.onSurface,
                        style = if (isLandscape) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    if (contextLabel.isNotBlank()) {
                        Text(
                            text = contextLabel,
                            color = scheme.onSurface.copy(alpha = 0.70f),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                PlayerGlassAction(
                    onClick = {
                        onInteraction()
                        onLockToggle()
                    },
                    icon = Icons.Filled.Lock,
                    contentDescription = "Заблокировать управление",
                    iconSize = 22.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                PlayerGlassAction(
                    onClick = {
                        onInteraction()
                        onPictureInPicture()
                    },
                    icon = Icons.Outlined.PictureInPictureAlt,
                    contentDescription = "Picture-in-Picture",
                    iconSize = 22.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                PlayerGlassAction(
                    onClick = onSettings,
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Настройки плеера",
                )
            }
        }
    }
}

@Composable
private fun PlayerGlassAction(
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = scheme.surfaceContainer.copy(alpha = 0.88f),
        modifier = modifier
            .size(44.dp)
            .border(1.dp, MoviaBorderSubtle, CircleShape),
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun PlayerQuickChip(
    label: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer.copy(alpha = 0.82f),
        modifier = Modifier
            .heightIn(min = 44.dp)
            .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(12.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon?.let {
                Icon(it, contentDescription = null, tint = scheme.onSurface.copy(alpha = 0.88f), modifier = Modifier.size(20.dp))
            }
            Text(
                text = label,
                color = scheme.onSurface.copy(alpha = 0.92f),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
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
    onOpenEpisodes: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    isLandscape: Boolean,
    isSeries: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val safeDuration = max(1L, durationMs)
    val thumbDiameter = if (isScrubbing) 18.dp else 14.dp
    val playedFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val remainingMs = max(0L, durationMs - positionMs)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTime(positionMs),
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.width(46.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
            ) {
                val safeMaxWidth = maxWidth.coerceAtLeast(0.dp)
                val trackInset = 5.dp
                val trackWidth = (safeMaxWidth - trackInset * 2f).coerceAtLeast(1.dp)
                val thumbCenterX = trackInset + trackWidth * playedFraction
                val previewWidth = 120.dp
                val maxPreviewX = (safeMaxWidth - previewWidth).coerceAtLeast(0.dp)
                val previewX = if (maxPreviewX <= 0.dp) 0.dp else (thumbCenterX - previewWidth / 2f).coerceIn(0.dp, maxPreviewX)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = "Позиция воспроизведения"
                            stateDescription = formatTime(positionMs) + " из " + formatTime(durationMs)
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
                                val trackInsetPx = trackInset.toPx()
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
                            val trackInsetPx = trackInset.toPx()
                            val trackStart = trackInsetPx
                            val trackEnd = (size.width - trackInsetPx).coerceAtLeast(trackStart)
                            val trackWidthPx = (trackEnd - trackStart).coerceAtLeast(1f)
                            val playedX = trackStart + trackWidthPx * playedFraction
                            val bufferedX = trackStart + trackWidthPx * bufferedFraction
                            val stroke = 4.5.dp.toPx()
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
                                contentDescription = "Предпросмотр кадра " + formatTime(positionMs),
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
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "−" + formatTime(remainingMs),
                color = Color.White.copy(alpha = 0.90f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.width(56.dp),
            )
            if (isSeries && onOpenEpisodes != null) {
                Spacer(modifier = Modifier.width(8.dp))
                PlayerGlassAction(
                    onClick = onOpenEpisodes,
                    icon = Icons.AutoMirrored.Outlined.PlaylistPlay,
                    contentDescription = "Сезоны и серии",
                    iconSize = 22.dp,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            PlayerGlassAction(
                onClick = onToggleFullscreen,
                icon = if (isLandscape) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                contentDescription = if (isLandscape) {
                    "Свернуть из полноэкранного режима"
                } else {
                    "Развернуть на весь экран"
                },
                iconSize = 22.dp,
            )
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
        rippleScale.snapTo(1.0f)
        rippleAlpha.snapTo(0.6f)
        delay(150L)
        rippleAlpha.snapTo(0f)
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
            color = scheme.background.copy(alpha = 0.80f),
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

private fun playerContextLabel(title: String, season: Int, episode: Int?): String {
    val episodeNumber = episode ?: return ""
    return "Сезон $season · Серия $episodeNumber"
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
