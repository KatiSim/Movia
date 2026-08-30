package app.movia.android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaRatingBadgeBackground
import java.util.Locale

/**
 * Authoritative Movia media-card hierarchy for all ordinary movie/series/TV tiles:
 * poster + optional rating -> title -> year • age -> duration/live state.
 * Artwork is loaded through the shared MoviaArtwork memory/disk cache.
 */
@Composable
fun MediaContentCard(
    item: MediaContent,
    modifier: Modifier = Modifier,
    posterShape: Shape = RoundedCornerShape(12.dp),
    titleFontSize: TextUnit = 15.sp,
    onClick: () -> Unit,
) {
    val facts = listOfNotNull(
        item.year.takeIf { it > 0 }?.toString(),
        item.ageRating.takeIf { it > 0 }?.let { "$it+" },
        item.durationMinutes.takeIf { it > 0 }?.let { formatDuration(it) },
    ).joinToString(" • ")
    val ratingBadge = item.rating.takeIf { it > 0.0 }?.let { String.format(Locale.US, "★ %.1f", it) }
    val genres = item.genres
        .filter { it.isNotBlank() && !it.equals("Фильмы", true) && !it.equals("Сериалы", true) }
        .take(3)
        .joinToString(" • ")
    val duration = when {
        item.type == ContentType.TV -> "Прямой эфир"
        item.durationMinutes <= 0 -> null
        item.durationMinutes >= 60 -> {
            val hours = item.durationMinutes / 60
            val minutes = item.durationMinutes % 60
            if (minutes == 0) "$hours ч" else "$hours ч $minutes мин"
        }
        else -> "${item.durationMinutes} мин"
    }

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    item.title,
                    ratingBadge,
                    facts.takeIf { it.isNotBlank() },
                    duration,
                ).joinToString(". ")
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(posterShape)
                .border(1.dp, MoviaBorderSubtle, posterShape),
        ) {
            MoviaArtwork(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
            )

            if (ratingBadge != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MoviaRatingBadgeBackground,
                    contentColor = MoviaBrandAmber,
                    border = BorderStroke(1.dp, MoviaBrandAmber),
                ) {
                    Text(
                        text = ratingBadge,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }

        Text(
            text = item.title,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = titleFontSize,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (facts.isNotBlank()) {
            Text(
                text = facts,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (genres.isNotBlank()) {
            Text(
                text = genres,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatDuration(minutes: Int): String {
    if (minutes < 60) return "$minutes мин"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0) "$hours ч" else "$hours ч $rest мин"
}

@Composable
fun MediaCard(
    item: MediaContent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MediaContentCard(
        item = item,
        modifier = modifier.width(152.dp),
        onClick = onClick,
    )
}
