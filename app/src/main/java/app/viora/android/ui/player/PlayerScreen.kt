@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.viora.android.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.media.AudioManager
import android.provider.Settings
import android.os.SystemClock
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.viora.android.R
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale
import app.viora.android.ui.theme.VioraBrandAmber
import app.viora.android.ui.theme.VioraOnBrandAmber

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val AUDIO_OPTIONS = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val QUALITY_OPTIONS = listOf("Auto", "4K", "1080p", "720p", "480p")
private val RESIZE_MODES = listOf(
    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

private data class SubtitleTrackOption(
    val label: String,
    val override: TrackSelectionOverride,
    val selected: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    session: PlaybackSession,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    preferredAudio: String = "Auto",
    preferredQuality: String = "Auto",
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    subtitlesEnabled: Boolean = false,
    autoNextEnabled: Boolean = true,
    onSubtitlesChanged: (Boolean) -> Unit,
    onNextEpisode: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val player = session.player
    val inPictureInPicture = VioraPiPState.isInPictureInPicture

    // The player owns an immersive fullscreen window while it is visible.
    // Restore system bars when the composable leaves so the rest of Viora behaves normally.
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
    var resizeIndex by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var subtitleTracks by remember { mutableStateOf(buildSubtitleTrackOptions(player.currentTracks)) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(max(0L, player.currentPosition)) }
    var bufferedPositionMs by remember { mutableLongStateOf(max(0L, player.bufferedPosition)) }
    var durationMs by remember { mutableLongStateOf(max(0L, player.duration.takeIf { it > 0L } ?: 0L)) }
    var scrubbing by remember { mutableStateOf(false) }
    var seekFeedbackDirection by remember { mutableIntStateOf(0) }
    var seekFeedbackTick by remember { mutableIntStateOf(0) }
    var gestureFeedbackLabel by remember { mutableStateOf<String?>(null) }
    var gestureFeedbackPercent by remember { mutableIntStateOf(0) }
    var gestureFeedbackTick by remember { mutableIntStateOf(0) }

    val resizeMode = RESIZE_MODES[resizeIndex].second
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun showControls() {
        if (!inPictureInPicture) {
            controlsVisible = true
            interactionTick++
        }
    }

    fun updateSourceRect(view: PlayerView) {
        val rect = Rect()
        val sourceView = view.videoSurfaceView ?: view
        if (sourceView.getGlobalVisibleRect(rect) && !rect.isEmpty) {
            sourceRectHint = rect
        }
    }

    LaunchedEffect(inPictureInPicture) {
        if (inPictureInPicture) {
            controlsVisible = false
            settingsOpen = false
            subtitlePickerOpen = false
        }
    }

    LaunchedEffect(activity, sourceRectHint, session.isPlaying, session.playWhenReady, title) {
        activity?.setPictureInPictureParams(
            buildVioraPictureInPictureParams(
                context = context,
                sourceRectHint = sourceRectHint,
                isPlaying = session.isPlaying,
                title = displayPlayerTitle(title),
                autoEnter = session.playWhenReady,
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

    LaunchedEffect(player, title) {
        while (true) {
            if (!scrubbing) {
                positionMs = max(0L, player.currentPosition)
            }
            bufferedPositionMs = max(0L, player.bufferedPosition)
            durationMs = max(0L, player.duration.takeIf { it > 0L } ?: durationMs)
            delay(250L)
        }
    }

    LaunchedEffect(controlsVisible, interactionTick, session.isPlaying, settingsOpen) {
        if (controlsVisible && session.isPlaying && !settingsOpen) {
            delay(3_500L)
            controlsVisible = false
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
                subtitleTracks = buildSubtitleTrackOptions(tracks)
                showControls()
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(subtitlePickerOpen, subtitleTracks.isEmpty()) {
        if (subtitlePickerOpen && subtitleTracks.isEmpty()) {
            subtitlePickerOpen = false
        }
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
    val subtitleSummary = when {
        subtitleTracks.isEmpty() -> "Нет"
        !subtitlesEnabled -> "Выкл"
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
        } else {
            builder
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(option.override)
            onSubtitlesChanged(true)
        }
        player.trackSelectionParameters = builder.build()
        subtitlePickerOpen = false
        showControls()
    }

    fun selectAutomaticSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        onSubtitlesChanged(true)
        subtitlePickerOpen = false
        showControls()
    }

    val leavePlayer: () -> Unit = {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onBack()
    }

    BackHandler(onBack = leavePlayer)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        Color.Black,
                    ),
                ),
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
        ) {
            Text(
                text = "Viora",
                color = VioraBrandAmber,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = displayPlayerTitle(title),
                color = Color.White.copy(alpha = 0.84f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
        }

        AndroidView(
            factory = { viewContext ->
                (LayoutInflater.from(viewContext)
                    .inflate(
                        R.layout.view_viora_player,
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
                    .background(Color.Black.copy(alpha = 0.42f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pipProgress)
                        .background(VioraBrandAmber),
                )
            }
        }

        if (!inPictureInPicture) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(activity) {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        var lastTapAt = 0L
                        var lastTapX = 0f
                        var lastTapY = 0f
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startX = down.position.x
                            val startY = down.position.y
                            var previousX = startX
                            var previousY = startY
                            var totalX = 0f
                            var totalY = 0f
                            var verticalDrag = false
                            val brightnessGesture = startX < size.width / 2f

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                val dx = change.position.x - previousX
                                val dy = change.position.y - previousY
                                totalX += dx
                                totalY += dy

                                if (!verticalDrag &&
                                    abs(totalY) > viewConfiguration.touchSlop &&
                                    abs(totalY) > abs(totalX)
                                ) {
                                    verticalDrag = true
                                }

                                if (verticalDrag && change.pressed) {
                                    change.consume()
                                    val delta = -dy / size.height.toFloat()
                                    if (brightnessGesture) {
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
                                    if (!verticalDrag) {
                                        val now = SystemClock.uptimeMillis()
                                        val closeToLastTap =
                                            abs(startX - lastTapX) < size.width * 0.28f &&
                                                abs(startY - lastTapY) < size.height * 0.22f
                                        if (now - lastTapAt <= 320L && closeToLastTap) {
                                            val direction = if (startX < size.width / 2f) -1 else 1
                                            val target = (player.currentPosition + direction * 10_000L)
                                                .coerceIn(
                                                    0L,
                                                    max(
                                                        0L,
                                                        player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE,
                                                    ),
                                                )
                                            player.seekTo(target)
                                            positionMs = target
                                            seekFeedbackDirection = direction
                                            seekFeedbackTick++
                                            lastTapAt = 0L
                                            showControls()
                                        } else {
                                            controlsVisible = !controlsVisible
                                            if (controlsVisible) interactionTick++
                                            lastTapAt = now
                                            lastTapX = startX
                                            lastTapY = startY
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    },
            )
        }


        if (!inPictureInPicture && (controlsVisible || settingsOpen)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.36f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.40f)),
                        ),
                    ),
            )

            PlayerTopBar(
                title = displayPlayerTitle(title),
                onBack = leavePlayer,
                onInteraction = ::showControls,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            Surface(
                color = Color.Black.copy(alpha = 0.62f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp),
            ) {
                if (session.playbackState == Player.STATE_BUFFERING) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = 0.20f),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            session.togglePlayPause()
                            showControls()
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            if (session.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (session.isPlaying) "Пауза" else "Воспроизвести",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = seekFeedbackDirection < 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 48.dp),
            ) {
                SeekFeedbackBubble(forward = false, pulseKey = seekFeedbackTick)
            }

            AnimatedVisibility(
                visible = seekFeedbackDirection > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp),
            ) {
                SeekFeedbackBubble(forward = true, pulseKey = seekFeedbackTick)
            }

            gestureFeedbackLabel?.let { label ->
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Text(
                        text = "$label · $gestureFeedbackPercent%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }

            PlayerTimeline(
                positionMs = positionMs,
                bufferedPositionMs = bufferedPositionMs,
                durationMs = durationMs,
                isScrubbing = scrubbing,
                onScrub = { value ->
                    scrubbing = true
                    positionMs = value
                    showControls()
                },
                onScrubFinished = {
                    player.seekTo(positionMs)
                    scrubbing = false
                    showControls()
                },
                onSettings = {
                    settingsOpen = true
                    showControls()
                },
                isLandscape = isLandscape,
                activity = activity,
                onInteraction = ::showControls,
                onEnterPictureInPicture = {
                    activity?.let { host ->
                        controlsVisible = false
                        settingsOpen = false
                        val params = buildVioraPictureInPictureParams(
                            context = context,
                            sourceRectHint = sourceRectHint,
                            isPlaying = session.isPlaying,
                            title = displayPlayerTitle(title),
                            autoEnter = session.playWhenReady,
                        )
                        host.setPictureInPictureParams(params)
                        host.enterPictureInPictureMode(params)
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isLandscape) 0.dp else 96.dp),
            )
        }

        if (!inPictureInPicture && settingsOpen) {
            ModalBottomSheet(
                onDismissRequest = {
                    settingsOpen = false
                    subtitlePickerOpen = false
                    showControls()
                },
                sheetState = sheetState,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    if (subtitlePickerOpen) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            IconButton(
                                onClick = { subtitlePickerOpen = false },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Назад к настройкам плеера",
                                )
                            }
                            Text(
                                text = "Субтитры",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        SubtitleTrackRow(
                            label = "Выкл",
                            selected = !subtitlesEnabled,
                            onClick = { selectSubtitleTrack(null) },
                        )

                        SubtitleTrackRow(
                            label = "Авто",
                            selected = subtitlesEnabled && selectedSubtitleLabel == null,
                            onClick = ::selectAutomaticSubtitles,
                        )
                        subtitleTracks.forEach { option ->
                            SubtitleTrackRow(
                                label = option.label,
                                selected = subtitlesEnabled && option.selected,
                                onClick = { selectSubtitleTrack(option) },
                            )
                        }
                    } else {
                        Text(
                            text = "Настройки плеера",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                        )

                        ScrollablePlayerSettingRow(
                            title = "Озвучка",
                            options = AUDIO_OPTIONS,
                            selected = preferredAudio,
                            onSelect = onAudioSelected,
                        )

                        ScrollablePlayerSettingRow(
                            title = "Качество",
                            options = QUALITY_OPTIONS,
                            selected = preferredQuality,
                            onSelect = onQualitySelected,
                        )

                        ScrollablePlayerSettingRow(
                            title = "Скорость",
                            options = SPEEDS.map { "${formatSpeed(it)}×" },
                            selected = "${formatSpeed(speed)}×",
                            onSelect = { value ->
                                speed = value.removeSuffix("×").toFloatOrNull() ?: 1f
                            },
                        )

                        PlayerSettingGrid(
                            title = "Масштаб",
                            options = RESIZE_MODES.map { it.first },
                            selected = RESIZE_MODES[resizeIndex].first,
                            onSelect = { value ->
                                resizeIndex = RESIZE_MODES.indexOfFirst { it.first == value }.coerceAtLeast(0)
                            },
                        )

                        SubtitleSelectorRow(
                            value = subtitleSummary,
                            enabled = subtitleTracks.isNotEmpty(),
                            onClick = { subtitlePickerOpen = true },
                        )
                    }
                }
            }
        }

        if (!inPictureInPicture) playbackError?.let { error ->
            Text(
                text = "Ошибка воспроизведения: $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 84.dp),
            )
        }
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        IconButton(
            onClick = {
                onInteraction()
                onBack()
            },
            modifier = Modifier
                .align(Alignment.TopStart)
                .size(56.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Свернуть плеер",
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(start = 72.dp, end = 72.dp, top = 16.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTimeline(
    positionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    isScrubbing: Boolean,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onSettings: () -> Unit,
    isLandscape: Boolean,
    activity: Activity?,
    onInteraction: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDuration = max(1L, durationMs)
    val horizontalSafePadding = if (isLandscape) 24.dp else 16.dp
    val thumbSize by animateDpAsState(
        targetValue = if (isScrubbing) 18.dp else 12.dp,
        animationSpec = tween(durationMillis = 120),
        label = "playerScrubberThumb",
    )
    val playedFraction = (positionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val bufferedFraction = (bufferedPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val remainingMs = max(0L, durationMs - positionMs)

    Surface(
        color = Color.Black.copy(alpha = 0.26f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = horizontalSafePadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "−${formatTime(remainingMs)}",
                    color = Color.White.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isScrubbing) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.78f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .align(Alignment.TopCenter),
                    ) {
                        Text(
                            text = formatTime(positionMs),
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }

                Slider(
                    value = positionMs.coerceIn(0L, safeDuration).toFloat(),
                    onValueChange = { onScrub(it.toLong()) },
                    onValueChangeFinished = onScrubFinished,
                    valueRange = 0f..safeDuration.toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Позиция воспроизведения"
                            stateDescription = "${formatTime(positionMs)} из ${formatTime(durationMs)}"
                        },
                    colors = SliderDefaults.colors(
                        thumbColor = VioraBrandAmber,
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(thumbSize)
                                .background(VioraBrandAmber, CircleShape),
                        )
                    },
                    track = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .drawBehind {
                                    val y = size.height / 2f
                                    val stroke = 4.dp.toPx()
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.20f),
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = stroke,
                                        cap = StrokeCap.Round,
                                    )
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.42f),
                                        start = Offset(0f, y),
                                        end = Offset(size.width * bufferedFraction, y),
                                        strokeWidth = stroke,
                                        cap = StrokeCap.Round,
                                    )
                                    drawLine(
                                        color = VioraBrandAmber,
                                        start = Offset(0f, y),
                                        end = Offset(size.width * playedFraction, y),
                                        strokeWidth = stroke,
                                        cap = StrokeCap.Round,
                                    )
                                },
                        )
                    },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        onInteraction()
                        activity?.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    },
                    enabled = activity != null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Outlined.ScreenRotation,
                        contentDescription = if (isLandscape) {
                            "Переключить в портретный режим"
                        } else {
                            "Развернуть в альбомный режим"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(
                    onClick = {
                        onInteraction()
                        onEnterPictureInPicture()
                    },
                    enabled = activity != null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Outlined.PictureInPictureAlt,
                        contentDescription = "Картинка в картинке",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Настройки плеера",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleSelectorRow(
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = if (enabled) 0.15f else 0.09f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .alpha(if (enabled) 1f else 0.38f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = if (enabled) 0.12f else 0.07f),
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.ClosedCaption,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.94f else 0.62f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Субтитры",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.72f),
                )
                Text(
                    text = if (enabled) value else "Нет доступных дорожек",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.82f else 0.66f),
                    maxLines = 1,
                )
            }
            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.12f),
                ) {
                    Text(
                        text = "Нет",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.76f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleTrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            VioraBrandAmber.copy(alpha = 0.18f)
        } else {
            Color.White.copy(alpha = 0.08f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "✓",
                    color = VioraBrandAmber,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

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
private fun ScrollablePlayerSettingRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val fadeColor = MaterialTheme.colorScheme.surface
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(options) { option ->
                    VioraChoiceChip(
                        label = option,
                        selected = option == selected,
                        onClick = { onSelect(option) },
                    )
                }
            }
            if (listState.canScrollBackward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(18.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(fadeColor, Color.Transparent),
                            ),
                        ),
                )
            }
            if (listState.canScrollForward) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(22.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, fadeColor),
                            ),
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerSettingGrid(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            options.forEach { option ->
                VioraChoiceChip(
                    label = option,
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun VioraChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
            VioraBrandAmber
        } else {
            Color.White.copy(alpha = 0.16f)
        },
        contentColor = if (selected) {
            VioraOnBrandAmber
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.96f)
        },
        modifier = Modifier
            .widthIn(min = 56.dp)
            .heightIn(min = 48.dp)
            .semantics { this.selected = selected },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
) {
    val rippleScale = remember { Animatable(0.72f) }
    val rippleAlpha = remember { Animatable(0.60f) }
    LaunchedEffect(pulseKey) {
        rippleScale.snapTo(0.72f)
        rippleAlpha.snapTo(0.60f)
        rippleScale.animateTo(1.12f, animationSpec = tween(320))
        rippleAlpha.animateTo(0f, animationSpec = tween(180))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(116.dp),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .graphicsLayer {
                    scaleX = rippleScale.value
                    scaleY = rippleScale.value
                    alpha = rippleAlpha.value
                }
                .border(2.dp, Color.White.copy(alpha = 0.52f), CircleShape),
        )
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.66f),
            modifier = Modifier.size(76.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10,
                    contentDescription = if (forward) {
                        "Перемотано вперёд на 10 секунд"
                    } else {
                        "Перемотано назад на 10 секунд"
                    },
                    tint = Color.White,
                    modifier = Modifier.size(38.dp),
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
