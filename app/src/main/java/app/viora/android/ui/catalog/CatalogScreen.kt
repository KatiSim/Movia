package app.viora.android.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent

private val contentTypes = ContentType.entries.toList()

@Composable
fun CatalogScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit = {},
) {
    var selectedTypeName by rememberSaveable { mutableStateOf(ContentType.MOVIE.name) }
    var comedyOnly by rememberSaveable { mutableStateOf(false) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var highRatingOnly by rememberSaveable { mutableStateOf(false) }
    var hdOnly by rememberSaveable { mutableStateOf(false) }

    val selectedType = ContentType.valueOf(selectedTypeName)
    val filtered = DemoCatalogRepository.all().filter { item ->
        item.type == selectedType &&
            (!comedyOnly || "Комедия" in item.genres) &&
            (!recentOnly || item.year >= 2020) &&
            (!highRatingOnly || item.rating >= 7.0) &&
            (!hdOnly || item.quality in setOf("1080p", "4K"))
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 112.dp),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Каталог", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contentTypes) { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedTypeName = type.name },
                        label = { Text(type.label) },
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                item { FilterChip(selected = comedyOnly, onClick = { comedyOnly = !comedyOnly }, label = { Text(if (comedyOnly) "✓ Комедия" else "Жанр") }) }
                item { FilterChip(selected = recentOnly, onClick = { recentOnly = !recentOnly }, label = { Text(if (recentOnly) "2020–2026" else "Год") }) }
                item { FilterChip(selected = highRatingOnly, onClick = { highRatingOnly = !highRatingOnly }, label = { Text(if (highRatingOnly) "★ 7+" else "Рейтинг") }) }
                item { FilterChip(selected = hdOnly, onClick = { hdOnly = !hdOnly }, label = { Text(if (hdOnly) "HD+" else "Качество") }) }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Найдено: ${filtered.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Снимите один или несколько фильтров.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                CatalogMediaCard(item, onClick = { onOpenDetails(item.title) })
            }
        }
    }
}

@Composable
private fun CatalogMediaCard(item: MediaContent, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${item.year} · ★ ${item.rating} · ${item.quality}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
