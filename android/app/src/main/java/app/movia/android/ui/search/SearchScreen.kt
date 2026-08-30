package app.movia.android.ui.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.CanonicalTextNormalizer
import app.movia.android.data.catalog.SearchStatus
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.MoviaPageTitle
import app.movia.android.ui.components.SectionHeader
import app.movia.android.ui.components.moviaPrimaryGenre
import app.movia.android.ui.theme.MoviaBorderFocused
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import java.util.Locale

private val discoveryGenres = listOf("Фантастика", "Драма", "Комедия", "Триллер")
private val resultFilters = listOf("Все", "Фильмы", "Сериалы", "Люди")

@OptIn(FlowPreview::class)
@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    recentQueries: List<String> = emptyList(),
    onSearchCommitted: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var voiceUnavailable by rememberSaveable { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    var committed by rememberSaveable { mutableStateOf(false) }
    var selectedFilter by rememberSaveable { mutableStateOf("Все") }

    var results by remember { mutableStateOf<List<MediaContent>>(emptyList()) }
    var people by remember { mutableStateOf<List<Person>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchStatus by remember { mutableStateOf(SearchStatus.EMPTY_QUERY) }
    var searchError by remember { mutableStateOf<String?>(null) }
    val popularItems = remember { DemoCatalogRepository.getPopular(8) }

    LaunchedEffect(Unit) {
        snapshotFlow { CanonicalTextNormalizer.normalize(query) }
            .debounce(150L)
            .distinctUntilChanged()
            .collectLatest { normalized ->
                if (normalized.isBlank()) {
                    results = emptyList()
                    people = emptyList()
                    searchStatus = SearchStatus.EMPTY_QUERY
                    searchError = null
                    isSearching = false
                    return@collectLatest
                }

                isSearching = true
                searchError = null
                val local = withContext(Dispatchers.IO) {
                    DemoCatalogRepository.searchDetailed(
                        normalized,
                        limit = 20,
                        discover = false,
                    )
                }
                ensureActive()
                results = local.items
                people = local.people
                searchStatus = local.status
                searchError = local.errorMessage
                isSearching = false

                // Remote discovery is deliberately separated from the first local
                // response and is never eligible for one- or two-character input.
                if (normalized.length >= 3 && local.weakLocal) {
                    isSearching = true
                    delay(400L)
                    ensureActive()
                    val enriched = withContext(Dispatchers.IO) {
                        DemoCatalogRepository.searchDetailed(
                            normalized,
                            limit = 20,
                            discover = true,
                        )
                    }
                    ensureActive()
                    results = enriched.items
                    people = enriched.people
                    searchStatus = enriched.status
                    searchError = enriched.errorMessage
                    isSearching = false
                }
            }
    }

    fun commitSearch(value: String) {
        val normalized = value.trim()
        if (normalized.isNotEmpty()) {
            query = normalized
            committed = true
            selectedFilter = "Все"
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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
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

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 24.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "search-header") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                MoviaPageTitle(text = "Поиск")
                MoviaSearchField(
                    query = query,
                    onQueryChange = {
                        query = it
                        committed = it.isNotBlank()
                        selectedFilter = "Все"
                    },
                    focused = searchFocused,
                    onFocusChange = { searchFocused = it },
                    onClear = {
                        query = ""
                        committed = false
                        selectedFilter = "Все"
                    },
                    onVoice = ::startVoiceSearch,
                    onSearch = { commitSearch(query) },
                )
            }
        }

        if (voiceUnavailable) {
            item(key = "voice-error") {
                Text(
                    text = "На устройстве не найден системный сервис распознавания речи.",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        when {
            committed && query.isNotBlank() -> {
                item(key = "result-title") {
                    Text(
                        text = "Результаты для «${query.trim()}»",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                item(key = "result-filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(resultFilters, key = { it }) { filter ->
                            ResultFilterChip(
                                text = filter,
                                selected = selectedFilter == filter,
                                onClick = { selectedFilter = filter },
                            )
                        }
                    }
                }

                val visibleMedia = when (selectedFilter) {
                    "Фильмы" -> results.filter { it.type == ContentType.MOVIE }
                    "Сериалы" -> results.filter { it.type == ContentType.SERIES || it.type == ContentType.TV }
                    "Люди" -> emptyList()
                    else -> results
                }
                val visiblePeople = if (selectedFilter == "Все" || selectedFilter == "Люди") people else emptyList()
                val totalVisible = visibleMedia.size + visiblePeople.size
                val searchStatusError = searchStatus !in setOf(
                    SearchStatus.OK,
                    SearchStatus.NO_RESULTS,
                    SearchStatus.EMPTY_QUERY,
                )

                if (totalVisible == 0 && isSearching) {
                    item(key = "searching") {
                        Text(
                            text = "Ищем в каталоге…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                } else if (totalVisible == 0 && searchStatusError) {
                    item(key = "search-error") {
                        Text(
                            text = searchError ?: "Поиск временно недоступен. Локальный каталог не был заменён пустой выдачей.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                } else if (totalVisible == 0) {
                    item(key = "empty-results") {
                        SearchEmptyState(
                            onClear = {
                                query = ""
                                committed = false
                                selectedFilter = "Все"
                            },
                        )
                    }
                } else {
                    items(visibleMedia, key = { "result-${it.id}" }) { item ->
                        SearchResultRow(
                            item = item,
                            onClick = {
                                onSearchCommitted(query.trim())
                                onOpenDetails(item.title)
                            },
                        )
                    }

                    items(visiblePeople, key = { "person-${it.name}" }) { person ->
                        PersonResultRow(
                            person = person,
                            onClick = {
                                query = person.name
                                commitSearch(person.name)
                            },
                        )
                    }
                }
            }

            searchFocused && query.isNotBlank() -> {
                val mediaSuggestions = results.take(6)
                val peopleSuggestions = people.take((8 - mediaSuggestions.size).coerceAtLeast(0))

                item(key = "suggestions-title") {
                    Text(
                        text = "Подсказки",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (mediaSuggestions.isEmpty() && peopleSuggestions.isEmpty()) {
                    item(key = "suggestions-empty") {
                        Text(
                            text = "Нет подходящих подсказок. Нажмите поиск, чтобы проверить весь каталог.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                        )
                    }
                } else {
                    items(mediaSuggestions, key = { "suggest-${it.id}" }) { item ->
                        SearchSuggestionRow(
                            title = item.title,
                            supporting = "${contentTypeLabel(item.type)} • ${item.year}",
                            onClick = {
                                onSearchCommitted(query.trim())
                                onOpenDetails(item.title)
                            },
                        )
                    }
                    items(peopleSuggestions, key = { "suggest-person-${it.name}" }) { person ->
                        SearchSuggestionRow(
                            title = person.name,
                            supporting = "Человек",
                            person = true,
                            onClick = {
                                query = person.name
                                commitSearch(person.name)
                            },
                        )
                    }
                }
            }

            else -> {
                if (recentQueries.isNotEmpty()) {
                    item(key = "recent") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SectionHeader(
                                    title = "Недавние",
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = onClearRecent,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
                                ) {
                                    Text(
                                        text = "Очистить",
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(recentQueries, key = { it }) { recent ->
                                    SearchSuggestionChip(
                                        text = recent,
                                        onClick = { commitSearch(recent) },
                                    )
                                }
                            }
                        }
                    }
                }

                item(key = "popular") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(title = "Популярное")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 12.dp),
                        ) {
                            items(popularItems, key = { "popular-${it.id}" }) { item ->
                                MediaContentCard(
                                    item = item,
                                    modifier = Modifier.width(134.dp),
                                    onClick = { onOpenDetails(item.title) },
                                )
                            }
                        }
                    }
                }

                item(key = "genres") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(title = "Жанры")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(discoveryGenres, key = { it }) { genre ->
                                SearchSuggestionChip(
                                    text = genre,
                                    onClick = { commitSearch(genre) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoviaSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onVoice: () -> Unit,
    onSearch: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val focusBorder = if (focused) MoviaBorderFocused else MoviaBorderSubtle

    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            lineHeight = 22.sp,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(width = 1.dp, color = focusBorder, shape = shape)
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
                    modifier = Modifier.size(24.dp),
                    tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Название, актёр или режиссёр",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            lineHeight = 22.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                IconButton(
                    onClick = if (query.isNotEmpty()) onClear else onVoice,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (query.isNotEmpty()) Icons.Outlined.Close else Icons.Outlined.Mic,
                        contentDescription = if (query.isNotEmpty()) "Очистить поиск" else "Голосовой поиск",
                        tint = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
    )
}

@Composable
private fun SearchSuggestionChip(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ResultFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MoviaBrandAmber else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (selected) MoviaBrandAmber else MoviaBorderSubtle),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun SearchSuggestionRow(
    title: String,
    supporting: String,
    person: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = if (person) Icons.Outlined.Person else Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = supporting,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    item: MediaContent,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MoviaArtwork(
            url = item.posterUrl,
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(10.dp)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = item.title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            SearchMetadataLine(
                item = item,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PersonResultRow(
    person: Person,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = person.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "Связанный контент: ${person.knownFor.size}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun SearchEmptyState(
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = "Ничего не найдено",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Попробуйте изменить запрос или проверить написание.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        TextButton(onClick = onClear) {
            Text(
                text = "Очистить запрос",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun contentTypeLabel(type: ContentType): String = when (type) {
    ContentType.MOVIE -> "Фильм"
    ContentType.SERIES -> "Сериал"
    ContentType.TV -> "ТВ"
}

@Composable
private fun SearchMetadataLine(
    item: MediaContent,
    modifier: Modifier = Modifier,
) {
    val parts = listOfNotNull(
        item.year.takeIf { it > 0 }?.toString(),
        contentTypeLabel(item.type),
        moviaPrimaryGenre(item),
    )
    val showRating = item.rating >= 5.0
    val rating = if (showRating) {
        String.format(Locale.US, "%.1f", item.rating)
    } else {
        null
    }

    Text(
        text = buildAnnotatedString {
            rating?.let {
                withStyle(
                    SpanStyle(
                        color = MoviaBrandAmber,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append("★ $it")
                }
                if (parts.isNotEmpty()) append(" • ")
            }
            append(parts.joinToString(" • "))
        },
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
