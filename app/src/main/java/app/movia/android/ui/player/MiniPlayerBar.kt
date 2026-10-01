package app.movia.android.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.movia.android.ui.components.MoviaTapIconButton
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaProgressTrack
import app.movia.android.ui.theme.MoviaSurfaceElevated
import app.movia.android.ui.theme.MoviaTextSecondary
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.rememberMoviaMediaCoverFeedback
import app.movia.android.ui.components.MoviaMediaCoverFrame
import app.movia.android.ui.components.moviaDisplayTitle

@Composable
fun MiniPlayerBar(
    session: PlaybackSession,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    mediaContent: MediaContent? = null,
) {
    val playback by session.state.collectAsStateWithLifecycle()
    if (!playback.hasMedia) return
    val coverFeedback = rememberMoviaMediaCoverFeedback(onOpen)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
            .clickable(onClick = coverFeedback.onClick),
        color = MoviaSurfaceElevated,
        tonalElevation = 6.dp,
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MoviaMediaCoverFrame(
                    feedback = coverFeedback,
                    modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    borderColor = Color.Transparent,
                ) {
                    MoviaArtwork(
                        url = mediaContent?.posterUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(
                        text = mediaContent?.title?.let(::moviaDisplayTitle)
                            ?: moviaDisplayTitle(playback.displayTitle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val episodeLabel = listOfNotNull(
                        playback.seasonNumber?.let { "S$it" },
                        playback.episodeNumber?.let { "E$it" },
                    ).joinToString(" · ")
                    if (episodeLabel.isNotBlank()) {
                        Text(
                            text = episodeLabel,
                            color = MoviaTextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                        )
                    }
                }
                MoviaTapIconButton(
                    icon = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playback.isPlaying) "Пауза" else "Продолжить",
                    onClick = { session.togglePlayPause() },
                    modifier = Modifier.size(48.dp),
                    iconModifier = Modifier.size(if (playback.isPlaying) 24.dp else 26.4.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                MoviaTapIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Закрыть плеер",
                    onClick = onClose,
                    modifier = Modifier.size(48.dp),
                    iconModifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                    actionDelayMs = 500L,
                )
            }
            LinearProgressIndicator(
                progress = { playback.percentageWatched.coerceIn(0f, 1f) },
                color = MoviaBrandAmber,
                trackColor = MoviaProgressTrack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
            )
        }
    }
}
