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
    onPlay: (MediaContent, String) -> Unit,
    onOpenDetails: (String, String?) -> Unit,
    modifier: Modifier = Modifier,
    inMyList: Boolean = false,
    onMyListChange: (Boolean) -> Unit,
    downloads: Set<String> = emptySet(),
    onDownloadTitle: (String) -> Unit,
    progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    latestProgress: PlaybackProgress = PlaybackProgress(),
) {
    val initialContent = remember(title, mediaId) {
        mediaId?.takeIf { it.isNotBlank() }?.let(DemoCatalogRepository::findById)
            ?: DemoCatalogRepository.findByTitle(title)
    }
    val contentState by produceState<MediaContent?>(initialValue = initialContent, title, mediaId) {
        value = withContext(Dispatchers.IO) {
            mediaId?.takeIf { it.isNotBlank() }?.let(DemoCatalogRepository::findFullById)
                ?: DemoCatalogRepository.findFullByTitle(title)
                ?: initialContent
        }
    }
    val content = contentState ?: initialContent
    val isTv = content?.type == ContentType.TV
    val seasonEpisodeCounts = content?.seasonEpisodeCounts.orEmpty()
    val hasEpisodes = seasonEpisodeCounts.isNotEmpty()
    val isSeries = content?.type == ContentType.SERIES || content?.type == ContentType.TV || hasEpisodes
    val resume = latestProgress.takeIf { it.title == title || it.title.startsWith("$title · S") }
    val initialSeason = seasonFromTitle(resume?.title.orEmpty())
        ?.coerceIn(1, seasonEpisodeCounts.size.coerceAtLeast(1)) ?: 1
    var selectedSeason by remember(title, initialSeason) { mutableIntStateOf(initialSeason) }
    var synopsisExpanded by remember(title) { mutableStateOf(false) }
    var seasonScreenOpen by remember(title) { mutableStateOf(false) }
    var streamOptionsOpen by remember(title) { mutableStateOf(false) }
    var selectedQuality by remember(title) { mutableStateOf("1080p") }
    var selectedAudio by remember(title) { mutableStateOf("Дубляж") }
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

    if (hasEpisodes && seasonScreenOpen) {
        SeasonEpisodesScreen(
            baseTitle = title,
            seasonEpisodeCounts = seasonEpisodeCounts,
            initialSeason = selectedSeason,
            progressByTitle = progressByTitle,
            onSeasonChange = { selectedSeason = it },
            onPlay = { episodeTitle -> content?.let { onPlay(it, episodeTitle) } },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 64.dp),
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
                        onClick = { content?.let { onPlay(it, playbackTitle) } },
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
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            val director = content?.director?.takeIf { it.isNotBlank() }
            if (director != null) {
                item(key = "director") {
                    InfoSection(
                        title = if (isSeries) "Создатель" else "Режиссёр",
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

    if (streamOptionsOpen) {
        StreamQualityAudioSheet(
            selectedQuality = selectedQuality,
            onQualitySelected = { selectedQuality = it },
            selectedAudio = selectedAudio,
            onAudioSelected = { selectedAudio = it },
            onPlay = { content?.let { onPlay(it, playbackTitle) } },
            onDismiss = { streamOptionsOpen = false },
        )
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
@Composable
private fun DetailsMetadataLine(
    content: MediaContent,
    isTv: Boolean,
) {
    val showRating = content.rating > 0.0
    val ratingText = if (showRating) String.format(Locale.US, "%.1f", content.rating) else null
    val typeLabel = moviaContentTypeLabel(content)
    val isSeries = content.type == ContentType.SERIES || content.category == CatalogCategory.TV_SERIES || content.category == CatalogCategory.LIMITED_SERIES || content.seasonEpisodeCounts.isNotEmpty() || content.seasonsCount > 0

    val metadata = buildList<String> {
        if (content.year > 0) add(content.year.toString())
        if (content.country.isNotBlank()) {
            add(content.country)
        } else {
            add("Зарубежный")
        }
        if (isTv) {
            add("ТВ")
            add("Прямой эфир")
        } else if (isSeries) {
            val seasonCount = content.seasonsCount.takeIf { it > 0 } ?: content.seasonEpisodeCounts.size.takeIf { it > 0 }
            val episodesCount = content.episodesCount.takeIf { it > 0 }
            if (seasonCount != null) {
                val sSuffix = when {
                    seasonCount % 10 == 1 && seasonCount % 100 != 11 -> "сезон"
                    seasonCount % 10 in 2..4 && seasonCount % 100 !in 12..14 -> "сезона"
                    else -> "сезонов"
                }
                if (episodesCount != null) {
                    add("$seasonCount $sSuffix ($episodesCount серий)")
                } else {
                    add("$seasonCount $sSuffix")
                }
            } else {
                add("Сериал")
            }
            if (content.durationMinutes > 0) {
                add("${content.durationMinutes} мин/серия")
            }
        } else {
            add(typeLabel)
            if (content.durationMinutes > 0) {
                add("${content.durationMinutes} мин")
            }
        }
    }

    Text(
        text = buildAnnotatedString {
            if (ratingText != null) {
                withStyle(
                    SpanStyle(
                        color = MoviaBrandAmber,
                        fontWeight = FontWeight.SemiBold,
                    ),
                ) {
                    append("★ $ratingText")
                }
                if (metadata.isNotEmpty()) append(" • ")
            } else {
                withStyle(
                    SpanStyle(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                    ),
                ) {
                    append("—")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StreamQualityAudioSheet(
    selectedQuality: String,
    onQualitySelected: (String) -> Unit,
    selectedAudio: String,
    onAudioSelected: (String) -> Unit,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
) {
    val qualities = listOf("1080p", "720p", "480p", "4K")
    val audios = listOf("Дубляж", "LostFilm", "HDRezka", "TVShows", "Кураж-Бамбей", "Оригинал")
    val scheme = MaterialTheme.colorScheme

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Качество и озвучка",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Закрыть", tint = scheme.onSurface)
                }
            }

            Text(
                text = "КАЧЕСТВО ПОТОКА",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(qualities) { q ->
                    val isSelected = selectedQuality == q
                    FilterChip(
                        selected = isSelected,
                        onClick = { onQualitySelected(q) },
                        label = { Text(q, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MoviaBrandAmber,
                            selectedLabelColor = MoviaOnBrandAmber,
                            containerColor = scheme.surfaceContainer,
                            labelColor = scheme.onSurface,
                        ),
                    )
                }
            }

            Text(
                text = "ВАРИАНТ ОЗВУЧКИ / ИСТОЧНИК",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(audios) { a ->
                    val isSelected = selectedAudio == a
                    FilterChip(
                        selected = isSelected,
                        onClick = { onAudioSelected(a) },
                        label = { Text(a, maxLines = 1) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MoviaBrandAmber,
                            selectedLabelColor = MoviaOnBrandAmber,
                            containerColor = scheme.surfaceContainer,
                            labelColor = scheme.onSurface,
                        ),
                    )
                }
            }

            Button(
                onClick = {
                    onDismiss()
                    onPlay()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MoviaBrandAmber, contentColor = MoviaOnBrandAmber),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("Смотреть в $selectedQuality ($selectedAudio)", fontWeight = FontWeight.Bold)
            }
        }
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
                        scope.launch { pagerState.scrollToPage(season - 1) }
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
        color = Color(0xFF1E2129),
        contentColor = Color.White,
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
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Text(
                    text = "Выбор сезона и серий",
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.72f),
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
    modifier: Modifier = Modifier,
) {
    InfoSection(
        title = "В ролях",
        modifier = modifier,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = cast,
                key = { person -> "cast-${person.name}-${person.photoUrl.orEmpty()}" },
            ) { person ->
                ActorCard(person)
            }
        }
    }
}

@Composable
private fun ActorCard(person: Person) {
    Column(
        modifier = Modifier.width(88.dp),
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
                .background(Color(0xFF1B1E26))
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
        val displayRole = castRoleForDisplay(person.role)
        if (!displayRole.isNullOrBlank()) {
            Text(
                text = displayRole,
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

private fun castRoleForDisplay(role: String?): String? {
    val raw = role?.trim().orEmpty()
    if (raw.isBlank()) return null
    val hasCyrillic = Regex("[А-Яа-яЁё]").containsMatchIn(raw)
    if (hasCyrillic) {
        return raw.replace("(voice)", "(озвучка)", ignoreCase = true)
    }
    return if (raw.contains("(voice)", ignoreCase = true)) "Озвучка" else null
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
    onOpenDetails: (String, String?) -> Unit,
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
                    onClick = { onOpenDetails(item.title, item.id) },
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
