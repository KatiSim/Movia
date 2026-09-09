package app.movia.android.ui.details

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.moviaContentTypeLabel
import app.movia.android.ui.components.moviaLocalizedGenreList
import app.movia.android.ui.components.moviaLocalizedCountry
import app.movia.android.ui.components.moviaPrimaryGenre
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaDividerSubtle
import app.movia.android.ui.theme.MoviaScrim40
import app.movia.android.ui.theme.MoviaScrim60
import app.movia.android.ui.theme.MoviaScrim70
import app.movia.android.ui.theme.MoviaRatingBadgeBackground
import app.movia.android.ui.theme.MoviaPrimaryAccentHover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
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
    mediaId: String? = null,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onOpenDetails: (MediaContent) -> Unit,
    modifier: Modifier = Modifier,
    inMyList: Boolean = false,
    onMyListChange: (Boolean) -> Unit,
    downloads: Set<String> = emptySet(),
    onDownloadTitle: (String) -> Unit,
    progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    latestProgress: PlaybackProgress = PlaybackProgress(),
) {
    val initialContent = remember(mediaId, title) {
        mediaId?.let(DemoCatalogRepository::findById) ?: DemoCatalogRepository.findByTitle(title)
    }
    val contentState by produceState<MediaContent?>(initialValue = initialContent, mediaId, title) {
        value = withContext(Dispatchers.IO) {
            if (!mediaId.isNullOrBlank()) {
                DemoCatalogRepository.findFullById(mediaId) ?: initialContent
            } else {
                DemoCatalogRepository.findFullByTitle(title) ?: initialContent
            }
        }
    }
    val content = contentState ?: initialContent
    val isTv = content?.type == ContentType.TV
    val seasonEpisodeCounts = content?.seasonEpisodeCounts.orEmpty()
    val hasEpisodes = seasonEpisodeCounts.isNotEmpty()
    val isSeries = content?.type == ContentType.SERIES || content?.type == ContentType.TV || hasEpisodes
    val resume = latestProgress.takeIf {
        it.isResumable && (it.title == title || it.title.startsWith("$title · S"))
    }
    val initialSeason = seasonFromTitle(resume?.title.orEmpty())
        ?.coerceIn(1, seasonEpisodeCounts.size.coerceAtLeast(1)) ?: 1
    var selectedSeason by remember(title, initialSeason) { mutableIntStateOf(initialSeason) }
    var synopsisExpanded by remember(title) { mutableStateOf(false) }
    var seasonScreenOpen by remember(title) { mutableStateOf(false) }
    var selectedPerson by remember(mediaId, title) { mutableStateOf<Person?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(content?.id, title) {
        listState.scrollToItem(0)
    }
    val heroOutOfView by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }
    val appBarColor = if (heroOutOfView) MaterialTheme.colorScheme.background else Color.Transparent
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val resumeEpisode = episodeFromTitle(resume?.title.orEmpty())
    val playbackTitle = when {
        hasEpisodes && resumeEpisode != null -> resume!!.title
        hasEpisodes -> episodeTitle(title, 1, 1)
        else -> title
    }
    val genres = content?.let { moviaLocalizedGenreList(it.genres, limit = 3) }.orEmpty()
    val resumeRemainingMinutes = resume
        ?.takeIf { it.durationMs > 0L && it.positionMs > 0L }
        ?.let { ceil((it.durationMs - it.positionMs).coerceAtLeast(0L) / 60_000.0).toInt() }
    val hasStartedPlayback = resume?.positionMs?.let { it > 0L } == true
    val downloadTarget = if (hasEpisodes && resumeEpisode != null) resume!!.title else title
    val isDownloaded = downloadTarget in downloads
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
    val franchiseItems by produceState<List<MediaContent>>(initialValue = emptyList(), content?.id) {
        value = withContext(Dispatchers.IO) {
            content?.id?.let { movieId ->
                DemoCatalogRepository.getSequelsAndPrequels(movieId, limit = 15)
            }.orEmpty()
        }
    }
    val similarItems by produceState<List<MediaContent>>(initialValue = emptyList(), content?.id, franchiseItems) {
        value = withContext(Dispatchers.IO) {
            val excludedIds = franchiseItems.map { it.id }.toSet()
            content?.let { current ->
                DemoCatalogRepository.getSimilar(current, limit = 8)
                    .filterNot { it.id in excludedIds }
            }.orEmpty()
        }
    }

    val creativeCreditTitle = if (isSeries) "Создатели" else "Режиссёр"
    val creativeNames = content?.director
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        ?.take(4)
        .orEmpty()
    val creativePeople by produceState(
        initialValue = creativeNames.map { Person(name = it, role = creativeCreditTitle) },
        content?.id,
        creativeNames.joinToString("|"),
    ) {
        value = withContext(Dispatchers.IO) {
            creativeNames.map { name ->
                val resolved = DemoCatalogRepository.getPersonProjects(name, limit = 200).person
                resolved.copy(name = resolved.name.ifBlank { name }, role = creativeCreditTitle)
            }
        }
    }

    selectedPerson?.let { person ->
        PersonProjectsScreen(
            seedPerson = person,
            onBack = { selectedPerson = null },
            onOpenDetails = onOpenDetails,
            modifier = modifier,
        )
        return
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
                DetailsHero(
                    backdropUrl = content?.backdropUrl,
                    posterUrl = content?.posterUrl,
                    onSwipeDown = onBack,
                )
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
                        DetailsMetadataLine(
                            content = content,
                            isTv = isTv,
                        )
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

            item(key = "quick-actions") {
                QuickActionsRow(
                    title = title,
                    sourceUrl = content?.sourceUrl,
                    isDownloaded = isDownloaded,
                    onToggleDownload = { onDownloadTitle(downloadTarget) },
                )
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
                        onPersonClick = { person -> selectedPerson = person.copy(role = "Актёр") },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (creativePeople.isNotEmpty()) {
                item(key = "creative-credits") {
                    PeopleSection(
                        title = creativeCreditTitle,
                        people = creativePeople,
                        onPersonClick = { selectedPerson = it.copy(role = creativeCreditTitle) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            if (franchiseItems.isNotEmpty()) {
                item(key = "franchise") {
                    MediaContentRowSection(
                        title = "Сиквелы и приквелы",
                        items = franchiseItems,
                        activeId = content?.id,
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
                .background(appBarColor)
                .swipeDownToDismiss(onBack),
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

private fun Modifier.swipeDownToDismiss(
    onDismiss: () -> Unit,
): Modifier = pointerInput(onDismiss) {
    var dragDistance = 0f
    detectVerticalDragGestures(
        onDragStart = { dragDistance = 0f },
        onVerticalDrag = { _, dragAmount ->
            if (dragAmount > 0f) {
                dragDistance += dragAmount
            } else if (dragAmount < 0f) {
                dragDistance = 0f
            }
        },
        onDragEnd = {
            if (dragDistance >= 48.dp.toPx()) onDismiss()
            dragDistance = 0f
        },
        onDragCancel = { dragDistance = 0f },
    )
}

@Composable
private fun DetailsHero(
    backdropUrl: String?,
    posterUrl: String?,
    onSwipeDown: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val heroHeight = if (isLandscape) {
        (configuration.screenHeightDp * 0.58f).dp
    } else {
        (configuration.screenHeightDp * 0.42f).dp
    }

    val heroShape = RoundedCornerShape(
        bottomStart = 24.dp,
        bottomEnd = 24.dp,
    )
    val posterShape = RoundedCornerShape(18.dp)
    val hasBackdrop = !backdropUrl.isNullOrBlank()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(heroHeight)
            .clip(heroShape)
            .background(MaterialTheme.colorScheme.surface)
            .swipeDownToDismiss(onSwipeDown),
        contentAlignment = Alignment.Center,
    ) {
        when {
            hasBackdrop -> {
                // A real horizontal backdrop is the only artwork shown in the hero.
                MoviaArtwork(
                    url = backdropUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholderStyle = MediaArtworkPlaceholderStyle.HERO,
                )
            }

            !posterUrl.isNullOrBlank() -> {
                // If no backdrop exists, build a cinematic fallback from the poster:
                // a soft blurred/glowing background plus one correctly fitted poster.
                MoviaArtwork(
                    url = posterUrl,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = 0.34f
                            scaleX = 1.16f
                            scaleY = 1.16f
                        }
                        .blur(28.dp),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholderStyle = MediaArtworkPlaceholderStyle.HERO,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    MoviaBrandAmber.copy(alpha = 0.20f),
                                    Color.Transparent,
                                ),
                                radius = 900f,
                            ),
                        ),
                )
                MoviaArtwork(
                    url = posterUrl,
                    modifier = Modifier
                        .fillMaxHeight(0.88f)
                        .aspectRatio(2f / 3f)
                        .clip(posterShape)
                        .border(1.dp, MoviaBorderSubtle, posterShape),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
                )
            }

            else -> {
                MediaArtworkPlaceholder(
                    modifier = Modifier.fillMaxSize(),
                    style = MediaArtworkPlaceholderStyle.HERO,
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.40f to Color.Transparent,
                            0.70f to MoviaScrim40,
                            0.86f to MoviaScrim60,
                            1.00f to MaterialTheme.colorScheme.background,
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, MoviaBorderSubtle, heroShape),
        )
    }
}
internal data class DetailsMetadataRows(
    val ratingText: String?,
    val primary: List<String>,
    val secondary: List<String>,
)

internal fun detailsMetadataRows(
    content: MediaContent,
    isTv: Boolean,
): DetailsMetadataRows {
    val ratingText = content.rating
        .takeIf { it > 0.0 }
        ?.let { String.format(Locale.US, "%.1f", it) }
    val typeLabel = moviaContentTypeLabel(content)
    val isSeries = content.type == ContentType.SERIES ||
        content.category == CatalogCategory.TV_SERIES ||
        content.category == CatalogCategory.LIMITED_SERIES ||
        content.seasonEpisodeCounts.isNotEmpty() ||
        content.seasonsCount > 0

    val primary = buildList {
        if (content.year > 0) add(content.year.toString())
        content.country
            .takeIf { it.isNotBlank() }
            ?.let(::moviaLocalizedCountry)
            ?.takeIf { it.isNotBlank() }
            ?.let(::add)
        moviaPrimaryGenre(content)?.let(::add)
    }

    val secondary = buildList {
        when {
            isTv -> {
                add(typeLabel)
                add("Прямой эфир")
            }
            isSeries -> {
                val seasonCount = content.seasonsCount.takeIf { it > 0 }
                    ?: content.seasonEpisodeCounts.size.takeIf { it > 0 }
                val episodesCount = content.episodesCount.takeIf { it > 0 }
                    ?: content.seasonEpisodeCounts.sum().takeIf { it > 0 }

                seasonCount?.let { count -> add("$count ${seasonWord(count)}") }
                episodesCount?.let { count -> add("$count ${episodeWord(count)}") }
                if (content.durationMinutes > 0) add("${content.durationMinutes} мин/серия")
            }
            else -> {
                add(typeLabel)
                if (content.durationMinutes > 0) add("${content.durationMinutes} мин")
            }
        }
    }

    return DetailsMetadataRows(
        ratingText = ratingText,
        primary = primary.distinct(),
        secondary = secondary.distinct(),
    )
}

private fun seasonWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "сезон"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "сезона"
    else -> "сезонов"
}

private fun episodeWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "серия"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "серии"
    else -> "серий"
}

@Composable
private fun DetailsMetadataLine(
    content: MediaContent,
    isTv: Boolean,
) {
    val rows = detailsMetadataRows(content, isTv)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (rows.ratingText != null || rows.primary.isNotEmpty()) {
            Text(
                text = buildAnnotatedString {
                    rows.ratingText?.let { rating ->
                        withStyle(
                            SpanStyle(
                                color = MoviaBrandAmber,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append("★ $rating")
                        }
                        if (rows.primary.isNotEmpty()) append(" • ")
                    }
                    append(rows.primary.joinToString(" • "))
                },
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = DetailsInfoFontSize,
                lineHeight = DetailsInfoLineHeight,
                fontWeight = FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (rows.secondary.isNotEmpty()) {
            Text(
                text = rows.secondary.joinToString(" • "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RatingStrip(
    content: MediaContent,
    modifier: Modifier = Modifier,
) {
    val ratings = buildList {
        content.rating
            .takeIf { it > 0.0 }
            ?.let { add("Movia" to it) }
        content.imdbRating
            ?.takeIf { it > 0.0 }
            ?.let { add("IMDb" to it) }
    }
    if (ratings.isEmpty()) return

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ratings.forEach { (source, value) ->
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MoviaBorderSubtle),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = "★ " + String.format(java.util.Locale.US, "%.1f", value),
                        color = MoviaBrandAmber,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = source,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    title: String,
    sourceUrl: String?,
    isDownloaded: Boolean,
    onToggleDownload: () -> Unit,
) {
    val context = LocalContext.current
    val shareText = listOfNotNull(title, sourceUrl?.takeIf { it.isNotBlank() }).joinToString("\n")
    val share = {
        runCatching {
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    },
                    "Поделиться",
                ),
            )
        }
        Unit
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DetailsQuickAction(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Download,
            label = if (isDownloaded) "Скачано" else "Скачать",
            active = isDownloaded,
            onClick = onToggleDownload,
        )
        DetailsQuickAction(
            modifier = Modifier.weight(1f),
            icon = Icons.Outlined.Share,
            label = "Поделиться",
            active = false,
            onClick = share,
        )
    }
}

@Composable
private fun DetailsQuickAction(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(64.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = if (active) MoviaBrandAmber else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, if (active) MoviaBrandAmber else MoviaBorderSubtle),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) MoviaBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                color = if (active) MoviaBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
    val seasonListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val dismissThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    var headerDragDistance by remember { mutableStateOf(0f) }

    LaunchedEffect(pagerState.currentPage) {
        val selectedIndex = pagerState.currentPage
        onSeasonChange(selectedIndex + 1)
        // Keep the selected season in the viewport with one neighbour as context.
        seasonListState.animateScrollToItem((selectedIndex - 1).coerceAtLeast(0))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The header is the only dismiss gesture target. Horizontal season swipes can
        // therefore never leak into a close action at the first/last page.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusTop)
                .height(52.dp)
                .pointerInput(onBack, dismissThresholdPx) {
                    detectVerticalDragGestures(
                        onDragStart = { headerDragDistance = 0f },
                        onDragCancel = { headerDragDistance = 0f },
                        onDragEnd = {
                            if (headerDragDistance >= dismissThresholdPx) onBack()
                            headerDragDistance = 0f
                        },
                    ) { _, dragAmount ->
                        headerDragDistance = (headerDragDistance + dragAmount).coerceAtLeast(0f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)),
            )
        }

        LazyRow(
            state = seasonListState,
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
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
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
                modifier = Modifier.align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier.size(30.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.PlaylistPlay,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    text = "Выбор сезона и серий",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(22.dp),
            )
        }
    }
}

@Composable
private fun CastSection(
    cast: List<Person>,
    onPersonClick: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    PeopleSection(
        title = "В ролях",
        people = cast,
        onPersonClick = onPersonClick,
        modifier = modifier,
    )
}

@Composable
private fun PeopleSection(
    title: String,
    people: List<Person>,
    onPersonClick: (Person) -> Unit,
    modifier: Modifier = Modifier,
) {
    InfoSection(title = title, modifier = modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(
                items = people,
                key = { person -> "$title-${person.name}-${person.photoUrl.orEmpty()}" },
            ) { person ->
                ActorCard(person = person, onClick = { onPersonClick(person) })
            }
        }
    }
}

@Composable
private fun ActorCard(person: Person, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(88.dp)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val actorBitmap by produceState<Bitmap?>(initialValue = null, key1 = person.photoUrl) {
            value = loadActorBitmap(person.photoUrl)
        }
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MoviaBorderSubtle, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val imageBitmap = actorBitmap?.asImageBitmap()
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = person.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = person.name.trim().firstOrNull()?.uppercase() ?: "?",
                    color = MoviaBrandAmber,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            text = person.name,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 12.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!person.role.isNullOrBlank()) {
            Text(
                text = person.role,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PersonProjectsScreen(
    seedPerson: Person,
    onBack: () -> Unit,
    onOpenDetails: (MediaContent) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    val result by produceState<app.movia.android.data.catalog.PersonProjectsResult?>(
        initialValue = null,
        seedPerson.name,
    ) {
        value = withContext(Dispatchers.IO) {
            DemoCatalogRepository.getPersonProjects(seedPerson.name, limit = 200)
        }
    }
    val resolved = result?.person
    val person = Person(
        name = resolved?.name?.takeIf { it.isNotBlank() } ?: seedPerson.name,
        photoUrl = resolved?.photoUrl ?: seedPerson.photoUrl,
        role = localizedPersonDepartment(resolved?.role) ?: seedPerson.role,
        knownFor = result?.projects?.take(8)?.map { it.title } ?: seedPerson.knownFor,
    )
    val projects = result?.projects.orEmpty()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = topInset + 8.dp,
            bottom = navBottom + 24.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "person-header-${seedPerson.name}") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                        )
                    }
                    Text(
                        text = person.name,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 26.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PersonAvatar(person = person, size = 104.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        person.role?.takeIf { it.isNotBlank() }?.let { role ->
                            Text(
                                text = role,
                                color = MoviaBrandAmber,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Text(
                            text = if (projects.isEmpty() && result == null) {
                                "Загружаем фильмографию…"
                            } else {
                                "Проекты в Movia: ${projects.size}"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                }
                Text(
                    text = "Фильмография",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (projects.isEmpty() && result != null) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "person-empty") {
                Text(
                    text = "В каталоге Movia пока нет доступных проектов этого человека.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            gridItems(projects, key = { it.id }) { project ->
                MediaContentCard(
                    item = project,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenDetails(project) },
                )
            }
        }
    }
}

@Composable
private fun PersonAvatar(person: Person, size: androidx.compose.ui.unit.Dp) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = person.photoUrl) {
        value = loadActorBitmap(person.photoUrl)
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MoviaBorderSubtle, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap?.asImageBitmap()
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = person.name.trim().firstOrNull()?.uppercase() ?: "?",
                color = MoviaBrandAmber,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun localizedPersonDepartment(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.ROOT)) {
    "acting" -> "Актёр"
    "directing" -> "Режиссёр"
    "writing" -> "Сценарист"
    "production" -> "Продюсер"
    "sound" -> "Музыка и звук"
    "camera" -> "Оператор"
    "art" -> "Художник"
    null, "" -> null
    else -> raw
}

private suspend fun loadActorBitmap(url: String?): Bitmap? = withContext(Dispatchers.IO) {
    if (url.isNullOrBlank()) return@withContext null
    val connection = try {
        URL(url).openConnection() as? HttpURLConnection
    } catch (_: Exception) {
        null
    } ?: return@withContext null

    try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 8_000
        connection.instanceFollowRedirects = true
        if (connection.responseCode !in 200..299) return@withContext null
        connection.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun MediaContentRowSection(
    title: String,
    items: List<MediaContent>,
    activeId: String? = null,
    onOpenDetails: (MediaContent) -> Unit,
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
                    posterBorder = if (item.id == activeId) MoviaBrandAmber else MoviaBorderSubtle,
                    onClick = { onOpenDetails(item) },
                )
            }
        }
    }
}

private fun similarContentFor(
    current: MediaContent,
    all: List<MediaContent>,
    excludedIds: Set<String> = emptySet(),
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
        .filterNot { it.id == current.id || it.id in excludedIds }
        .filter {
            val candidateRating = it.rating.takeIf { value -> value > 0.0 } ?: it.imdbRating
            candidateRating == null || candidateRating >= 5.5
        }
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
            maxLines = if (expanded) Int.MAX_VALUE else 3,
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
    val scale = if (pressed) 0.98f else 1f
    val buttonColor = if (pressed) MoviaPrimaryAccentHover else MoviaBrandAmber

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
