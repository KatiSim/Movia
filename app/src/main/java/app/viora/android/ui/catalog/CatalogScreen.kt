package app.viora.android.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.viora.android.data.catalog.CatalogFilter
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.data.catalog.filterCatalog
import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent

private val contentTypes = ContentType.entries.toList()
private val countries = listOf("Испания", "США", "Франция", "Германия", "Италия", "Норвегия", "Великобритания")

@Composable
fun CatalogScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit,
) {
    var selectedTypeName by rememberSaveable { mutableStateOf(ContentType.MOVIE.name) }
    var comedyOnly by rememberSaveable { mutableStateOf(false) }
    var recentOnly by rememberSaveable { mutableStateOf(false) }
    var highRatingOnly by rememberSaveable { mutableStateOf(false) }
    var hdOnly by rememberSaveable { mutableStateOf(false) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var durationMode by rememberSaveable { mutableStateOf("ANY") }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var maxAgeRating by rememberSaveable { mutableStateOf<Int?>(null) }
    var audioLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitleLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var advancedOpen by rememberSaveable { mutableStateOf(false) }

    val selectedType = ContentType.valueOf(selectedTypeName)
    val filter = CatalogFilter(
        type = selectedType,
        comedyOnly = comedyOnly,
        recentOnly = recentOnly,
        highRatingOnly = highRatingOnly,
        hdOnly = hdOnly,
        country = country,
        durationMode = durationMode,
        newOnly = newOnly,
        maxAgeRating = maxAgeRating,
        audioLanguage = audioLanguage,
        subtitleLanguage = subtitleLanguage,
    )
    val filtered = filterCatalog(DemoCatalogRepository.all(), filter)

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
            Text("Каталог", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(contentTypes) { type ->
                    FilterChip(selected = selectedType == type, onClick = { selectedTypeName = type.name }, label = { Text(type.label) })
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
                item { FilterChip(selected = comedyOnly, onClick = { comedyOnly = !comedyOnly }, label = { Text(if (comedyOnly) "✓ Комедия" else "Жанр") }) }
                item { FilterChip(selected = recentOnly, onClick = { recentOnly = !recentOnly }, label = { Text(if (recentOnly) "2020–2026" else "Год") }) }
                item { FilterChip(selected = highRatingOnly, onClick = { highRatingOnly = !highRatingOnly }, label = { Text(if (highRatingOnly) "★ 7+" else "Рейтинг") }) }
                item { FilterChip(selected = hdOnly, onClick = { hdOnly = !hdOnly }, label = { Text(if (hdOnly) "HD+" else "Качество") }) }
                item {
                    FilterChip(
                        selected = filter.advancedCount > 0,
                        onClick = { advancedOpen = true },
                        label = { Text(if (filter.advancedCount > 0) "Ещё · ${filter.advancedCount}" else "Ещё") },
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Найдено: ${filtered.size}", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (filter.advancedCount > 0 || comedyOnly || recentOnly || highRatingOnly || hdOnly) {
                    TextButton(onClick = {
                        comedyOnly = false
                        recentOnly = false
                        highRatingOnly = false
                        hdOnly = false
                        country = null
                        durationMode = "ANY"
                        newOnly = false
                        maxAgeRating = null
                        audioLanguage = null
                        subtitleLanguage = null
                    }) { Text("Сбросить") }
                }
            }
        }
        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Снимите один или несколько фильтров.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(filtered, key = { it.id }) { item -> CatalogMediaCard(item, onClick = { onOpenDetails(item.title) }) }
        }
    }

    if (advancedOpen) {
        AdvancedFiltersDialog(
            country = country,
            durationMode = durationMode,
            newOnly = newOnly,
            maxAgeRating = maxAgeRating,
            audioLanguage = audioLanguage,
            subtitleLanguage = subtitleLanguage,
            onCountry = { country = it },
            onDuration = { durationMode = it },
            onNewOnly = { newOnly = it },
            onAge = { maxAgeRating = it },
            onAudio = { audioLanguage = it },
            onSubtitles = { subtitleLanguage = it },
            onReset = {
                country = null
                durationMode = "ANY"
                newOnly = false
                maxAgeRating = null
                audioLanguage = null
                subtitleLanguage = null
            },
            onDismiss = { advancedOpen = false },
        )
    }
}

@Composable
private fun AdvancedFiltersDialog(
    country: String?,
    durationMode: String,
    newOnly: Boolean,
    maxAgeRating: Int?,
    audioLanguage: String?,
    subtitleLanguage: String?,
    onCountry: (String?) -> Unit,
    onDuration: (String) -> Unit,
    onNewOnly: (Boolean) -> Unit,
    onAge: (Int?) -> Unit,
    onAudio: (String?) -> Unit,
    onSubtitles: (String?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("Расширенные фильтры", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                AdvancedChoice("Страна", listOf<String?>(null) + countries, country, onCountry) { it ?: "Все" }
                AdvancedChoice("Длительность", listOf("ANY", "SHORT", "LONG"), durationMode, onDuration) {
                    when (it) { "SHORT" -> "≤100 мин"; "LONG" -> "≥110 мин"; else -> "Любая" }
                }
                AdvancedChoice("Возраст", listOf<Int?>(null, 6, 12, 16, 18), maxAgeRating, onAge) { it?.let { age -> "до $age+" } ?: "Любой" }
                AdvancedChoice("Аудио", listOf<String?>(null, "Русский", "Original"), audioLanguage, onAudio) { it ?: "Любое" }
                AdvancedChoice("Субтитры", listOf<String?>(null, "Русский", "English"), subtitleLanguage, onSubtitles) { it ?: "Любые" }
                FilterChip(selected = newOnly, onClick = { onNewOnly(!newOnly) }, label = { Text("Только новинки") })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onReset) { Text("Сбросить") }
                    Button(onClick = onDismiss) { Text("Готово") }
                }
            }
        }
    }
}

@Composable
private fun <T> AdvancedChoice(
    title: String,
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { option ->
                FilterChip(selected = option == selected, onClick = { onSelected(option) }, label = { Text(label(option)) })
            }
        }
    }
}

@Composable
private fun CatalogMediaCard(item: MediaContent, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.title}. ${item.year}, рейтинг ${item.rating}, ${item.quality}"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(174.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${item.year} · ★ ${item.rating} · ${item.quality}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
