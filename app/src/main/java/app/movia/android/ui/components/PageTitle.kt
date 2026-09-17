package app.movia.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * Authoritative top-level Movia page title.
 * Use this for primary screen headings so Search, Catalog, Library and Profile
 * keep one typographic hierarchy instead of drifting independently.
 */
@Composable
fun MoviaPageTitle(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
