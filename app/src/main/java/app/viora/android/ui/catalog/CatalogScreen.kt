package app.viora.android.ui.catalog

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.data.catalog.CatalogFilter
import app.viora.android.data.catalog.CatalogSort
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.data.catalog.filterCatalog
import app.viora.android.data.catalog.sortCatalog
import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent
import app.viora.android.ui.components.MediaMetadataText
import java.util.Locale

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

enum class CatalogLaunchPreset { ALL, NEW }

private enum class QuickSheet { GENRE, YEAR, RATING, RESOLUTION }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    contentPadding: PaddingValues,
    launchPreset: CatalogLaunchPreset?,
    onLaunchPresetConsumed: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit,
) {
    var selectedTypeName by rememberSaveable { mutableStateOf(ContentType.MOVIE.name) }
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
    var quickSheet by remember { mutableStateOf<QuickSheet?>(null) }
    var advancedOpen by remember { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(CatalogSort.POPULAR.name) }
    var sortSheetOpen by remember { mutableStateOf(false) }

    val allContent = remember { DemoCatalogRepository.all() }
    val selectedGenres = selectedGenresState.takeIf { it.isNotBlank() }?.split("|") ?: emptyList()
    val allGenres = remember(allContent) { allContent.flatMap { it.genres }.distinct().sorted() }
    val selectedType = selectedTypeName.takeUnless { it == "ALL" }?.let(ContentType::valueOf)

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
    val filtered = sortCatalog(filterCatalog(allContent, filter), sort)

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Каталог", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val typeOptions = listOf<ContentType?>(null) + contentTypes
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                typeOptions.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = selectedType == type,
                        onClick = { selectedTypeName = type?.name ?: "ALL" },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = typeOptions.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = MaterialTheme.colorScheme.primary,
                            activeContentColor = MaterialTheme.colorScheme.onPrimary,
                            inactiveContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            activeBorderColor = MaterialTheme.colorScheme.primary,
                            inactiveBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        label = { Text(type?.label ?: "Все", maxLines = 1) },
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickFilterPill(
                    label = if (filter.activeCount > 0) "Фильтры · ${filter.activeCount}" else "Фильтры",
                    active = filter.activeCount > 0,
                    onOpen = { advancedOpen = true },
                    onClear = { applyFilter(CatalogFilter(type = selectedType)) },
                )
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(end = 16.dp),
                ) {
                    item {
                        QuickFilterPill(
                            label = genreChipLabel(selectedGenres),
                            active = selectedGenres.isNotEmpty(),
                            onOpen = { quickSheet = QuickSheet.GENRE },
                            onClear = { selectedGenresState = "" },
                        )
                    }
                    item {
                        QuickFilterPill(
                            label = yearChipLabel(yearFrom, yearTo),
                            active = yearFrom != null || yearTo != null,
                            onOpen = { quickSheet = QuickSheet.YEAR },
                            onClear = { yearFrom = null; yearTo = null },
                        )
                    }
                    item {
                        QuickFilterPill(
                            label = minRating?.let { "★ ${formatRating(it)}+" } ?: "Рейтинг",
                            active = minRating != null,
                            onOpen = { quickSheet = QuickSheet.RATING },
                            onClear = { minRating = null },
                        )
                    }
                    item {
                        QuickFilterPill(
                            label = resolution ?: "Разрешение",
                            active = resolution != null,
                            onOpen = { quickSheet = QuickSheet.RESOLUTION },
                            onClear = { resolution = null },
                        )
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    catalogCountLabel(filtered.size, selectedType),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { sortSheetOpen = true }) {
                    Text(sort.label)
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Изменить сортировку",
                        modifier = Modifier.size(18.dp),
                    )
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
            items(filtered, key = { it.id }) { item ->
                CatalogMediaCard(item, onClick = { onOpenDetails(item.title) })
            }
        }
    }

    when (quickSheet) {
        QuickSheet.GENRE -> GenreFilterSheet(
            genres = allGenres,
            selected = selectedGenres.toSet(),
            resultCount = { draftGenres -> filterCatalog(allContent, filter.copy(genres = draftGenres)).size },
            onApply = {
                selectedGenresState = it.sorted().joinToString("|")
                quickSheet = null
            },
            onDismiss = { quickSheet = null },
        )
        QuickSheet.YEAR -> SingleChoiceSheet(
            title = "Год выпуска",
            options = yearPresets,
            selected = yearPresets.firstOrNull { it.from == yearFrom && it.to == yearTo } ?: yearPresets.first(),
            label = { it.label },
            onSelect = {
                yearFrom = it.from
                yearTo = it.to
                quickSheet = null
            },
            onDismiss = { quickSheet = null },
        )
        QuickSheet.RATING -> SingleChoiceSheet(
            title = "Минимальный рейтинг",
            options = ratingOptions,
            selected = minRating,
            label = { it?.let { value -> "★ от ${formatRating(value)}" } ?: "Любой рейтинг" },
            onSelect = {
                minRating = it
                quickSheet = null
            },
            onDismiss = { quickSheet = null },
        )
        QuickSheet.RESOLUTION -> SingleChoiceSheet(
            title = "Разрешение",
            options = resolutionOptions,
            selected = resolution,
            label = {
                when (it) {
                    "720p" -> "720p"
                    "1080p" -> "1080p"
                    "4K" -> "4K Ultra HD"
                    else -> "Любое разрешение"
                }
            },
            onSelect = {
                resolution = it
                quickSheet = null
            },
            onDismiss = { quickSheet = null },
        )
        null -> Unit
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
                .padding(horizontal = 20.dp),
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
                modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = { draft = emptySet() }) { Text("Сбросить") }
                Button(onClick = { onApply(draft) }) { Text("Показать ${resultCount(draft)}") }
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
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
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FilterSection("Тип") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VioraFilterChip(draft.type == null, { draft = draft.copy(type = null) }, "Все")
                        contentTypes.forEach { type ->
                            VioraFilterChip(draft.type == type, { draft = draft.copy(type = type) }, type.label)
                        }
                    }
                }
                FilterSection("Жанры") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        allGenres.forEach { genre ->
                            VioraFilterChip(
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
                            VioraFilterChip(
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
                            VioraFilterChip(
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
                            VioraFilterChip(
                                selected = draft.resolution == option,
                                onClick = { draft = draft.copy(resolution = option) },
                                label = when (option) {
                                    "720p" -> "720p"
                                    "1080p" -> "1080p"
                                    "4K" -> "4K Ultra HD"
                                    else -> "Любое"
                                },
                            )
                        }
                    }
                }
                FilterSection("Страна") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        VioraFilterChip(draft.country == null, { draft = draft.copy(country = null) }, "Все")
                        countries.forEach { value ->
                            VioraFilterChip(draft.country == value, { draft = draft.copy(country = value) }, value)
                        }
                    }
                }
                FilterSection("Длительность") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ANY" to "Любая", "SHORT" to "≤100 мин", "LONG" to "≥110 мин").forEach { (value, text) ->
                            VioraFilterChip(draft.durationMode == value, { draft = draft.copy(durationMode = value) }, text)
                        }
                    }
                }
                FilterSection("Возраст") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ageOptions.forEach { age ->
                            VioraFilterChip(
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
                            VioraFilterChip(
                                draft.audioLanguage == audio,
                                { draft = draft.copy(audioLanguage = audio) },
                                audio ?: "Любое",
                            )
                        }
                    }
                }
                FilterSection("Субтитры") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        subtitleOptions.forEach { subtitle ->
                            VioraFilterChip(
                                draft.subtitleLanguage == subtitle,
                                { draft = draft.copy(subtitleLanguage = subtitle) },
                                subtitle ?: "Любые",
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Только новинки", fontWeight = FontWeight.SemiBold)
                            Text("Показывать только новые релизы", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = draft.newOnly,
                            onCheckedChange = { draft = draft.copy(newOnly = it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = MaterialTheme.colorScheme.primary,
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text("Показать ${resultCount(draft)}")
            }
        }
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    val container = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val content = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onOpen)
                    .heightIn(min = 48.dp)
                    .padding(start = 16.dp, end = if (active) 2.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
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
private fun VioraFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.heightIn(min = 48.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun CatalogMediaCard(item: MediaContent, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.title}. ${item.year}, рейтинг ${item.rating}, ${item.quality}"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MediaMetadataText(
            text = "${item.year} · ★ ${item.rating} · ${item.quality}",
        )
    }
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
