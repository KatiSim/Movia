package app.movia.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaFontFamily
import app.movia.android.ui.theme.MoviaTextSecondary

@Composable
fun MediaMetadataText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = MoviaFontFamily,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun MediaMetadataRow(
    item: MediaContent,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 12.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = 16.sp,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    val rating = (item.imdbRating ?: item.rating)?.let(::moviaRatingLabel)
    val genreOrType = moviaPrimaryGenre(item)?.takeIf { it.isNotBlank() }
        ?: moviaContentTypeLabel(item).takeIf { it.isNotBlank() }
    val year = moviaYearLabel(item.year)
    val parts = listOfNotNull(rating?.let { "rating" to it }, genreOrType?.let { "fact" to it }, year?.let { "fact" to it })
    if (parts.isEmpty()) return
    Text(
        text = buildAnnotatedString {
            parts.forEachIndexed { index, (kind, value) ->
                if (index > 0) withStyle(SpanStyle(color = MoviaTextSecondary)) { append(" · ") }
                if (kind == "rating") withStyle(SpanStyle(color = MoviaBrandAmber, fontWeight = FontWeight.SemiBold)) { append("★ $value") }
                else withStyle(SpanStyle(color = MoviaTextSecondary)) { append(value) }
            }
        },
        modifier = modifier,
        color = MoviaTextSecondary,
        fontFamily = MoviaFontFamily,
        fontSize = fontSize,
        lineHeight = lineHeight,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
