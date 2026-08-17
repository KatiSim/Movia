package app.viora.android.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Rational
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
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
import kotlinx.coroutines.delay
import kotlin.math.max
import java.util.Locale

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

    var playbackError by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var resizeIndex by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    var subtitlePickerOpen by remember { mutableStateOf(false) }
    var subtitleTracks by remember { mutableStateOf(buildSubtitleTrackOptions(player.currentTracks)) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var positionMs by remember { mutableLongStateOf(max(0L, player.currentPosition)) }
    var durationMs by remember { mutableLongStateOf(max(0L, player.duration.takeIf { it > 0L } ?: 0L)) }
    var scrubbing by remember { mutableStateOf(false) }
    var seekFeedbackDirection by remember { mutableIntStateOf(0) }
    var seekFeedbackTick by remember { mutableIntStateOf(0) }

    val resizeMode = RESIZE_MODES[resizeIndex].second
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun showControls() {
        controlsVisible = true
        interactionTick++
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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    this.player = player
                }
            },
            update = {
                it.player = player
                it.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            if (controlsVisible) interactionTick++
                        },
                        onDoubleTap = { offset ->
                            val direction = if (offset.x < size.width / 2f) -1 else 1
                            val target = (player.currentPosition + direction * 10_000L)
                                .coerceIn(0L, max(0L, player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE))
                            player.seekTo(target)
                            positionMs = target
                            seekFeedbackDirection = direction
                            seekFeedbackTick++
                            showControls()
                        },
                    )
                },
        )

        if (controlsVisible || settingsOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.62f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.76f),
                            ),
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
                color = Color.Black.copy(alpha = 0.72f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(84.dp),
            ) {
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
                        modifier = Modifier.size(48.dp),
                    )
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
                SeekFeedbackBubble(forward = false)
            }

            AnimatedVisibility(
                visible = seekFeedbackDirection > 0,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 48.dp),
            ) {
                SeekFeedbackBubble(forward = true)
            }

            PlayerTimeline(
                positionMs = positionMs,
                durationMs = durationMs,
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (settingsOpen) {
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

        playbackError?.let { error ->
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
    durationMs: Long,
    onScrub: (Long) -> Unit,
    onScrubFinished: () -> Unit,
    onSettings: () -> Unit,
    isLandscape: Boolean,
    activity: Activity?,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeDuration = max(1L, durationMs)
    Surface(
        color = Color.Black.copy(alpha = 0.40f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 2.dp),
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
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Outlined.ScreenRotation,
                        contentDescription = if (isLandscape) {
                            "Переключить в портретный режим"
                        } else {
                            "Развернуть в альбомный режим"
                        },
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconButton(
                    onClick = {
                        onInteraction()
                        activity?.enterPictureInPictureMode(
                            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                        )
                    },
                    enabled = activity != null,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Outlined.PictureInPictureAlt,
                        contentDescription = "Картинка в картинке",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTime(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(14.dp))
                Slider(
                    value = positionMs.coerceIn(0L, safeDuration).toFloat(),
                    onValueChange = { onScrub(it.toLong()) },
                    onValueChangeFinished = onScrubFinished,
                    valueRange = 0f..safeDuration.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.34f),
                    ),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    },
                    track = { sliderState ->
                        SliderDefaults.Track(
                            sliderState = sliderState,
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.34f),
                            ),
                            drawStopIndicator = null,
                        )
                    },
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = formatTime(durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(
                    onClick = onSettings,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = "Настройки плеера",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
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
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = if (enabled) 0.10f else 0.06f),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Субтитры",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (enabled) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                        maxLines = 1,
                    )
                }
            }
            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.80f),
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.White.copy(alpha = 0.10f),
                ) {
                    Text(
                        text = "Нет",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
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
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
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
                    color = MaterialTheme.colorScheme.primary,
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
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Box(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
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
            style = MaterialTheme.typography.titleMedium,
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
            MaterialTheme.colorScheme.primary
        } else {
            Color.White.copy(alpha = 0.10f)
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
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
private fun SeekFeedbackBubble(forward: Boolean) {
    Surface(
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.64f),
        modifier = Modifier.size(92.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10,
                contentDescription = if (forward) "Перемотано вперёд на 10 секунд" else "Перемотано назад на 10 секунд",
                tint = Color.White,
                modifier = Modifier.size(42.dp),
            )
        }
    }
}

private fun displayPlayerTitle(title: String): String =
    title
        .replace(Regex(" · Эпизод \\d+$"), "")
        .trim()
        .trimEnd('·')
        .trim()

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
