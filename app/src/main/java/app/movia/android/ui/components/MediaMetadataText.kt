package app.movia.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import app.movia.android.ui.theme.MoviaMetadataText

@Composable
fun MediaMetadataText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    MoviaFadeOverflowText(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(
            fontFamily = MoviaFontFamily,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MoviaMetadataText,
        ),
        maxLines = maxLines,
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
    val full = listOfNotNull(rating?.let { "rating" to it }, genreOrType?.let { "fact" to it }, year?.let { "fact" to it })
    if (full.isEmpty()) return
    val variants = remember(rating, genreOrType, year) {
        listOf(
            full,
            listOfNotNull(rating?.let { "rating" to it }, genreOrType?.let { "fact" to it }),
            listOfNotNull(rating?.let { "rating" to it }, year?.let { "fact" to it }),
        ).filter { it.isNotEmpty() }.distinct()
    }
    var variantIndex by remember(rating, genreOrType, year) { mutableIntStateOf(0) }
    val parts = variants[variantIndex.coerceAtMost(variants.lastIndex)]
    MoviaFadeOverflowText(
        text = buildAnnotatedString {
            parts.forEachIndexed { index, (kind, value) ->
                if (index > 0) withStyle(SpanStyle(color = MoviaMetadataText)) { append(" · ") }
                if (kind == "rating") withStyle(SpanStyle(color = MoviaBrandAmber, fontWeight = FontWeight.SemiBold)) { append("★ $value") }
                else withStyle(SpanStyle(color = MoviaMetadataText)) { append(value) }
            }
        },
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall.copy(
            color = MoviaMetadataText,
            fontFamily = MoviaFontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontWeight = fontWeight,
            textAlign = textAlign,
        ),
        maxLines = maxLines,
        onOverflowChanged = { overflowed ->
            if (overflowed && variantIndex < variants.lastIndex) {
                variantIndex += 1
            }
        },
    )
}
