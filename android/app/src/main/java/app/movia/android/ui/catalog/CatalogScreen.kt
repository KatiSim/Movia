package app.movia.android.ui.catalog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
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
import kotlin.math.roundToInt
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaScrim60

private val contentTypes = ContentType.entries.toList()
private val countries = listOf("США", "Великобритания", "Франция", "Германия", "Италия", "Испания", "Россия", "СССР", "Южная Корея", "Турция", "Индия", "Япония", "Китай", "Канада", "Австралия", "Дания", "Швеция", "Норвегия", "Польша", "Мексика", "Бразилия")
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

enum class CatalogLaunchPreset { ALL, POPULAR, NEW, RECOMMENDED }

private enum class QuickSheet { GENRE, YEAR, RATING, RESOLUTION }

/**
 * Route-level retention for the catalog. DetailsScreen temporarily replaces the
 * catalog subtree, so its loaded pages and exact grid position must live above
 * that route boundary.
 */
class CatalogRetentionState {
    var requestKey: String = ""
    var itemIds: String = ""
    var totalCount: Int = 0
    var hasMore: Boolean = true
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0

    fun reset(nextRequestKey: String) {
        requestKey = nextRequestKey
        itemIds = ""
        totalCount = 0
        hasMore = true
        firstVisibleItemIndex = 0
        firstVisibleItemScrollOffset = 0
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CatalogScreen(
    contentPadding: PaddingValues,
    launchPreset: CatalogLaunchPreset?,
    onLaunchPresetConsumed: () -> Unit,
    retention: CatalogRetentionState,
    history: List<String> = emptyList(),
    favorites: Set<String> = emptySet(),
    recentQueries: List<String> = emptyList(),
    onSearchCommitted: (String) -> Unit = {},
    onClearRecent: () -> Unit = {},
    resetTrigger: Int = 0,
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
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MediaContent>>(emptyList()) }
    var searchFocused by remember { mutableStateOf(false) }
    var voiceUnavailable by rememberSaveable { mutableStateOf(false) }
    var genreSheetOpen by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    var pagedItems by remember { mutableStateOf<List<MediaContent>>(emptyList()) }
    var totalCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }

    // Keep the grid position and already loaded pages when the details route temporarily replaces this screen.
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var savedRequestKey by rememberSaveable { mutableStateOf("") }
    var savedItemIds by rememberSaveable { mutableStateOf("") }
    var savedTotalCount by rememberSaveable { mutableIntStateOf(0) }
    var savedHasMore by rememberSaveable { mutableStateOf(true) }
    var retentionCaptureEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(resetTrigger) {
        if (resetTrigger > 0 && launchPreset == null) {
            selectedTypeName = "ALL"
            selectedCategoryName = "ALL"
            selectedGenresState = ""
            yearFrom = null
            yearTo = null
            minRating = null
            resolution = null
            country = null
            durationMode = "ANY"
            newOnly = false
            maxAgeRating = null
            audioLanguage = null
            subtitleLanguage = null
            sortName = CatalogSort.POPULAR.name
            recommendedOnly = false
            searchQuery = ""
            searchFocused = false
            retention.reset("")
            savedRequestKey = ""
            savedItemIds = ""
            savedTotalCount = 0
            savedHasMore = true
            gridState.scrollToItem(0)
        }
    }

    val selectedGenres = selectedGenresState.takeIf { it.isNotBlank() }?.split("|") ?: emptyList()
    val allGenres = remember { DemoCatalogRepository.getAllGenres() }
    val selectedType = selectedTypeName.takeUnless { it == "ALL" }?.let(ContentType::valueOf)
    val selectedCategory = selectedCategoryName.takeUnless { it == "ALL" }?.let(CatalogCategory::valueOf)

    fun commitSearch(value: String) {
        val normalized = value.trim()
        searchQuery = normalized
        scope.launch { gridState.scrollToItem(0) }
        if (normalized.isNotEmpty()) {
            onSearchCommitted(normalized)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!recognized.isNullOrEmpty()) {
                voiceUnavailable = false
                commitSearch(recognized)
            }
        }
    }

    fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Что найти в Movia?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceLauncher.launch(intent)
            voiceUnavailable = false
        } catch (_: ActivityNotFoundException) {
            voiceUnavailable = true
        }
    }

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
            when (preset) {
                CatalogLaunchPreset.NEW -> {
                    sortName = CatalogSort.NEWEST.name
                    recommendedOnly = false
                    selectedCategoryName = "ALL"
                    applyFilter(
                        CatalogFilter(
                            type = null,
                            newOnly = false,
                        ),
                    )
                }
                CatalogLaunchPreset.POPULAR, CatalogLaunchPreset.ALL -> {
                    sortName = CatalogSort.POPULAR.name
                    recommendedOnly = false
                    selectedCategoryName = "ALL"
                    applyFilter(
                        CatalogFilter(
                            type = null,
                            newOnly = false,
                        ),
                    )
                }
                CatalogLaunchPreset.RECOMMENDED -> {
                    sortName = CatalogSort.RATING.name
                    recommendedOnly = true
                    selectedCategoryName = "ALL"
                    applyFilter(
                        CatalogFilter(
                            type = null,
                            newOnly = false,
                        ),
                    )
                }
            }
            retention.reset("")
            savedRequestKey = ""
            savedItemIds = ""
            savedTotalCount = 0
            savedHasMore = true
            searchQuery = ""
            searchFocused = false
            gridState.scrollToItem(0)
            onLaunchPresetConsumed()
        }
    }

    val sort = CatalogSort.valueOf(sortName)
    val recommendationIds = remember(history, favorites) {
        RecommendationEngine.recommend(history, favorites = favorites).items.mapTo(linkedSetOf()) { it.id }
    }
    val requestKey = listOf(
        selectedCategoryName,
        selectedTypeName,
        selectedGenresState,
        sortName,
        yearFrom,
        yearTo,
        minRating,
        resolution,
        country,
        durationMode,
        newOnly,
        maxAgeRating,
        audioLanguage,
        subtitleLanguage,
        searchQuery.trim(),
        recommendedOnly,
        if (recommendedOnly) recommendationIds.joinToString(",") else "",
    ).joinToString("|")
    val showScrollToTop by remember {
        derivedStateOf { gridState.firstVisibleItemIndex > 10 }
    }

    // Capture the exact grid position independently from the catalog subtree.
    // The existing "Наверх" behavior remains unchanged and still uses gridState.
    LaunchedEffect(gridState, requestKey) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                if (retentionCaptureEnabled) {
                    retention.requestKey = requestKey
                    retention.firstVisibleItemIndex = index
                    retention.firstVisibleItemScrollOffset = offset
                }
            }
    }

    LaunchedEffect(requestKey) {
        isLoading = true
        retentionCaptureEnabled = false

        if (searchQuery.isNotBlank()) {
            val cleanQuery = searchQuery.trim()
            if (cleanQuery.isNotEmpty()) {
                kotlinx.coroutines.delay(150L)
                val page = withContext(Dispatchers.IO) {
                    val count = DemoCatalogRepository.getTotalCount(
                        category = selectedCategory,
                        filter = filter,
                        query = cleanQuery,
                    )
                    val items = DemoCatalogRepository.getPaged(
                        limit = 60,
                        offset = 0,
                        sort = sort,
                        category = selectedCategory,
                        filter = filter,
                        query = cleanQuery,
                    )
                    count to items
                }
                searchResults = page.second
                totalCount = page.first
                hasMore = page.second.size >= 60 && page.second.size < page.first
                isLoading = false
                return@LaunchedEffect
            }
        }

        val canRestoreLocal = savedRequestKey == requestKey && savedItemIds.isNotBlank()
        val canRestoreRoute = retention.requestKey == requestKey && retention.itemIds.isNotBlank()
        val restoreIds = when {
            canRestoreRoute -> retention.itemIds
            canRestoreLocal -> savedItemIds
            else -> ""
        }

        if (restoreIds.isNotBlank()) {
            val restoredIds = restoreIds.split("|").filter { it.isNotBlank() }
            val restored = withContext(Dispatchers.IO) {
                restoredIds.mapNotNull { DemoCatalogRepository.findById(it) }
            }
            totalCount = if (canRestoreRoute) retention.totalCount else savedTotalCount
            pagedItems = restored
            hasMore = if (canRestoreRoute) retention.hasMore else savedHasMore
            savedRequestKey = requestKey
            savedItemIds = restored.joinToString("|") { it.id }
            savedTotalCount = totalCount
            savedHasMore = hasMore

            if (canRestoreRoute && restored.isNotEmpty()) {
                gridState.scrollToItem(
                    retention.firstVisibleItemIndex.coerceAtLeast(0),
                    retention.firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
            }
            retentionCaptureEnabled = true
            isLoading = false
            return@LaunchedEffect
        }

        retention.reset(requestKey)
        savedRequestKey = requestKey
        savedItemIds = ""
        gridState.scrollToItem(0)

        withContext(Dispatchers.IO) {
            val count = DemoCatalogRepository.getTotalCount(
                category = selectedCategory,
                filter = filter,
                query = null,
            )
            val initial = DemoCatalogRepository.getPaged(
                limit = 40,
                offset = 0,
                sort = sort,
                category = selectedCategory,
                filter = filter,
                query = null,
            )
            val finalInitial = if (recommendedOnly) {
                initial.filter { it.id in recommendationIds }
            } else {
                initial
            }
            withContext(Dispatchers.Main) {
                totalCount = count
                pagedItems = finalInitial
                savedItemIds = finalInitial.joinToString("|") { it.id }
                savedTotalCount = count
                hasMore = initial.size >= 40
                savedHasMore = hasMore
                retention.requestKey = requestKey
                retention.itemIds = savedItemIds
                retention.totalCount = count
                retention.hasMore = hasMore
                retention.firstVisibleItemIndex = 0
                retention.firstVisibleItemScrollOffset = 0
                retentionCaptureEnabled = true
                isLoading = false
            }
        }
    }

    fun loadNextPage() {
        if (isLoading || !hasMore) return
        isLoading = true
        val activeQuery = searchQuery.trim().takeIf { it.isNotBlank() }
        val currentOffset = if (activeQuery != null) searchResults.size else pagedItems.size
        val pageSize = if (activeQuery != null) 60 else 40
        scope.launch(Dispatchers.IO) {
            val nextPage = DemoCatalogRepository.getPaged(
                limit = pageSize,
                offset = currentOffset,
                sort = sort,
                category = selectedCategory,
                filter = filter,
                query = activeQuery,
            )
            val finalNext = if (recommendedOnly) {
                nextPage.filter { it.id in recommendationIds }
            } else {
                nextPage
            }
            withContext(Dispatchers.Main) {
                if (activeQuery != null) {
                    if (nextPage.isNotEmpty()) searchResults = searchResults + finalNext
                } else if (nextPage.isNotEmpty()) {
                    pagedItems = pagedItems + finalNext
                    savedItemIds = pagedItems.joinToString("|") { it.id }
                    retention.itemIds = savedItemIds
                }
                hasMore = nextPage.size >= pageSize
                savedHasMore = hasMore
                retention.requestKey = requestKey
                retention.totalCount = totalCount
                retention.hasMore = hasMore
                isLoading = false
            }
        }
    }

    // Quick type/genre chips are self-describing; this badge counts only deep filters.
    val advancedFilterCount = (filter.activeCount - if (selectedGenres.isNotEmpty()) 1 else 0)
        .coerceAtLeast(0)

    Box(modifier = modifier) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 168.dp),
            modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-title") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoviaPageTitle(text = "Каталог")
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) searchResults.size.toString() else if (totalCount > 0) totalCount.toString() else pagedItems.size.toString(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }, key = "explore-search") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CatalogSearchField(
                    searchQuery = searchQuery,
                    onQueryChange = {
                        searchQuery = it
                        searchFocused = true
                    },
                    focused = searchFocused,
                    onFocusChange = { searchFocused = it },
                    onClear = {
                        searchQuery = ""
                        searchFocused = false
                        scope.launch { gridState.scrollToItem(0) }
                    },
                    onVoice = ::startVoiceSearch,
                    onSearch = {
                        searchFocused = false
                        commitSearch(searchQuery)
                        scope.launch { gridState.scrollToItem(0) }
                    },
                )
                if (voiceUnavailable) {
                    Text(
                        text = "На устройстве не найден сервис голосового ввода.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        // One unified toolbar: deep filters and sort first, quick categories after them.
        item(span = { GridItemSpan(maxLineSpan) }, key = "unified-filter-bar") {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 24.dp),
            ) {
                item(key = "toolbar-filters") {
                    CatalogControlButton(
                        label = if (advancedFilterCount > 0) "Фильтры · $advancedFilterCount" else "Фильтры",
                        active = advancedFilterCount > 0,
                        onClick = { advancedOpen = true },
                        modifier = Modifier.width(132.dp),
                    )
                }
                item(key = "toolbar-sort") {
                    CatalogSortSelector(
                        label = sort.label,
                        onClick = { sortSheetOpen = true },
                        modifier = Modifier.width(156.dp),
                    )
                }
                item(key = "quick-all") {
                    MoviaFilterChip(
                        selected = selectedTypeName == "ALL" &&
                            selectedCategoryName == "ALL" &&
                            selectedGenres.isEmpty() &&
                            !recommendedOnly,
                        onClick = {
                            recommendedOnly = false
                            selectedTypeName = "ALL"
                            selectedCategoryName = "ALL"
                            selectedGenresState = ""
                        },
                        label = "Все",
                    )
                }
                item(key = "quick-movies") {
                    val selected = selectedTypeName == ContentType.MOVIE.name &&
                        selectedCategoryName == "ALL"
                    MoviaFilterChip(
                        selected = selected,
                        onClick = {
                            recommendedOnly = false
                            if (selected) {
                                selectedTypeName = "ALL"
                            } else {
                                selectedTypeName = ContentType.MOVIE.name
                                selectedCategoryName = "ALL"
                            }
                        },
                        label = if (selected) "Фильмы ✕" else "Фильмы",
                    )
                }
                item(key = "quick-series") {
                    val selected = selectedTypeName == ContentType.SERIES.name &&
                        selectedCategoryName == "ALL"
                    MoviaFilterChip(
                        selected = selected,
                        onClick = {
                            recommendedOnly = false
                            if (selected) {
                                selectedTypeName = "ALL"
                            } else {
                                selectedTypeName = ContentType.SERIES.name
                                selectedCategoryName = "ALL"
                            }
                        },
                        label = if (selected) "Сериалы ✕" else "Сериалы",
                    )
                }
                item(key = "quick-animation") {
                    val selected = selectedCategoryName == CatalogCategory.ANIMATION.name
                    MoviaFilterChip(
                        selected = selected,
                        onClick = {
                            recommendedOnly = false
                            if (selected) {
                                selectedCategoryName = "ALL"
                            } else {
                                selectedCategoryName = CatalogCategory.ANIMATION.name
                                selectedTypeName = "ALL"
                            }
                        },
                        label = if (selected) "Мультфильмы ✕" else "Мультфильмы",
                    )
                }
                item(key = "quick-genres") {
                    MoviaFilterChip(
                        selected = selectedGenres.isNotEmpty(),
                        onClick = { genreSheetOpen = true },
                        label = if (selectedGenres.isEmpty()) {
                            "Жанры"
                        } else {
                            "Жанры · " + selectedGenres.size
                        },
                    )
                }
            }
        }

        if (searchFocused && searchQuery.isBlank() && recentQueries.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "explore-recent") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Недавние запросы",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 18.sp,
                            lineHeight = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(onClick = onClearRecent) { Text("Очистить") }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentQueries, key = { "recent-" + it }) { recent ->
                            MoviaFilterChip(
                                selected = false,
                                onClick = { commitSearch(recent) },
                                label = recent,
                            )
                        }
                    }
                }
            }
        }

        if (searchQuery.isNotBlank()) {
            if (searchResults.isEmpty() && !isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-search-empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Ничего не найдено",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "По запросу «$searchQuery» ничего не найдено.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(searchResults, key = { it.id }) { item ->
                    MediaContentCard(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenDetails(item.title) },
                    )
                }
            }
        } else {
            if (pagedItems.isEmpty() && !isLoading) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Ничего не найдено",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Снимите один или несколько фильтров.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                itemsIndexed(pagedItems, key = { _, item -> item.id }) { index, item ->
                    if (index >= pagedItems.size - 6 && !isLoading && hasMore) {
                        LaunchedEffect(index) {
                            loadNextPage()
                        }
                    }
                    MediaContentCard(
                        item = item,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenDetails(item.title) },
                    )
                }
            }
        }
    }

        AnimatedVisibility(
            visible = showScrollToTop,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = 20.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
        ) {
            FloatingActionButton(
                onClick = {
                    scope.launch { gridState.scrollToItem(0) }
                },
                modifier = Modifier
                    .size(52.dp)
                    .semantics { contentDescription = "Наверх" },
                containerColor = MoviaBrandAmber,
                contentColor = MoviaOnBrandAmber,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Наверх",
                    modifier = Modifier.size(28.dp),
                )
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
            minYear = 1920,
            maxYear = 2026,
            resultCount = { draftFilter ->
                DemoCatalogRepository.getTotalCount(
                    category = selectedCategory,
                    filter = draftFilter,
                    query = searchQuery.takeIf { it.isNotBlank() },
                )
            },
            onApply = {
                applyFilter(it)
                advancedOpen = false
            },
            onDismiss = { advancedOpen = false },
        )
    }

    if (genreSheetOpen) {
        GenreFilterSheet(
            genres = allGenres,
            selected = selectedGenres.toSet(),
            resultCount = { draft ->
                DemoCatalogRepository.getTotalCount(
                    category = selectedCategory,
                    filter = filter.copy(genres = draft),
                    query = searchQuery.takeIf { it.isNotBlank() },
                )
            },
            onApply = {
                selectedGenresState = it.sorted().joinToString("|")
                genreSheetOpen = false
            },
            onDismiss = { genreSheetOpen = false },
        )
    }
}

@Composable
private fun CatalogSearchField(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onVoice: () -> Unit,
    onSearch: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (focused) MoviaBrandAmber else MoviaBorderSubtle
    BasicTextField(
        value = searchQuery,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        cursorBrush = SolidColor(MoviaBrandAmber),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboardController?.hide()
                focusManager.clearFocus()
                onSearch()
            },
            onDone = {
                keyboardController?.hide()
                focusManager.clearFocus()
                onSearch()
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, borderColor, shape)
            .onFocusChanged { onFocusChange(it.isFocused) },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = if (focused) MoviaBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Поиск фильмов и сериалов",
                            color = Color.Gray,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
                IconButton(
                    onClick = {
                        if (searchQuery.isNotEmpty()) {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onClear()
                        } else {
                            onVoice()
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (searchQuery.isNotEmpty()) Icons.Outlined.Close else Icons.Outlined.Mic,
                        contentDescription = if (searchQuery.isNotEmpty()) "Очистить поиск" else "Голосовой поиск",
                        tint = if (focused) MoviaBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    )
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
                        headlineContent = { Text(genreDisplayLabel(genre)) },
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

@Composable
private fun FilterGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = title.uppercase(Locale.ROOT),
            color = MoviaBrandAmber,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
        )
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFiltersSheet(
    filter: CatalogFilter,
    allGenres: List<String>,
    minYear: Int,
    maxYear: Int,
    resultCount: (CatalogFilter) -> Int,
    onApply: (CatalogFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    var showAllGenres by remember(filter) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val visibleGenres = if (showAllGenres) allGenres else allGenres.take(8)
    val safeMinYear = minYear.coerceAtMost(maxYear)
    val safeMaxYear = maxYear.coerceAtLeast(safeMinYear)
    val selectedStartYear = (draft.yearFrom ?: safeMinYear)
        .coerceIn(safeMinYear, safeMaxYear)
        .toFloat()
    val selectedEndYear = (draft.yearTo ?: safeMaxYear)
        .coerceIn(safeMinYear, safeMaxYear)
        .toFloat()
    val selectedRating = draft.minRating?.toFloat()?.coerceIn(0f, 10f) ?: 0f

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxHeight(),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.96f)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Все фильтры",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { draft = CatalogFilter(type = null) }) {
                    Text("Сбросить")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                FilterGroup("Основное") {
                    FilterSection("Тип контента") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            contentTypes.forEach { type ->
                                val selected = draft.type == type
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(type = if (selected) null else type)
                                    },
                                    label = type.label,
                                )
                            }
                        }
                        Text(
                            "Пустой выбор = все типы",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FilterSection("Жанры") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            visibleGenres.forEach { genre ->
                                val selected = genre in draft.genres
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(
                                            genres = if (selected) draft.genres - genre else draft.genres + genre,
                                        )
                                    },
                                    label = genreDisplayLabel(genre),
                                )
                            }
                            if (allGenres.size > 8) {
                                TextButton(
                                    onClick = { showAllGenres = !showAllGenres },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        if (showAllGenres) "Скрыть" else "Ещё " + (allGenres.size - 8),
                                        color = MoviaBrandAmber,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        Text(
                            "Выбрано: " + draft.genres.size,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FilterSection("Год выхода") {
                        Text(
                            "Год выхода: " + selectedStartYear.roundToInt() + " — " + selectedEndYear.roundToInt(),
                            fontWeight = FontWeight.SemiBold,
                        )
                        RangeSlider(
                            value = selectedStartYear..selectedEndYear,
                            onValueChange = { range ->
                                val from = range.start.roundToInt()
                                val to = range.endInclusive.roundToInt()
                                draft = draft.copy(
                                    yearFrom = if (from <= safeMinYear) null else from,
                                    yearTo = if (to >= safeMaxYear) null else to,
                                )
                            },
                            valueRange = safeMinYear.toFloat()..safeMaxYear.toFloat(),
                            steps = (safeMaxYear - safeMinYear - 1).coerceAtLeast(0),
                            colors = SliderDefaults.colors(
                                thumbColor = MoviaBrandAmber,
                                activeTrackColor = MoviaBrandAmber,
                                inactiveTrackColor = MoviaBorderSubtle,
                            ),
                        )
                        Text(
                            "Потяни границы, чтобы задать диапазон",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    FilterSection("Рейтинг") {
                        Text(
                            if (selectedRating <= 0f) {
                                "Минимальный рейтинг: любой"
                            } else {
                                "Минимальный рейтинг: ★ " + formatRating(selectedRating.toDouble()) + "+"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Slider(
                            value = selectedRating,
                            onValueChange = { value ->
                                val normalized = (value * 2f).roundToInt() / 2f
                                draft = draft.copy(
                                    minRating = if (normalized <= 0f) null else normalized.toDouble(),
                                )
                            },
                            valueRange = 0f..10f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = MoviaBrandAmber,
                                activeTrackColor = MoviaBrandAmber,
                                inactiveTrackColor = MoviaBorderSubtle,
                            ),
                        )
                        Text(
                            "0 = без ограничения",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FilterGroup("Параметры воспроизведения") {
                    FilterSection("Качество") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            resolutionOptions.filterNotNull().forEach { option ->
                                val selected = draft.resolution == option
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(resolution = if (selected) null else option)
                                    },
                                    label = if (option == "4K") "4K Ultra HD" else option + " HD",
                                )
                            }
                        }
                    }

                    FilterSection("Аудиодорожка") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            audioOptions.filterNotNull().forEach { option ->
                                val selected = draft.audioLanguage == option
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(audioLanguage = if (selected) null else option)
                                    },
                                    label = catalogAudioLabel(option),
                                )
                            }
                        }
                    }

                    FilterSection("Субтитры") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            subtitleOptions.filterNotNull().forEach { option ->
                                val selected = draft.subtitleLanguage == option
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(subtitleLanguage = if (selected) null else option)
                                    },
                                    label = catalogSubtitleLabel(option),
                                )
                            }
                        }
                    }
                }

                FilterGroup("Дополнительно") {
                    FilterSection("Страна производства") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            countries.forEach { value ->
                                val selected = draft.country == value
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(country = if (selected) null else value)
                                    },
                                    label = value,
                                )
                            }
                        }
                    }

                    FilterSection("Длительность") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(
                                "SHORT" to "≤100 мин",
                                "MEDIUM" to "101–109 мин",
                                "LONG" to "≥110 мин",
                            ).forEach { (value, label) ->
                                val selected = draft.durationMode == value
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(
                                            durationMode = if (selected) "ANY" else value,
                                        )
                                    },
                                    label = label,
                                )
                            }
                        }
                    }

                    FilterSection("Возрастной рейтинг") {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ageOptions.filterNotNull().forEach { age ->
                                val selected = draft.maxAgeRating == age
                                MoviaFilterChip(
                                    selected = selected,
                                    onClick = {
                                        draft = draft.copy(maxAgeRating = if (selected) null else age)
                                    },
                                    label = "до " + age + "+",
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Только новинки", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Показывать только новые релизы",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                }

                Spacer(Modifier.height(8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MoviaBrandAmber,
                    contentColor = MoviaOnBrandAmber,
                ),
            ) {
                Text("Показать " + resultCount(draft))
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

private fun genreDisplayLabel(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
    "нф и фэнтези" -> "НФ и фэнтези"
    "реалити-шоу" -> "Реалити-шоу"
    "ток-шоу" -> "Ток-шоу"
    "мыльная опера" -> "Мыльная опера"
    "боевик и приключения" -> "Боевик и приключения"
    "война и политика" -> "Война и политика"
    "телевизионный фильм" -> "Телевизионный фильм"
    else -> value.trim().replaceFirstChar { it.titlecase(Locale.ROOT) }
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
