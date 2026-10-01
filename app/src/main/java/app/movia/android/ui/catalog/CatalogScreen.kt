package app.movia.android.ui.catalog

import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.data.catalog.CatalogFilter
import app.movia.android.data.catalog.CatalogSort
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.MediaContent
import androidx.compose.ui.text.style.TextAlign
import app.movia.android.ui.components.rememberMoviaMediaCoverFeedback
import app.movia.android.ui.components.MoviaMediaCoverFrame
import app.movia.android.ui.components.MoviaTapIconButton
import app.movia.android.ui.components.rememberMoviaNeonFeedbackAlpha
import app.movia.android.ui.components.rememberMoviaAnimatedAction
import app.movia.android.ui.components.rememberMoviaActionTriggerState
import app.movia.android.ui.components.MediaMetadataText
import app.movia.android.ui.components.MediaMetadataRow
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.MoviaPageTitle
import app.movia.android.ui.components.moviaDisplayTitle
import app.movia.android.ui.components.moviaRatingLabel
import app.movia.android.ui.components.moviaPrimaryGenre
import app.movia.android.ui.components.moviaContentTypeLabel
import app.movia.android.ui.components.moviaYearLabel
import app.movia.android.ui.components.moviaLocalizedGenreLabel
import java.util.Locale
import kotlin.math.roundToInt
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import app.movia.android.ui.theme.MoviaSurfacePrimary
import app.movia.android.ui.theme.MoviaSurfaceSecondary
import app.movia.android.ui.theme.MoviaSurfaceElevated
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBorderMedium
import app.movia.android.ui.theme.MoviaScrim60
import app.movia.android.ui.theme.MoviaTextPrimary
import app.movia.android.ui.theme.MoviaTextSecondary
import app.movia.android.ui.theme.MoviaTextTertiary

private enum class CatalogFacet(val label: String) {
    ALL("Все"),
    MOVIES("Фильмы"),
    SERIES("Сериалы"),
    ANIME("Аниме"),
}

private val contentTypes = ContentType.entries.toList()
private val countries = listOf("США", "Великобритания", "Франция", "Германия", "Италия", "Испания", "Россия", "СССР", "Южная Корея", "Турция", "Индия", "Япония", "Китай", "Канада", "Австралия", "Дания", "Швеция", "Норвегия", "Польша", "Мексика", "Бразилия")
private val ratingOptions = listOf<Double?>(null, 7.0, 8.0, 8.5)
private val resolutionOptions = listOf<String?>(null, "720p", "1080p", "4K")
private val ageOptions = listOf<Int?>(null, 6, 12, 16, 18)
private val audioOptions = listOf<String?>(null, "Русский", "English")
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
    var nextOffset: Int = 0
    var firstVisibleItemIndex: Int = 0
    var firstVisibleItemScrollOffset: Int = 0

    fun reset(nextRequestKey: String) {
        requestKey = nextRequestKey
        itemIds = ""
        totalCount = 0
        hasMore = true
        nextOffset = 0
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
    onSearchCommitted: (String) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDetails: (String, String) -> Unit,
    onOpenProfile: () -> Unit,
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
    var searchFocused by remember { mutableStateOf(false) }
    var voiceActive by remember { mutableStateOf(false) }
    var voiceUnavailable by rememberSaveable { mutableStateOf(false) }
    var genreSheetOpen by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val catalogFocusManager = LocalFocusManager.current
    val catalogKeyboardController = LocalSoftwareKeyboardController.current
    var catalogRootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var searchFieldCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val catalogViewModel: CatalogViewModel = viewModel()
    val context = LocalContext.current
    val catalogUiState by catalogViewModel.uiState.collectAsStateWithLifecycle()
    val homeFeed by catalogViewModel.homeFeed.collectAsStateWithLifecycle()
    val recommendationIdsState by catalogViewModel.recommendationIds.collectAsStateWithLifecycle()
    val pagingItems = catalogViewModel.pagedCatalog.collectAsLazyPagingItems()

    // Keep the grid position and already loaded pages when the details route temporarily replaces this screen.
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    var savedRequestKey by rememberSaveable { mutableStateOf("") }
    var savedItemIds by rememberSaveable { mutableStateOf("") }
    var savedTotalCount by rememberSaveable { mutableIntStateOf(0) }
    var savedHasMore by rememberSaveable { mutableStateOf(true) }
    var savedNextOffset by rememberSaveable { mutableIntStateOf(0) }
    var retentionCaptureEnabled by remember { mutableStateOf(false) }

    val selectedGenres = selectedGenresState.takeIf { it.isNotBlank() }?.split("|") ?: emptyList()
    val allGenres = homeFeed.genres.ifEmpty {
        listOf("аниме", "боевик", "детектив", "драма", "комедия", "мультфильм", "триллер", "ужасы", "фантастика")
    }
    val selectedType = selectedTypeName.takeUnless { it == "ALL" }?.let(ContentType::valueOf)
    val selectedCategory = selectedCategoryName.takeUnless { it == "ALL" }?.let(CatalogCategory::valueOf)

    fun dismissSearchFocus() {
        catalogKeyboardController?.hide()
        catalogFocusManager.clearFocus(force = true)
        searchFocused = false
    }

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
        voiceActive = false
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
            voiceActive = true
            voiceUnavailable = false
            voiceLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            voiceActive = false
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
            savedNextOffset = 0
            searchQuery = ""
            searchFocused = false
            gridState.scrollToItem(0)
            onLaunchPresetConsumed()
        }
    }

    val sort = CatalogSort.valueOf(sortName)
    LaunchedEffect(history, favorites, recommendedOnly) {
        catalogViewModel.updateRecommendations(history, favorites, recommendedOnly)
    }
    val recommendationIds = recommendationIdsState.orEmpty()
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
    val requestMatchesState = catalogUiState.requestKey == requestKey
    val isSearchRequest = searchQuery.isNotBlank()
    val pagedItems = if (requestMatchesState) {
        pagingItems.itemSnapshotList.items
    } else {
        emptyList()
    }
    val pagingItemCount = if (requestMatchesState) pagingItems.itemCount else 0
    val searchResults = if (requestMatchesState && isSearchRequest) catalogUiState.searchResults else emptyList()
    val pagingRefreshError = (pagingItems.loadState.refresh as? LoadState.Error)?.error
    val pagingAppendError = (pagingItems.loadState.append as? LoadState.Error)?.error
    val isLoading = !requestMatchesState || catalogUiState.isLoading ||
        pagingItems.loadState.refresh is LoadState.Loading || pagingItems.loadState.append is LoadState.Loading
    val loadError = pagingRefreshError?.message ?: pagingAppendError?.message ?: catalogUiState.errorMessage
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

    val loadRequest = CatalogLoadRequest(
        requestKey = requestKey,
        sort = sort,
        category = selectedCategory,
        filter = filter,
        query = searchQuery.trim().takeIf { it.isNotBlank() },
        recommendedIds = if (recommendedOnly) recommendationIds else emptySet(),
    )

    LaunchedEffect(requestKey, recommendationIdsState) {
        retentionCaptureEnabled = false

        if (recommendedOnly && recommendationIdsState == null) return@LaunchedEffect

        val currentStateReady = requestMatchesState && !catalogUiState.isLoading &&
            (isSearchRequest || catalogUiState.nextOffset > 0 || !catalogUiState.hasMore)
        if (currentStateReady) {
            if (retention.requestKey == requestKey && (pagedItems.isNotEmpty() || retention.itemIds.isNotBlank())) {
                gridState.scrollToItem(
                    retention.firstVisibleItemIndex.coerceAtLeast(0),
                    retention.firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
            }
            savedRequestKey = requestKey
            val loadedIds = pagedItems.joinToString("|") { it.id }
            if (loadedIds.isNotBlank()) savedItemIds = loadedIds
            else if (retention.requestKey != requestKey) savedItemIds = catalogUiState.searchResults.joinToString("|") { it.id }
            savedTotalCount = catalogUiState.totalCount
            savedHasMore = catalogUiState.hasMore
            savedNextOffset = catalogUiState.nextOffset
            retentionCaptureEnabled = true
            return@LaunchedEffect
        }

        if (loadRequest.query != null) {
            catalogViewModel.loadFirstPage(loadRequest)
            return@LaunchedEffect
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
            val restored = catalogViewModel.findCachedItems(restoredIds)
            val restoredTotal = if (canRestoreRoute) retention.totalCount else savedTotalCount
            val restoredHasMore = if (canRestoreRoute) retention.hasMore else savedHasMore
            val restoredNextOffset = when {
                canRestoreRoute -> retention.nextOffset
                canRestoreLocal -> savedNextOffset
                else -> restored.size
            }.coerceAtLeast(restored.size)
            catalogViewModel.restore(loadRequest, restored, restoredTotal, restoredHasMore, restoredNextOffset)
            savedRequestKey = requestKey
            savedItemIds = restored.joinToString("|") { it.id }
            savedTotalCount = restoredTotal
            savedHasMore = restoredHasMore
            savedNextOffset = restoredNextOffset

            if (canRestoreRoute && restored.isNotEmpty()) {
                gridState.scrollToItem(
                    retention.firstVisibleItemIndex.coerceAtLeast(0),
                    retention.firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
            }
            retentionCaptureEnabled = true
            return@LaunchedEffect
        }

        retention.reset(requestKey)
        savedRequestKey = requestKey
        savedItemIds = ""
        gridState.scrollToItem(0)
        catalogViewModel.loadFirstPage(loadRequest)
    }

    LaunchedEffect(requestKey, catalogUiState, pagedItems) {
        val stateReady = catalogUiState.requestKey == requestKey && !catalogUiState.isLoading &&
            (searchQuery.isNotBlank() || catalogUiState.nextOffset > 0 || !catalogUiState.hasMore)
        if (stateReady) {
            savedRequestKey = requestKey
            val loadedIds = pagedItems.joinToString("|") { it.id }
            if (loadedIds.isNotBlank()) savedItemIds = loadedIds
            else if (searchQuery.isNotBlank()) savedItemIds = catalogUiState.searchResults.joinToString("|") { it.id }
            savedTotalCount = catalogUiState.totalCount
            savedHasMore = catalogUiState.hasMore
            savedNextOffset = catalogUiState.nextOffset
            retention.requestKey = requestKey
            retention.itemIds = savedItemIds
            retention.totalCount = catalogUiState.totalCount
            retention.hasMore = catalogUiState.hasMore
            retention.nextOffset = catalogUiState.nextOffset
            retentionCaptureEnabled = true
        }
    }

    val retryLoad: () -> Unit = {
        if (searchQuery.isNotBlank()) catalogViewModel.retry() else pagingItems.retry()
    }

    // Quick type/genre chips are self-describing; this badge counts only deep filters.
    val advancedFilterCount = (filter.activeCount - if (selectedGenres.isNotEmpty()) 1 else 0)
        .coerceAtLeast(0)

    val currentFacet = when {
        selectedCategoryName == CatalogCategory.ANIME.name -> CatalogFacet.ANIME
        selectedTypeName == ContentType.SERIES.name && selectedCategoryName == "ALL" -> CatalogFacet.SERIES
        selectedTypeName == ContentType.MOVIE.name && selectedCategoryName == "ALL" -> CatalogFacet.MOVIES
        else -> CatalogFacet.ALL
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MoviaBackgroundPrimary)
            .onGloballyPositioned { catalogRootCoordinates = it }
            .pointerInput(searchFocused, catalogRootCoordinates, searchFieldCoordinates) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Final,
                    )
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Final)
                    if (up != null && searchFocused) {
                        val root = catalogRootCoordinates
                        val search = searchFieldCoordinates
                        val tapInWindow = root?.localToWindow(up.position)
                        val searchBounds = search?.boundsInWindow()
                        if (
                            tapInWindow == null ||
                            searchBounds == null ||
                            !searchBounds.contains(tapInWindow)
                        ) {
                            dismissSearchFocus()
                        }
                    }
                }
            },
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-header") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(92.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = "Какую историю выберешь?",
                        modifier = Modifier.offset(y = 20.dp),
                        color = MoviaTextPrimary,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                            dismissSearchFocus()
                            scope.launch { gridState.scrollToItem(0) }
                        },
                        onVoice = {
                            dismissSearchFocus()
                            startVoiceSearch()
                        },
                        voiceActive = voiceActive,
                        onFilterClick = {
                            dismissSearchFocus()
                            advancedOpen = true
                        },
                        activeFilterCount = advancedFilterCount,
                        modifier = Modifier.onGloballyPositioned { searchFieldCoordinates = it },
                        onSearch = {
                            dismissSearchFocus()
                            commitSearch(searchQuery)
                            scope.launch { gridState.scrollToItem(0) }
                        },
                    )
                    if (voiceUnavailable) {
                        Text(
                            text = "На устройстве не найден сервис голосового ввода.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-content-types") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CatalogFacet.entries.forEach { facet ->
                        val isSelected = currentFacet == facet
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clickable {
                                    dismissSearchFocus()
                                    when (facet) {
                                        CatalogFacet.ALL -> {
                                            selectedTypeName = "ALL"
                                            selectedCategoryName = "ALL"
                                        }
                                        CatalogFacet.MOVIES -> {
                                            selectedTypeName = ContentType.MOVIE.name
                                            selectedCategoryName = "ALL"
                                        }
                                        CatalogFacet.SERIES -> {
                                            selectedTypeName = ContentType.SERIES.name
                                            selectedCategoryName = "ALL"
                                        }
                                        CatalogFacet.ANIME -> {
                                            selectedTypeName = "ALL"
                                            selectedCategoryName = CatalogCategory.ANIME.name
                                        }
                                    }
                                    recommendedOnly = false
                                    scope.launch { gridState.scrollToItem(0) }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(36.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) MoviaSurfacePrimary else MoviaSurfaceSecondary,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MoviaBrandAmber else MoviaBorderSubtle,
                                ),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                    text = facet.label,
                                    color = if (isSelected) MoviaBrandAmber else MoviaTextSecondary,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                    maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-sort") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .clickable {
                                dismissSearchFocus()
                                sortSheetOpen = true
                            }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = catalogSortDisplayLabel(sort),
                                color = MoviaTextSecondary,
                                fontSize = 14.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Icon(
                                imageVector = Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Изменить сортировку",
                                modifier = Modifier.size(20.dp),
                                tint = MoviaTextSecondary,
                            )
                        }
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
                                color = MoviaTextPrimary,
                                fontSize = 18.sp,
                                lineHeight = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            TextButton(
                                onClick = onClearRecent,
                                modifier = Modifier.testTag("catalog.searchHistory.clear"),
                            ) {
                                Text(
                                    "Очистить",
                                    color = MoviaBrandAmber,
                                )
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(recentQueries, key = { "recent-" + it }) { recent ->
                                Surface(
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clickable {
                                            dismissSearchFocus()
                                            commitSearch(recent)
                                        },
                                    shape = RoundedCornerShape(10.dp),
                                    color = MoviaSurfaceSecondary,
                                    border = BorderStroke(1.dp, MoviaBorderSubtle),
                                ) {
                                    Box(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = recent,
                                            color = MoviaTextSecondary,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (loadError != null && (pagedItems.isNotEmpty() || searchResults.isNotEmpty())) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-load-warning") {
                    CatalogLoadError(message = requireNotNull(loadError), onRetry = retryLoad)
                }
            }

            if (isLoading && pagedItems.isEmpty() && searchResults.isEmpty()) {
                items(6, key = { "skeleton-$it" }) {
                    CatalogSkeletonCard()
                }
            } else {
                if (pagingItemCount == 0 && !isLoading) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "catalog-empty") {
                        if (loadError != null) {
                            CatalogLoadError(message = requireNotNull(loadError), onRetry = retryLoad)
                        } else {
                            CatalogEmptyState(
                                query = searchQuery.takeIf { it.isNotBlank() },
                                filterActive = advancedFilterCount > 0 || currentFacet != CatalogFacet.ALL,
                                onReset = {
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
                                    searchQuery = ""
                                    scope.launch { gridState.scrollToItem(0) }
                                },
                            )
                        }
                    }
                } else {
                    items(
                        count = pagingItemCount,
                        key = pagingItems.itemKey { it.id },
                    ) { index ->
                        val item = pagingItems[index]
                        if (item == null) {
                            CatalogSkeletonCard()
                        } else {
                            CatalogMediaCard(
                                item = item,
                                modifier = Modifier.fillMaxWidth().testTag("catalog.item.open.${item.id}"),
                                onClick = {
                                    dismissSearchFocus()
                                    onOpenDetails(item.id, item.title)
                                },
                            )
                        }
                    }
                    if (isLoading && pagedItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "pagination-loading") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = MoviaBrandAmber,
                                    strokeWidth = 2.5.dp,
                                )
                            }
                        }
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
                containerColor = MoviaSurfaceElevated,
                contentColor = MoviaTextPrimary,
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
        val sortOptions = listOf(
            CatalogSort.POPULAR,
            CatalogSort.RATING,
            CatalogSort.NEWEST,
            CatalogSort.TITLE,
        )
        SingleChoiceSheet(
            title = "Сортировка",
            options = sortOptions,
            selected = sort,
            label = ::catalogSortDisplayLabel,
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
                catalogViewModel.catalogResultCount(
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
                catalogViewModel.catalogResultCount(
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

private fun catalogSortDisplayLabel(sort: CatalogSort): String = when (sort) {
    CatalogSort.POPULAR -> "Сначала популярные"
    CatalogSort.RATING -> "По рейтингу"
    CatalogSort.NEWEST -> "Сначала новые"
    CatalogSort.TITLE -> "По названию"
    CatalogSort.OLDEST -> "Сначала старые"
    CatalogSort.CATEGORY -> "По типу и году"
}

@Composable
private fun CatalogSearchField(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onVoice: () -> Unit,
    voiceActive: Boolean,
    onFilterClick: () -> Unit,
    activeFilterCount: Int,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (focused) MoviaBrandAmber.copy(alpha = 0.5f) else MoviaBorderSubtle
    val filterActive = activeFilterCount > 0
    val voiceInteractionSource = remember { MutableInteractionSource() }
    val voicePressed by voiceInteractionSource.collectIsPressedAsState()
    val filterInteractionSource = remember { MutableInteractionSource() }
    val filterPressed by filterInteractionSource.collectIsPressedAsState()
    val filterActionTrigger = rememberMoviaActionTriggerState()
    val filterGlowAlpha = rememberMoviaNeonFeedbackAlpha(filterActionTrigger, durationMs = 500)
    val animatedFilterClick = rememberMoviaAnimatedAction(filterActionTrigger, 500L, onFilterClick)
    val voiceScale by animateFloatAsState(
        targetValue = if (voicePressed) 0.90f else 1.00f,
        animationSpec = tween(durationMillis = 500),
        label = "catalog-voice-press",
    )
    val voiceGlowAlpha by animateFloatAsState(
        targetValue = if (voiceActive) 1.00f else 0.00f,
        animationSpec = tween(durationMillis = 500),
        label = "catalog-voice-glow",
    )
    val filterScale by animateFloatAsState(
        targetValue = if (filterPressed) 0.90f else 1.00f,
        animationSpec = tween(durationMillis = 500),
        label = "catalog-filter-press",
    )

    BasicTextField(
        value = searchQuery,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = MoviaTextPrimary,
            fontSize = 16.sp,
            lineHeight = 24.sp,
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
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MoviaSurfaceSecondary, shape)
            .border(1.dp, borderColor, shape)
            .testTag("catalog.search")
            .onFocusChanged { onFocusChange(it.isFocused) },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MoviaTextSecondary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Найти фильм, сериал или аниме",
                            color = MoviaTextSecondary,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (searchQuery.isNotEmpty()) {
                    MoviaTapIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Очистить поиск",
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            onClear()
                        },
                        modifier = Modifier.size(48.dp).testTag("catalog.search.clear"),
                        iconModifier = Modifier.size(20.dp),
                        tint = MoviaTextSecondary,
                    )
                }
                IconButton(
                    onClick = onVoice,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = voiceScale
                            scaleY = voiceScale
                        }
                        .testTag("catalog.voice"),
                    interactionSource = voiceInteractionSource,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .requiredSize(48.dp)
                                .blur(
                                    radius = 10.dp,
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                ),
                        ) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.00f to MoviaBrandAmber.copy(alpha = 0.34f * voiceGlowAlpha),
                                        0.48f to MoviaBrandAmber.copy(alpha = 0.15f * voiceGlowAlpha),
                                        1.00f to MoviaBrandAmber.copy(alpha = 0.00f),
                                    ),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                ),
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.Mic,
                            contentDescription = if (voiceActive) "Голосовой поиск активен" else "Голосовой поиск",
                            tint = if (voiceActive || voicePressed) MoviaBrandAmber else MoviaTextSecondary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(MoviaBorderSubtle),
                )
                IconButton(
                    onClick = animatedFilterClick,
                    modifier = Modifier
                        .size(48.dp)
                        .graphicsLayer {
                            scaleX = filterScale
                            scaleY = filterScale
                        }
                        .testTag("catalog.filter"),
                    interactionSource = filterInteractionSource,
                ) {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .requiredSize(48.dp)
                                .blur(
                                    radius = 10.dp,
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                ),
                        ) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.00f to MoviaBrandAmber.copy(alpha = 0.40f * filterGlowAlpha),
                                        0.48f to MoviaBrandAmber.copy(alpha = 0.18f * filterGlowAlpha),
                                        1.00f to MoviaBrandAmber.copy(alpha = 0.00f),
                                    ),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                ),
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Фильтры",
                            tint = if (filterActive || filterPressed || filterGlowAlpha > 0.02f) {
                                MoviaBrandAmber
                            } else {
                                MoviaTextSecondary
                            },
                            modifier = Modifier.size(24.dp),
                        )
                        if (filterActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .background(MoviaBrandAmber, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$activeFilterCount",
                                    color = MoviaBackgroundPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 10.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun CatalogEmptyState(
    query: String?,
    filterActive: Boolean = false,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Ничего не найдено",
            color = MoviaTextPrimary,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (!query.isNullOrBlank()) {
                "По запросу «$query» ничего не найдено"
            } else {
                "Попробуйте изменить запрос или фильтры"
            },
            color = MoviaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        if (filterActive || !query.isNullOrBlank()) {
            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MoviaSurfaceElevated,
                    contentColor = MoviaBrandAmber,
                ),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MoviaBorderSubtle),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    text = if (filterActive) "Сбросить фильтры" else "Очистить поиск",
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun CatalogLoadError(
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Не удалось загрузить данные",
            color = MoviaTextPrimary,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message,
            color = MoviaTextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MoviaSurfaceElevated,
                contentColor = MoviaBrandAmber,
            ),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MoviaBorderSubtle),
        ) {
            Text("Повторить")
        }
    }
}

@Composable
private fun CatalogSkeletonCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(MoviaSurfaceSecondary)
                .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(12.dp)),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .height(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MoviaSurfaceSecondary),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MoviaSurfaceElevated),
        )
    }
}

@Composable
private fun CatalogMediaCard(
    item: MediaContent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayTitle = moviaDisplayTitle(item.title)
    val rating = item.imdbRating ?: item.rating
    val ratingLabel = moviaRatingLabel(rating)
    val primaryGenre = moviaPrimaryGenre(item)?.takeIf { it.isNotBlank() }
    val genreOrType = primaryGenre ?: moviaContentTypeLabel(item).takeIf { it.isNotBlank() }
    val yearLabel = moviaYearLabel(item.year)
    val coverFeedback = rememberMoviaMediaCoverFeedback(onClick)

    val talkBackText = buildString {
        append(displayTitle)
        if (ratingLabel != null) append(". Рейтинг $ratingLabel")
        if (genreOrType != null) append(". $genreOrType")
        if (yearLabel != null) append(". $yearLabel")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = coverFeedback.onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = talkBackText
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        MoviaMediaCoverFrame(
            feedback = coverFeedback,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(12.dp),
        ) {
            MoviaArtwork(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
            )
        }

        Text(
            text = displayTitle,
            modifier = Modifier.fillMaxWidth(),
            color = MoviaTextPrimary,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        MediaMetadataRow(
            item = item,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreFilterSheet(
    genres: List<String>,
    selected: Set<String>,
    resultCount: suspend (Set<String>) -> Int,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var draft by remember(selected) { mutableStateOf(selected) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resultCountValue by produceState<Int?>(initialValue = null, draft) {
        value = null
        delay(250L)
        value = withContext(Dispatchers.IO) { resultCount(draft) }
    }
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
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface),
                ) { Text(resultCountValue?.let { "Показать $it" } ?: "Показать результаты") }
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
        containerColor = MoviaSurfacePrimary,
        scrimColor = MoviaBackgroundPrimary.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                color = MoviaTextPrimary,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            options.forEach { option ->
                val isSelected = option == selected
                ListItem(
                    headlineContent = {
                        Text(
                            text = label(option),
                            color = if (isSelected) MoviaBrandAmber else MoviaTextPrimary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    leadingContent = {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MoviaBrandAmber,
                                unselectedColor = MoviaTextSecondary,
                            ),
                        )
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        containerColor = MoviaSurfacePrimary,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelect(option) },
                )
            }
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
            fontSize = 12.sp,
            lineHeight = 16.sp,
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
    resultCount: suspend (CatalogFilter) -> Int,
    onApply: (CatalogFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(filter) { mutableStateOf(filter) }
    var showAllGenres by remember(filter) { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val resultCountValue by produceState<Int?>(initialValue = null, draft) {
        value = null
        delay(250L)
        value = withContext(Dispatchers.IO) { resultCount(draft) }
    }
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
    val statusBarTopInset = with(LocalDensity.current) {
        WindowInsets.statusBars.getTop(this).toDp()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MoviaSurfacePrimary,
        scrimColor = MoviaBackgroundPrimary.copy(alpha = 0.6f),
        dragHandle = null,
        modifier = Modifier.fillMaxHeight().testTag("catalog.filter.sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight(0.96f)
                .padding(top = statusBarTopInset),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MoviaTapIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Закрыть фильтры",
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("catalog.filter.close"),
                    iconModifier = Modifier.size(24.dp),
                    tint = MoviaTextPrimary,
                    actionDelayMs = 500L,
                )
                Text(
                    "Фильтры",
                    color = MoviaTextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
                TextButton(onClick = { draft = CatalogFilter(type = null) }) {
                    Text(
                        "Сбросить",
                        color = MoviaBrandAmber,
                    )
                }
            }
            HorizontalDivider(color = MoviaBorderMedium)
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
            HorizontalDivider(color = MoviaBorderMedium)
            Button(
                onClick = { onApply(draft) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .testTag("catalog.filter.apply"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
            ) {
                Text(resultCountValue?.let { "Показать $it" } ?: "Показать результаты")
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
        border = BorderStroke(1.dp, MoviaBorderSubtle),
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
    val container = if (active) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surface
    val content = if (active) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
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
                MoviaTapIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Сбросить $label",
                    onClick = onClear,
                    modifier = Modifier.size(48.dp),
                    iconModifier = Modifier.size(18.dp),
                    tint = content,
                )
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
            selectedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            selectedLabelColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
    )
}


private fun catalogAudioLabel(value: String?): String = when (value) {
    null -> "Любое"
    "Original", "English" -> "Английский"
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

private fun formatRating(value: Double): String = String.format(Locale.US, "%.1f", value).replace('.', ',')
