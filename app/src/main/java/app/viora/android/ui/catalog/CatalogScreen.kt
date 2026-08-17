package app.viora.android.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

data class CatalogItem(
    val title: String,
    val meta: String,
)

private val catalogItems = listOf(
    CatalogItem("Граница миров", "2026 · ★ 8.1"),
    CatalogItem("Тихий сигнал", "2025 · ★ 7.9"),
    CatalogItem("Последний рейс", "2026 · ★ 7.7"),
    CatalogItem("Северный ветер", "2024 · ★ 8.0"),
    CatalogItem("Касание света", "2026 · ★ 7.6"),
    CatalogItem("Нулевая орбита", "2026 · ★ 8.3"),
    CatalogItem("Точка возврата", "2026 · ★ 7.8"),
    CatalogItem("Город после дождя", "2026 · ★ 8.0"),
)

private val contentTypes = listOf("Фильмы", "Сериалы", "ТВ")

@Composable
fun CatalogScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var selectedType by rememberSaveable { mutableStateOf("Фильмы") }
    var comedyOnly by rememberSaveable { mutableStateOf(false) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var highRatingOnly by rememberSaveable { mutableStateOf(false) }
    var hdOnly by rememberSaveable { mutableStateOf(false) }

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
            Text(
                text = "Каталог",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(contentTypes) { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = { Text(type) },
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                item {
                    FilterChip(
                        selected = comedyOnly,
                        onClick = { comedyOnly = !comedyOnly },
                        label = { Text(if (comedyOnly) "Комедия" else "Жанр") },
                    )
                }
                item {
                    FilterChip(
                        selected = recentOnly,
                        onClick = { recentOnly = !recentOnly },
                        label = { Text(if (recentOnly) "2020–2026" else "Год") },
                    )
                }
                item {
                    FilterChip(
                        selected = highRatingOnly,
                        onClick = { highRatingOnly = !highRatingOnly },
                        label = { Text(if (highRatingOnly) "★ 7+" else "Рейтинг") },
                    )
                }
                item {
                    FilterChip(
                        selected = hdOnly,
                        onClick = { hdOnly = !hdOnly },
                        label = { Text(if (hdOnly) "HD+" else "Качество") },
                    )
                }
                item {
                    FilterChip(
                        selected = false,
                        onClick = {},
                        label = { Text("Ещё") },
                    )
                }
            }
        }

        items(catalogItems) { item ->
            CatalogMediaCard(item)
        }
    }
}

@Composable
private fun CatalogMediaCard(item: CatalogItem) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(174.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.meta,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
