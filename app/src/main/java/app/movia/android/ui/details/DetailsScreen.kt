package app.movia.android.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaDividerSubtle
import app.movia.android.ui.theme.MoviaScrim40
import app.movia.android.ui.theme.MoviaScrim70
import app.movia.android.ui.theme.MoviaRatingBadgeBackground
import app.movia.android.ui.theme.MoviaPrimaryAccentHover
import kotlinx.coroutines.launch
import kotlin.math.ceil

private val DetailsInfoFontSize = 16.sp
private val DetailsInfoLineHeight = 22.sp

private data class EpisodeUiState(
    val season: Int,
    val number: Int,
    val durationMinutes: Int = 46,
    val progress: PlaybackProgress = PlaybackProgress(),
) {
    val code: String = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
    val playbackTitle: String get() = "$code · Эпизод $number"
    val progressFraction: Float get() = progress.fraction
    val remainingMinutes: Int?
        get() = if (progress.durationMs > 0L) {
            ceil((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000.0).toInt()
        } else null
}

private fun episodeTitle(baseTitle: String, season: Int, episode: Int): String =
    "$baseTitle · S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · Эпизод $episode"

private fun seasonFromTitle(title: String): Int? =
    Regex(" · S(\\d{2})E\\d{2}").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun episodeFromTitle(title: String): Int? =
    Regex(" · S\\d{2}E(\\d{2})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    title: String,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    modifier: Modifier = Modifier,
    inMyList: Boolean = false,
    onMyListChange: (Boolean) -> Unit,
    downloads: Set<String> = emptySet(),
    onDownloadTitle: (String) -> Unit,
    progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    latestProgress: PlaybackProgress = PlaybackProgress(),
) {
    val content = remember(title) { DemoCatalogRepository.findByTitle(title) }
    val isTv = content?.type == ContentType.TV
    val seasonEpisodeCounts = content?.seasonEpisodeCounts.orEmpty()
    val hasEpisodes = seasonEpisodeCounts.isNotEmpty()
    val resume = latestProgress.takeIf { it.title == title || it.title.startsWith("$title · S") }
    val initialSeason = seasonFromTitle(resume?.title.orEmpty())
        ?.coerceIn(1, seasonEpisodeCounts.size.coerceAtLeast(1)) ?: 1
    var selectedSeason by remember(title, initialSeason) { mutableIntStateOf(initialSeason) }
    var synopsisExpanded by remember(title) { mutableStateOf(false) }
    var seasonScreenOpen by remember(title) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val heroOutOfView by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val appBarColor by animateColorAsState(
        targetValue = if (heroOutOfView) MaterialTheme.colorScheme.background else Color.Transparent,
        label = "detailsTopAppBar",
    )
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val resumeEpisode = episodeFromTitle(resume?.title.orEmpty())
    val playbackTitle = when {
        hasEpisodes && resumeEpisode != null -> resume!!.title
        hasEpisodes -> episodeTitle(title, 1, 1)
        else -> title
    }
    val genres = content?.genres?.sorted().orEmpty()
    val resumeRemainingMinutes = resume
        ?.takeIf { it.durationMs > 0L && it.positionMs > 0L }
        ?.let { ceil((it.durationMs - it.positionMs).coerceAtLeast(0L) / 60_000.0).toInt() }
    val hasStartedPlayback = resume?.positionMs?.let { it > 0L } == true
    val ctaPrimary = if (hasStartedPlayback) "Продолжить" else "Смотреть"
    val ctaSecondary = when {
        isTv -> if (hasStartedPlayback) "Продолжить эфир" else "Прямой эфир"
        hasEpisodes && hasStartedPlayback && resumeEpisode != null && resumeRemainingMinutes != null ->
            "$resumeEpisode серия · осталось $resumeRemainingMinutes мин"
        hasEpisodes && hasStartedPlayback && resumeEpisode != null -> "$resumeEpisode серия"
        hasEpisodes -> "1 сезон · 1 серия"
        hasStartedPlayback && resumeRemainingMinutes != null -> "осталось $resumeRemainingMinutes мин"
        else -> null
    }
    val catalogItems = remember { DemoCatalogRepository.all() }
    val franchiseItems = remember(content, catalogItems) {
        val franchiseIds = buildList {
            addAll(content?.sequelPrequelIds.orEmpty())
            addAll(content?.relatedContentIds.orEmpty())
        }.distinct()
        franchiseIds.mapNotNull { relatedId -> catalogItems.firstOrNull { it.id == relatedId } }
    }
    val similarItems = remember(content, catalogItems) {
        content?.let { current -> similarContentFor(current, catalogItems, limit = 8) }.orEmpty()
    }

    if (hasEpisodes && seasonScreenOpen) {
        SeasonEpisodesScreen(
            baseTitle = title,
            seasonEpisodeCounts = seasonEpisodeCounts,
            initialSeason = selectedSeason,
            progressByTitle = progressByTitle,
            onSeasonChange = { selectedSeason = it },
            onPlay = onPlay,
            onBack = { seasonScreenOpen = false },
            modifier = modifier,
        )
        return
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = navBottom + 32.dp),
        ) {
            item(key = "hero") {
                DetailsHero()
            }

            item(key = "identity") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.Start,
                ) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (content != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            horizontalAlignment = Alignment.Start,
                        ) {
                            DetailsMetadataLine(
                                content = content,
                                isTv = isTv,
                            )
                            if (genres.isNotEmpty()) {
                                Text(
                                    text = genres.joinToString(" • "),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = DetailsInfoFontSize,
                                    lineHeight = DetailsInfoLineHeight,
                                    fontWeight = FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "primary-action") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PrimaryWatchButton(
                        primaryText = ctaPrimary,
                        secondaryText = ctaSecondary,
                        onClick = { onPlay(playbackTitle) },
                    )
                    if (hasEpisodes) {
                        SeasonEpisodesButton(
                            onClick = { seasonScreenOpen = true },
                        )
                    }
                }
            }


            item(key = "synopsis") {
                InfoSection(
                    title = "Сюжет",
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    SynopsisText(
                        synopsis = content?.synopsis ?: "Описание пока недоступно.",
                        expanded = synopsisExpanded,
                        onToggle = { synopsisExpanded = !synopsisExpanded },
                    )
                }
            }

            val cast = content?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                item(key = "cast") {
                    CastSection(
                        cast = cast,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            val director = content?.director?.takeIf { it.isNotBlank() }
            if (director != null) {
                item(key = "director") {
                    InfoSection(
                        title = "Режиссёр",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = director,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = DetailsInfoFontSize,
                            lineHeight = DetailsInfoLineHeight,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }
            }

            if (franchiseItems.isNotEmpty()) {
                item(key = "franchise") {
                    MediaContentRowSection(
                        title = "Франшиза",
                        items = franchiseItems,
                        onOpenDetails = onOpenDetails,
                    )
                }
            }

            if (similarItems.isNotEmpty()) {
                item(key = "similar") {
                    MediaContentRowSection(
                        title = "Похожее",
                        items = similarItems,
                        onOpenDetails = onOpenDetails,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(appBarColor),
        ) {
            TopAppBar(
                title = {
                    if (heroOutOfView) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                navigationIcon = {
                    Surface(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(48.dp),
                        shape = CircleShape,
                        color = if (heroOutOfView) Color.Transparent else MoviaScrim40,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = "Назад",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                },
                actions = {
                    Surface(
                        onClick = { onMyListChange(!inMyList) },
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(48.dp),
                        shape = CircleShape,
                        color = if (heroOutOfView) Color.Transparent else MoviaScrim40,
                        contentColor = if (inMyList) MoviaBrandAmber else MaterialTheme.colorScheme.onSurface,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (inMyList) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = if (inMyList) "Убрать из избранного" else "Добавить в избранное",
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                windowInsets = WindowInsets.statusBars,
            )
            if (heroOutOfView) {
                HorizontalDivider(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    color = MoviaBorderSubtle,
                )
            }
        }
    }

}

@Composable
private fun DetailsHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio((16f / 9f) / 1.10f),
    ) {
        MediaArtworkPlaceholder(
            modifier = Modifier.fillMaxSize(),
            style = MediaArtworkPlaceholderStyle.HERO,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.54f to Color.Transparent,
                            1.00f to MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )
    }
}

@Composable
private fun DetailsMetadataLine(
    content: MediaContent,
    isTv: Boolean,
) {
    val metadata = buildList {
        if (content.year > 0) add(content.year.toString())
        if (content.ageRating > 0) add("${content.ageRating}+")
        add(if (isTv) "Прямой эфир" else if (content.durationMinutes > 0) formatDuration(content.durationMinutes) else "Длительность не указана")
        content.licenseName?.takeIf { it.isNotBlank() }?.let(::add)
    }
    val rating = content.imdbRating
    Text(
        text = buildAnnotatedString {
            if (rating != null) {
                withStyle(SpanStyle(color = MoviaBrandAmber, fontWeight = FontWeight.SemiBold)) {
                    append("★ ${String.format(java.util.Locale.US, "%.1f", rating)}")
                }
                if (metadata.isNotEmpty()) append(" • ")
            }
            append(metadata.joinToString(" • "))
        },
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = DetailsInfoFontSize,
        lineHeight = DetailsInfoLineHeight,
        fontWeight = FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeasonEpisodesScreen(
    baseTitle: String,
    seasonEpisodeCounts: List<Int>,
    initialSeason: Int,
    progressByTitle: Map<String, PlaybackProgress>,
    onSeasonChange: (Int) -> Unit,
    onPlay: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val pageCount = seasonEpisodeCounts.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = (initialSeason - 1).coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val dismissThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val swipeDownBack = remember(onBack, dismissThresholdPx) {
        object : NestedScrollConnection {
            var pullDistance = 0f

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    when {
                        available.y > 0f -> pullDistance += available.y
                        available.y < 0f -> pullDistance = 0f
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val shouldGoBack = pullDistance >= dismissThresholdPx
                pullDistance = 0f
                if (shouldGoBack) onBack()
                return Velocity.Zero
            }
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        onSeasonChange(pagerState.currentPage + 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .nestedScroll(swipeDownBack),
    ) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Назад",
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            actions = {},
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            windowInsets = WindowInsets.statusBars,
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            items((1..pageCount).toList(), key = { "season-$it" }) { season ->
                val selected = pagerState.currentPage == season - 1
                FilterChip(
                    selected = selected,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(season - 1) }
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        borderColor = MoviaBorderSubtle,
                        selectedBorderColor = MoviaBrandAmber,
                    ),
                    label = {
                        Text(
                            text = "Сезон $season",
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        selectedContainerColor = MoviaBrandAmber,
                        selectedLabelColor = MoviaOnBrandAmber,
                    ),
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            val season = page + 1
            val episodeCount = seasonEpisodeCounts.getOrElse(page) { 0 }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = navBottom + 24.dp,
                ),
            ) {
                items(
                    count = episodeCount,
                    key = { index -> "S$season-E${index + 1}" },
                ) { index ->
                    val number = index + 1
                    val fullTitle = episodeTitle(baseTitle, season, number)
                    EpisodeRow(
                        episode = EpisodeUiState(
                            season = season,
                            number = number,
                            progress = progressByTitle[fullTitle] ?: PlaybackProgress(title = fullTitle),
                        ),
                        onPlay = { onPlay(fullTitle) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonEpisodesButton(
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Text(
                    text = "Сезоны и серии",
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(20.dp),
            )
        }
    }
}

@Composable
private fun CastSection(
    cast: List<String>,
    modifier: Modifier = Modifier,
) {
    InfoSection(
        title = "В ролях",
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(cast, key = { "cast-$it" }) { actor ->
                ActorCard(actor)
            }
        }
    }
}

@Composable
private fun ActorCard(name: String) {
    Column(
        modifier = Modifier.width(96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        MediaArtworkPlaceholder(
            modifier = Modifier
                .size(76.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(14.dp)),
            style = MediaArtworkPlaceholderStyle.POSTER,
        )
        Text(
            text = name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MediaContentRowSection(
    title: String,
    items: List<MediaContent>,
    onOpenDetails: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title, Modifier.padding(horizontal = 16.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(items, key = { "$title-${it.id}" }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.width(136.dp),
                    onClick = { onOpenDetails(item.title) },
                )
            }
        }
    }
}

private fun similarContentFor(
    current: MediaContent,
    all: List<MediaContent>,
    limit: Int,
): List<MediaContent> {
    fun score(candidate: MediaContent): Int {
        val sharedGenres = candidate.genres.intersect(current.genres).size
        val sharedCast = candidate.cast.intersect(current.cast.toSet()).size
        val sameDirector = current.director != null && candidate.director == current.director
        val sameCountry = candidate.country == current.country
        val sameType = candidate.type == current.type
        return sharedGenres * 6 +
            sharedCast * 4 +
            (if (sameDirector) 5 else 0) +
            (if (sameCountry) 2 else 0) +
            (if (sameType) 1 else 0)
    }

    return all
        .asSequence()
        .filterNot { it.id == current.id }
        .sortedWith(
            compareByDescending<MediaContent> { score(it) }
                .thenByDescending { it.rating }
                .thenByDescending { it.popularity },
        )
        .take(limit)
        .toList()
}

@Composable
private fun InfoSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun SynopsisText(
    synopsis: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    var canExpand by remember(synopsis) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = synopsis,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = DetailsInfoFontSize,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Normal,
            maxLines = if (expanded) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layout ->
                if (!expanded) canExpand = layout.hasVisualOverflow
            },
        )
        if (expanded || canExpand) {
            TextButton(
                onClick = onToggle,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (expanded) "Свернуть" else "Подробнее",
                    color = MoviaBrandAmber,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PrimaryWatchButton(
    primaryText: String,
    secondaryText: String?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "watchButtonScale")
    val buttonColor by animateColorAsState(
        targetValue = if (pressed) MoviaPrimaryAccentHover else MoviaBrandAmber,
        label = "watchButtonColor",
    )

    Button(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(if (secondaryText == null) 56.dp else 68.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            contentColor = MoviaOnBrandAmber,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PlayCircleOutline,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = primaryText,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            secondaryText?.let { detail ->
                Text(
                    text = detail,
                    color = MoviaOnBrandAmber.copy(alpha = 0.68f),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: EpisodeUiState,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = episode.progressFraction
    val percent = (progress * 100).toInt().coerceIn(0, 100)
    val watched = progress >= 0.98f
    val status = when {
        watched -> "Просмотрено"
        progress > 0f -> "Осталось ${episode.remainingMinutes ?: 0} мин · Просмотрено $percent%"
        else -> "${episode.durationMinutes} мин"
    }

    Surface(
        onClick = onPlay,
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Эпизод ${episode.number}. $status"
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MoviaBrandAmber,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Воспроизвести эпизод ${episode.number}",
                                tint = MoviaBrandAmber,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "${episode.number}. Эпизод ${episode.number}",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = status,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (progress > 0f) {
                LinearProgressIndicator(
                    progress = { if (watched) 1f else progress },
                    color = if (watched) MaterialTheme.colorScheme.tertiary else MoviaBrandAmber,
                    trackColor = MoviaBorderSubtle,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        modifier = modifier,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "$hours ч $rest мин" else "$minutes мин"
}
