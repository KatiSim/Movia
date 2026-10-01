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
import androidx.compose.ui.platform.testTag
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
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import app.movia.android.ui.theme.MoviaTextTertiary
import app.movia.android.ui.theme.MoviaTextTertiary

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
    val metadataFacts = moviaCardMetadataFacts(item).joinToString(" · ")
    val ratingLabel = moviaRatingLabel(item.imdbRating ?: item.rating)
    val displayTitle = moviaDisplayTitle(item.title)
    val resolvedTitleLineHeight = if (titleFontSize <= 14.sp) 18.sp else 22.sp
    val coverFeedback = rememberMoviaMediaCoverFeedback(onClick)

    Column(
        modifier = modifier
            .clickable(onClick = coverFeedback.onClick)
            .testTag("media_card")
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(
                    displayTitle,
                    ratingLabel?.let { "★ $it" },
                    metadataFacts.takeIf { it.isNotBlank() },
                ).joinToString(". ")
            },
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        MoviaMediaCoverFrame(
            feedback = coverFeedback,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = posterShape,
            borderColor = posterBorder,
        ) {
            MoviaArtwork(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
            )
        }

        MoviaFadeOverflowText(
            text = displayTitle,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = titleFontSize,
                lineHeight = resolvedTitleLineHeight,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 2,
        )

        if (ratingLabel != null || metadataFacts.isNotBlank()) {
            MediaMetadataRow(
                item = item,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 12.sp,
                lineHeight = 16.sp,
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
