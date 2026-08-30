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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import java.util.Locale

/**
 * Authoritative Movia media-card hierarchy for all ordinary movie/series/TV tiles:
 * poster -> title -> explicit metadata: rating • year • content type, then genres.
 * Artwork is loaded through the shared MoviaArtwork memory/disk cache.
 */
@Composable
fun MediaContentCard(
    item: MediaContent,
    modifier: Modifier = Modifier,
    posterShape: Shape = RoundedCornerShape(14.dp),
    posterBorder: Color = MoviaBorderSubtle,
    titleFontSize: TextUnit = 16.sp,
    onClick: () -> Unit,
) {
    val typeLabel = moviaContentTypeLabel(item)
    val mainGenre = moviaPrimaryGenre(item)
    val genreOrType = mainGenre ?: typeLabel
    val country = item.country.takeIf { it.isNotBlank() }
    val metadataFacts = listOfNotNull(
        item.year.takeIf { it > 0 }?.toString(),
        country,
        genreOrType,
    ).joinToString(" • ")

    val showRating = item.rating > 0.0
    val ratingLabel = if (showRating) {
        String.format(Locale.US, "%.1f", item.rating)
    } else {
        null
    }

    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    item.title,
                    ratingLabel?.let { "★ $it" },
                    metadataFacts.takeIf { it.isNotBlank() },
                ).joinToString(". ")
            },
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(posterShape)
                .border(1.dp, posterBorder, posterShape),
        ) {
            MoviaArtwork(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
            )

            if (!item.playbackUrl.isNullOrBlank() || item.streams.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(topStart = 0.dp, bottomEnd = 8.dp),
                    color = Color.Black.copy(alpha = 0.72f),
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(
                        text = "🎬",
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
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

        if (ratingLabel != null || metadataFacts.isNotBlank()) {
            Text(
                text = buildAnnotatedString {
                    ratingLabel?.let { label ->
                        withStyle(
                            SpanStyle(
                                color = MoviaBrandAmber,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append("★ $label")
                        }
                        if (metadataFacts.isNotBlank()) append(" • ")
                    }
                    append(metadataFacts)
                },
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
