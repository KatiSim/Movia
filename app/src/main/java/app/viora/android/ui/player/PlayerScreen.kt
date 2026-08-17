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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
                }
            },
            update = {
                it.player = player
                it.resizeMode = resizeMode
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
                    .padding(start = 112.dp, end = 112.dp, top = 12.dp),
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

        // Custom options live above Media3's own transport/timeline controls.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = 16.dp, bottom = 104.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Предпочтение: $preferredAudio · $preferredQuality${if (offline) " · офлайн" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {
                        val index = SPEEDS.indexOf(speed).takeIf { it >= 0 } ?: 1
                        speed = SPEEDS[(index + 1) % SPEEDS.size]
                    },
                    label = { Text("${formatSpeed(speed)}×") },
                )
                AssistChip(
                    onClick = { resizeIndex = (resizeIndex + 1) % RESIZE_MODES.size },
                    label = { Text(RESIZE_MODES[resizeIndex].first) },
                )
                FilterChip(
                    selected = subtitlesEnabled,
                    onClick = { onSubtitlesChanged(!subtitlesEnabled) },
                    label = { Text("CC") },
                )
            }
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

private fun formatSpeed(speed: Float): String = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
