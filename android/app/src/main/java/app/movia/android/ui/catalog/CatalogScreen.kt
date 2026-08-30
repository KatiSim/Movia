package app.movia.android.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.data.catalog.CatalogFilter
import app.movia.android.data.catalog.CatalogSort
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.RecommendationEngine
import app.movia.android.data.catalog.filterCatalog
import app.movia.android.data.catalog.sortCatalog
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.components.MediaMetadataText
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MoviaPageTitle
import java.util.Locale
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaScrim60

private val contentTypes = ContentType.entries.toList()
private val countries = listOf("Испания", "США", "Франция", "Германия", "Италия", "Норвегия", "Великобритания")
private val ratingOptions = listOf<Double?>(null, 7.0, 8.0, 8.5)
private val resolutionOptions = listOf<String?>(null, "720p", "1080p", "4K")
private val ageOptions = listOf<Int?>(null, 6, 12, 16, 18)
private val audioOptions = listOf<String?>(null, "Русский", "Original")
private val subtitleOptions = listOf<String?>(null, "Русский", "English")

private data class YearPreset(
    val label: String,
    val from: Int?,
    val to: Int?,
)

private val yearPresets = listOf(
    YearPreset("Все годы", null, null),
    YearPreset("2026", 2026, 2026),
    YearPreset("2025", 2025, 2025),
    YearPreset("2024", 2024, 2024),
    YearPreset("2020–2023", 2020, 2023),
    YearPreset("2010-е", 2010, 2019),
    YearPreset("2000-е", 2000, 2009),
)

enum class CatalogLaunchPreset { ALL, NEW, RECOMMENDED }

private enum class QuickSheet { GENRE, YEAR, RATING, RESOLUTION }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    contentPadding: PaddingValues,
    launchPreset: CatalogLaunchPreset?,
    onLaunchPresetConsumed: () -> Unit,
    history: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit,
) {
    var selectedTypeName by rememberSaveable { mutableStateOf("ALL") }
    var selectedCategoryName by rememberSaveable { mutableStateOf("ALL") }
    var selectedGenresState by rememberSaveable { mutableStateOf("") }
    var yearFrom by rememberSaveable { mutableStateOf<Int?>(null) }
    var yearTo by rememberSaveable { mutableStateOf<Int?>(null) }
    var minRating by rememberSaveable { mutableStateOf<Double?>(null) }
    var resolution by rememberSaveable { mutableStateOf<String?>(null) }
    var country by rememberSaveable { mutableStateOf<String?>(null) }
    var durationMode by rememberSaveable { mutableStateOf("ANY") }
    var newOnly by rememberSaveable { mutableStateOf(false) }
    var maxAgeRating by rememberSaveable { mutableStateOf<Int?>(null) }
    var audioLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var subtitleLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    var advancedOpen by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(CatalogSort.POPULAR.name) }
    var sortSheetOpen by remember { mutableStateOf(false) }
    var recommendedOnly by rememberSaveable { mutableStateOf(false) }

    val allContent = remember { DemoCatalogRepository.all() }
    val selectedGenres = selectedGenresState.takeIf { it.isNotBlank() }?.split("|") ?: emptyList()
    val allGenres = remember(allContent) { allContent.flatMap { it.genres }.distinct().sorted() }
    val selectedType = selectedTypeName.takeUnless { it == "ALL" }?.let(ContentType::valueOf)
    val selectedCategory = selectedCategoryName.takeUnless { it == "ALL" }?.let(CatalogCategory::valueOf)

    fun applyFilter(next: CatalogFilter) {
        selectedTypeName = next.type?.name ?: "ALL"
        selectedGenresState = next.genres.sorted().joinToString("|")
        yearFrom = next.yearFrom
        yearTo = next.yearTo
        minRating = next.minRating
        resolution = next.resolution
        country = next.country
        durationMode = next.durationMode
        newOnly = next.newOnly
        maxAgeRating = next.maxAgeRating
        audioLanguage = next.audioLanguage
        subtitleLanguage = next.subtitleLanguage
    }

    val filter = CatalogFilter(
        type = selectedType,
        genres = selectedGenres.toSet(),
        yearFrom = yearFrom,
        yearTo = yearTo,
        minRating = minRating,
        resolution = resolution,
        country = country,
        durationMode = durationMode,
        newOnly = newOnly,
        maxAgeRating = maxAgeRating,
        audioLanguage = audioLanguage,
        subtitleLanguage = subtitleLanguage,
    )

    LaunchedEffect(launchPreset) {
        launchPreset?.let { preset ->
            recommendedOnly = preset == CatalogLaunchPreset.RECOMMENDED
            selectedCategoryName = "ALL"
            applyFilter(
                CatalogFilter(
                    type = null,
                    newOnly = preset == CatalogLaunchPreset.NEW,
                ),
            )
            onLaunchPresetConsumed()
        }
    }

    val sort = CatalogSort.valueOf(sortName)
    val recommendationIds = remember(history) {
        RecommendationEngine.recommend(history).items.mapTo(linkedSetOf()) { it.id }
    }
    val filtered = sortCatalog(filterCatalog(allContent, filter), sort)
        .filter { selectedCategory == null || it.category == selectedCategory }
        .let { items ->
            if (recommendedOnly) items.filter { it.id in recommendationIds } else items
        }

    val effectiveFilterCount = filter.activeCount + if (selectedType != null) 1 else 0

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 24.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-title") {
            MoviaPageTitle(text = "Каталог")
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-categories") {
            val categories = listOf<Pair<String, CatalogCategory?>>("ALL" to null) +
                CatalogCategory.entries.map { it.name to it }
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(end = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(categories, key = { it.first }) { (key, category) ->
                        MoviaFilterChip(
                            selected = selectedCategoryName == key,
                            onClick = {
                                recommendedOnly = false
                                selectedCategoryName = key
                                selectedTypeName = "ALL"
                                selectedGenresState = ""
                            },
                            label = category?.label ?: "Все",
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(32.dp)
                        .height(48.dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                    MaterialTheme.colorScheme.background,
                                ),
                            ),
                        ),
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-controls") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = catalogCountLabel(filtered.size, selectedType),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CatalogControlButton(
                        label = if (effectiveFilterCount > 0) "Фильтры · $effectiveFilterCount" else "Фильтры",
                        active = effectiveFilterCount > 0,
                        onClick = { advancedOpen = true },
                        modifier = Modifier.weight(0.42f),
                    )
                    CatalogSortSelector(
                        label = sort.label,
                        onClick = { sortSheetOpen = true },
                        modifier = Modifier.weight(0.58f),
                    )
                }
            }
        }

        if (effectiveFilterCount > 0) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-applied-filters") {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    if (selectedType != null) {
                        item(key = "type-${selectedType.name}") {
                            AppliedFilterChip(selectedType.label) { selectedTypeName = "ALL" }
                        }
                    }
                    items(selectedGenres, key = { "genre-$it" }) { genre ->
                        AppliedFilterChip(genre) {
                            selectedGenresState = selectedGenres.filterNot { it == genre }.sorted().joinToString("|")
                        }
                    }
                    if (yearFrom != null || yearTo != null) {
                        item(key = "year") {
                            AppliedFilterChip(yearChipLabel(yearFrom, yearTo)) { yearFrom = null; yearTo = null }
                        }
                    }
                    if (minRating != null) {
                        item(key = "rating") {
                            AppliedFilterChip("★ ${formatRating(minRating!!)}+") { minRating = null }
                        }
                    }
                    if (resolution != null) {
                        item(key = "resolution") {
                            AppliedFilterChip(resolution!!) { resolution = null }
                        }
                    }
                    if (country != null) {
                        item(key = "country") {
                            AppliedFilterChip(country!!) { country = null }
                        }
                    }
                    if (durationMode != "ANY") {
                        item(key = "duration") {
                            AppliedFilterChip(if (durationMode == "SHORT") "≤100 мин" else "≥110 мин") { durationMode = "ANY" }
                        }
                    }
                    if (newOnly) {
                        item(key = "new") { AppliedFilterChip("Новинки") { newOnly = false } }
                    }
                    if (maxAgeRating != null) {
                        item(key = "age") {
                            AppliedFilterChip("до ${maxAgeRating!!}+") { maxAgeRating = null }
                        }
                    }
                    if (audioLanguage != null) {
                        item(key = "audio") {
                            AppliedFilterChip("Аудио: ${catalogAudioLabel(audioLanguage)}") { audioLanguage = null }
                        }
                    }
                    if (subtitleLanguage != null) {
                        item(key = "subtitles") {
                            AppliedFilterChip("Субтитры: ${catalogSubtitleLabel(subtitleLanguage)}") { subtitleLanguage = null }
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-empty") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ничего не найдено", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Снимите один или несколько фильтров.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(filtered, key = { it.id }) { item ->
                MediaContentCard(item = item, modifier = Modifier.fillMaxWidth(), onClick = { onOpenDetails(item.title) })
            }
        }
    }


    if (sortSheetOpen) {
        SingleChoiceSheet(
            title = "Сортировка",
            options = CatalogSort.entries,
            selected = sort,
            label = { it.label },
            onSelect = {
                sortName = it.name
                sortSheetOpen = false
            },
            onDismiss = { sortSheetOpen = false },
        )
    }

    if (advancedOpen) {
        AdvancedFiltersSheet(
            filter = filter,
            allGenres = allGenres,
            resultCount = { draftFilter -> filterCatalog(allContent, draftFilter).size },
            onApply = {
                applyFilter(it)
                advancedOpen = false
            },
            onDismiss = { advancedOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreFilterSheet(
    genres: List<String>,
    selected: Set<String>,
    resultCount: (Set<String>) -> Int,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(selected) { mutableStateOf(selected) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visible = remember(genres, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) genres else genres.filter { it.lowercase().contains(normalized) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 360.dp, max = 720.dp)
                 .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Жанры", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (genres.size > 12) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Поиск по жанрам" },
                    singleLine = true,
                    label = { Text("Поиск по жанрам") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(visible, key = { it }) { genre ->
                    ListItem(
                        headlineContent = { Text(genre) },
                        leadingContent = {
                            Checkbox(
                                checked = genre in draft,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = MaterialTheme.colorScheme.primary,
                                    checkmarkColor = MaterialTheme.colorScheme.onPrimary,
                                ),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable {
                                draft = if (genre in draft) draft - genre else draft + genre
                            },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth() .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = { draft = emptySet() }) { Text("Сбросить") }
                Button(
                    onClick = { onApply(draft) },
                    colors = ButtonDefaults.buttonColors(containerColor = MoviaBrandAmber, contentColor = MoviaOnBrandAmber),
                ) { Text("Показать ${resultCount(draft)}") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SingleChoiceSheet(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth() .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            options.forEach { option ->
                ListItem(
                    headlineContent = { Text(label(option)) },
                    leadingContent = {
                        RadioButton(
                            selected = option == selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary),
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable { onSelect(option) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFiltersSheet(
    filter: CatalogFilter,
    allGenres: List<String>,
    resultCount: (CatalogFilter) -> Int,
    onApply: (CatalogFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.96f)) {
            Row(
                modifier = Modifier.fillMaxWidth() .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Все фильтры",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { draft = CatalogFilter(type = draft.type) }) { Text("Сбросить") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                     .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FilterSection("Тип") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoviaFilterChip(draft.type == null, { draft = draft.copy(type = null) }, "Все")
                        contentTypes.forEach { type ->
                            MoviaFilterChip(draft.type == type, { draft = draft.copy(type = type) }, type.label)
                        }
                    }
                }
                FilterSection("Жанры") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allGenres.forEach { genre ->
                            MoviaFilterChip(
                                selected = genre in draft.genres,
                                onClick = {
                                    draft = draft.copy(
                                        genres = if (genre in draft.genres) draft.genres - genre else draft.genres + genre,
                                    )
                                },
                                label = genre,
                            )
                        }
                    }
                }
                FilterSection("Год") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        yearPresets.forEach { preset ->
                            MoviaFilterChip(
                                selected = draft.yearFrom == preset.from && draft.yearTo == preset.to,
                                onClick = { draft = draft.copy(yearFrom = preset.from, yearTo = preset.to) },
                                label = preset.label,
                            )
                        }
                    }
                }
                FilterSection("Рейтинг") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ratingOptions.forEach { rating ->
                            MoviaFilterChip(
                                selected = draft.minRating == rating,
                                onClick = { draft = draft.copy(minRating = rating) },
                                label = rating?.let { "★ от ${formatRating(it)}" } ?: "Любой",
                            )
                        }
                    }
                }
                FilterSection("Разрешение") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        resolutionOptions.forEach { option ->
                            MoviaFilterChip(
                                selected = draft.resolution == option,
                                onClick = { draft = draft.copy(resolution = option) },
                                label = when (option) {
                                    "720p" -> "720p"
                                    "1080p" -> "1080p"
                                    "4K" -> "4K (ультравысокое)"
                                    else -> "Любое"
                                },
                            )
                        }
                    }
                }
                FilterSection("Страна") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoviaFilterChip(draft.country == null, { draft = draft.copy(country = null) }, "Все")
                        countries.forEach { value ->
                            MoviaFilterChip(draft.country == value, { draft = draft.copy(country = value) }, value)
                        }
                    }
                }
                FilterSection("Длительность") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ANY" to "Любая", "SHORT" to "≤100 мин", "LONG" to "≥110 мин").forEach { (value, text) ->
                            MoviaFilterChip(draft.durationMode == value, { draft = draft.copy(durationMode = value) }, text)
                        }
                    }
                }
                FilterSection("Возраст") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ageOptions.forEach { age ->
                            MoviaFilterChip(
                                draft.maxAgeRating == age,
                                { draft = draft.copy(maxAgeRating = age) },
                                age?.let { "до $it+" } ?: "Любой",
                            )
                        }
                    }
                }
                FilterSection("Аудио") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        audioOptions.forEach { audio ->
                            MoviaFilterChip(
                                draft.audioLanguage == audio,
                                { draft = draft.copy(audioLanguage = audio) },
                                catalogAudioLabel(audio),
                            )
                        }
                    }
                }
                FilterSection("Субтитры") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleOptions.forEach { subtitle ->
                            MoviaFilterChip(
                                draft.subtitleLanguage == subtitle,
                                { draft = draft.copy(subtitleLanguage = subtitle) },
                                catalogSubtitleLabel(subtitle),
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth() .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Только новинки", fontWeight = FontWeight.SemiBold)
                            Text("Показывать только новые релизы", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = draft.newOnly,
                            onCheckedChange = { draft = draft.copy(newOnly = it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MoviaBrandAmber,
                                checkedThumbColor = MoviaOnBrandAmber,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier.fillMaxWidth() .padding(horizontal = 24.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MoviaBrandAmber, contentColor = MoviaOnBrandAmber),
            ) {
                Text("Показать ${resultCount(draft)}")
            }
        }
    }
}

@Composable
private fun CatalogControlButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = if (active) MoviaBrandAmber else MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, if (active) MoviaBrandAmber else MoviaBorderSubtle),
        ) {
            Box(
                modifier = Modifier.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CatalogSortSelector(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MoviaBorderSubtle),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = "Изменить сортировку",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AppliedFilterChip(
    label: String,
    onClear: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MoviaBrandAmber),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Сбросить $label",
                modifier = Modifier.size(18.dp),
                tint = MoviaBrandAmber,
            )
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun QuickFilterPill(
    label: String,
    active: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    val container = if (active) MoviaBrandAmber else MaterialTheme.colorScheme.surface
    val content = if (active) MoviaOnBrandAmber else MaterialTheme.colorScheme.onSurface
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = content,
        border = if (active) null else BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onOpen)
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = if (active) 0.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(label, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                if (!active) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            if (active) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "Сбросить $label", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MoviaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    val shape = RoundedCornerShape(10.dp)
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        },
        shape = shape,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .then(if (selected) Modifier else Modifier.border(1.dp, MoviaBorderSubtle, shape)),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MoviaBrandAmber,
            selectedLabelColor = MoviaOnBrandAmber,
        ),
    )
}


private fun catalogAudioLabel(value: String?): String = when (value) {
    null -> "Любое"
    "Original" -> "Оригинал"
    else -> value
}

private fun catalogSubtitleLabel(value: String?): String = when (value) {
    null -> "Любые"
    "English" -> "Английские"
    "Русский" -> "Русские"
    else -> value
}

private fun genreChipLabel(genres: List<String>): String = when (genres.size) {
    0 -> "Жанр"
    1 -> "Жанр: ${genres.first()}"
    else -> "Жанры: ${genres.size}"
}

private fun yearChipLabel(from: Int?, to: Int?): String = when {
    from == null && to == null -> "Год"
    from != null && from == to -> from.toString()
    from != null && to != null -> "$from–$to"
    from != null -> "от $from"
    else -> "до $to"
}


private fun catalogCountLabel(count: Int, type: ContentType?): String = when (type) {
    ContentType.MOVIE -> "$count ${pluralRu(count, "фильм", "фильма", "фильмов")}"
    ContentType.SERIES -> "$count ${pluralRu(count, "сериал", "сериала", "сериалов")}"
    ContentType.TV -> "$count ${pluralRu(count, "канал", "канала", "каналов")}"
    null -> "$count ${pluralRu(count, "материал", "материала", "материалов")}"
}

private fun pluralRu(value: Int, one: String, few: String, many: String): String {
    val mod100 = value % 100
    val mod10 = value % 10
    return when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
}

private fun formatRating(value: Double): String = String.format(Locale.US, "%.1f", value)
