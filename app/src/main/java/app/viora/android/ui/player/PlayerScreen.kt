package app.viora.android.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
private val AUDIO_OPTIONS = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val QUALITY_OPTIONS = listOf("Auto", "4K", "1080p", "720p", "480p")
private val RESIZE_MODES = listOf(
    "Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "Zoom" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    "Fill" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
)

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
    val offline = session.activeSource?.startsWith("file:") == true
    val resizeMode = RESIZE_MODES[resizeIndex].second

    LaunchedEffect(speed) {
        player.playbackParameters = PlaybackParameters(speed)
    }

    LaunchedEffect(subtitlesEnabled) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitlesEnabled)
            .build()
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
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
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
                    useController = true
                    controllerShowTimeoutMs = 3000
                    this.player = player
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
                }
            },
            update = {
                it.player = player
                it.resizeMode = resizeMode
                it.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )

        // Keep all top actions inside status-bar / cutout safe areas.
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(
                onClick = leavePlayer,
                modifier = Modifier.align(Alignment.TopStart),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Свернуть плеер",
                    tint = Color.White,
                )
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(start = 64.dp, end = 112.dp, top = 12.dp),
            )

            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                IconButton(
                    onClick = {
                        activity?.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        }
                    },
                    enabled = activity != null,
                ) {
                    Icon(
                        Icons.Outlined.ScreenRotation,
                        contentDescription = if (isLandscape) {
                            "Переключить в портретный режим"
                        } else {
                            "Развернуть в альбомный режим"
                        },
                        tint = Color.White,
                    )
                }

                IconButton(
                    onClick = {
                        activity?.enterPictureInPictureMode(
                            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                        )
                    },
                    enabled = activity != null,
                ) {
                    Icon(
                        Icons.Outlined.PictureInPictureAlt,
                        contentDescription = "Картинка в картинке",
                        tint = Color.White,
                    )
                }
            }
        }

        IconButton(
            onClick = { settingsOpen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 8.dp, bottom = 8.dp),
        ) {
            Icon(
                Icons.Outlined.Settings,
                contentDescription = "Настройки плеера",
                tint = Color.White,
            )
        }

        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                title = { Text("Настройки плеера") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            text = "${if (offline) "Офлайн · " else ""}$preferredAudio · $preferredQuality",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        PlayerSettingRow(
                            title = "Озвучка",
                            options = AUDIO_OPTIONS,
                            selected = preferredAudio,
                            onSelect = onAudioSelected,
                        )
                        PlayerSettingRow(
                            title = "Качество",
                            options = QUALITY_OPTIONS,
                            selected = preferredQuality,
                            onSelect = onQualitySelected,
                        )
                        PlayerSettingRow(
                            title = "Скорость",
                            options = SPEEDS.map { "${formatSpeed(it)}×" },
                            selected = "${formatSpeed(speed)}×",
                            onSelect = { value ->
                                speed = value.removeSuffix("×").toFloatOrNull() ?: 1f
                            },
                        )
                        PlayerSettingRow(
                            title = "Масштаб",
                            options = RESIZE_MODES.map { it.first },
                            selected = RESIZE_MODES[resizeIndex].first,
                            onSelect = { value ->
                                resizeIndex = RESIZE_MODES.indexOfFirst { it.first == value }.coerceAtLeast(0)
                            },
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Субтитры", modifier = Modifier.weight(1f))
                            FilterChip(
                                selected = subtitlesEnabled,
                                onClick = { onSubtitlesChanged(!subtitlesEnabled) },
                                label = { Text(if (subtitlesEnabled) "Включены" else "Выключены") },
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { settingsOpen = false }) { Text("Готово") }
                },
            )
        }

        playbackError?.let { error ->
            Text(
                text = "Ошибка воспроизведения: $error",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
    }
}


@Composable
private fun PlayerSettingRow(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
