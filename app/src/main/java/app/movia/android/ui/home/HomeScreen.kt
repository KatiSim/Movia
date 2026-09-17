package app.movia.android.ui.home

import android.content.res.Configuration
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.data.catalog.CatalogSort
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.RecommendationEngine
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.SectionHeader
import app.movia.android.ui.components.moviaContentTypeLabel
import app.movia.android.ui.components.moviaPrimaryGenre
import app.movia.android.ui.components.moviaDisplayTitle
import app.movia.android.ui.components.moviaDetailsMetadataFacts
import app.movia.android.ui.components.moviaRatingLabel
import app.movia.android.ui.components.moviaRemainingMinutes
import app.movia.android.ui.components.moviaYearLabel
import app.movia.android.ui.catalog.CatalogLaunchPreset
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaLogoGradientStart
import app.movia.android.ui.theme.MoviaLogoGradientSoftGold
import app.movia.android.ui.theme.MoviaLogoGradientPastelGold
import app.movia.android.ui.theme.MoviaLogoGradientLightBronze
import app.movia.android.ui.theme.MoviaLogoGradientCream
import app.movia.android.ui.theme.MoviaLogoGradientIvory
import app.movia.android.ui.theme.MoviaLogoGradientMilk
import app.movia.android.ui.theme.MoviaLogoGradientEnd
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaOnBrandAmber

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress = PlaybackProgress(),
    history: List<String> = emptyList(),
    favorites: Set<String> = emptySet(),
    onOpenDetails: (String) -> Unit,
    onContinue: (String) -> Unit,
    onOpenCatalog: (CatalogLaunchPreset) -> Unit,
) {
    val recommendation = remember(history, favorites) {
        RecommendationEngine.recommend(history, favorites = favorites, limit = 20)
    }
    val popularPool by produceState<List<MediaContent>>(initialValue = DemoCatalogRepository.getPopular(12)) {
        value = withContext(Dispatchers.IO) {
            DemoCatalogRepository.getPopular(12)
        }
    }
    val newPool by produceState<List<MediaContent>>(initialValue = DemoCatalogRepository.getNew(12)) {
        value = withContext(Dispatchers.IO) {
            DemoCatalogRepository.getNew(12)
        }
    }
    val forYouPool by produceState<List<MediaContent>>(initialValue = DemoCatalogRepository.getForYou(12)) {
        value = withContext(Dispatchers.IO) {
            DemoCatalogRepository.getForYou(12)
        }
    }
    val seriesPool by produceState<List<MediaContent>>(initialValue = DemoCatalogRepository.getSeries(12)) {
        value = withContext(Dispatchers.IO) {
            DemoCatalogRepository.getSeries(12)
        }
    }
    val heroPool by produceState<List<MediaContent>>(initialValue = DemoCatalogRepository.getHero(3)) {
        value = withContext(Dispatchers.IO) {
            DemoCatalogRepository.getHero(3)
        }
    }

    val newItems = remember(newPool) { newPool.take(12) }
    val popularItems = remember(popularPool) { popularPool.take(12) }
    val forYouItems = remember(forYouPool, recommendation.items) {
        if (recommendation.items.isNotEmpty()) recommendation.items.take(12) else forYouPool.take(12)
    }
    val seriesItems = remember(seriesPool) { seriesPool.take(12) }
    val topNewHit = remember(newItems, newPool) { newItems.firstOrNull() ?: newPool.firstOrNull() }
    val topHeroHit = remember(heroPool, topNewHit) { heroPool.firstOrNull() ?: topNewHit }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 0.dp,
            top = 0.dp,
            end = 0.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "continue") {
            ContinueWatchingCard(
                progress = progress,
                fallbackItem = topHeroHit,
                onContinue = onContinue,
                onOpenDetails = onOpenDetails,
            )
        }

        if (newItems.isNotEmpty()) {
            item(key = "new") {
                HomeMediaSection(
                    title = "Новинки",
                    items = newItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.NEW) },
                )
            }
        }

        if (popularItems.isNotEmpty()) {
            item(key = "popular-now") {
                HomeMediaSection(
                    title = "Сейчас популярно",
                    items = popularItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.POPULAR) },
                )
            }
        }

        if (forYouItems.isNotEmpty()) {
            item(key = "for-you") {
                HomeMediaSection(
                    title = "Для вас",
                    items = forYouItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.RECOMMENDED) },
                )
            }
        }

        if (seriesItems.isNotEmpty()) {
            item(key = "series-section") {
                HomeMediaSection(
                    title = "Сериалы и Мультсериалы",
                    items = seriesItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                )
            }
        }
    }
}

@Composable
private fun HomeHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Movia",
            style = TextStyle(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0.00f to MoviaLogoGradientStart,
                        0.10f to MoviaLogoGradientStart,
                        0.20f to MoviaLogoGradientSoftGold,
                        0.30f to MoviaLogoGradientPastelGold,
                        0.40f to MoviaLogoGradientLightBronze,
                        0.50f to MoviaLogoGradientCream,
                        0.60f to MoviaLogoGradientIvory,
                        0.70f to MoviaLogoGradientMilk,
                        0.80f to MoviaLogoGradientEnd,
                        0.90f to MoviaLogoGradientEnd,
                        1.00f to MoviaLogoGradientEnd,
                    ),
                ),
                fontFamily = FontFamily.SansSerif,
                fontSize = 31.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.15.sp,
            ),
        )
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: PlaybackProgress,
    fallbackItem: MediaContent?,
    onContinue: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val hasRealProgress = progress.title.isNotBlank() && progress.positionMs > 0L
    val playbackTitle = if (hasRealProgress) progress.title else (fallbackItem?.title ?: "")
    val displayTitle = if (hasRealProgress) {
        moviaDisplayTitle(playbackTitle)
    } else {
        fallbackItem?.title?.let(::moviaDisplayTitle) ?: "Новинка"
    }

    // Resume entries are lightweight. Enrich the current title so Home uses the same
    // artwork and metadata contract as Details for every movie and series.
    val initialHeroContent = remember(progress.contentId, displayTitle, fallbackItem) {
        if (hasRealProgress) {
            progress.contentId?.let(DemoCatalogRepository::findById)
                ?: DemoCatalogRepository.findByTitle(displayTitle)
        } else {
            fallbackItem
        }
    }
    val heroContent by produceState<MediaContent?>(
        initialValue = initialHeroContent,
        progress.contentId,
        displayTitle,
        hasRealProgress,
        fallbackItem,
    ) {
        value = withContext(Dispatchers.IO) {
            when {
                hasRealProgress && !progress.contentId.isNullOrBlank() ->
                    DemoCatalogRepository.findFullById(progress.contentId!!)
                        ?: DemoCatalogRepository.findFullByTitle(displayTitle)
                        ?: initialHeroContent
                hasRealProgress ->
                    DemoCatalogRepository.findFullByTitle(displayTitle) ?: initialHeroContent
                fallbackItem != null ->
                    DemoCatalogRepository.findFullById(fallbackItem.id)
                        ?: DemoCatalogRepository.findFullByTitle(fallbackItem.title)
                        ?: fallbackItem
                else -> initialHeroContent
            }
        }
    }

    val backdropUrl = heroContent?.backdropUrl?.takeIf { it.isNotBlank() }
    val posterUrl = heroContent?.posterUrl?.takeIf { it.isNotBlank() }
    val hasBackdrop = !backdropUrl.isNullOrBlank()
    val episodeMatch = Regex(""" · S(\d{2})E(\d{2})""").find(playbackTitle)
    val season = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
    val remainingMinutes = if (hasRealProgress) {
        moviaRemainingMinutes(progress.positionMs, progress.durationMs) ?: 0
    } else 0
    val ratingText = heroContent?.rating?.let(::moviaRatingLabel)
    val metadataFacts = heroContent?.let(::moviaDetailsMetadataFacts).orEmpty()
    val metadataTail = metadataFacts.joinToString(" • ")
    val actionLabel = if (hasRealProgress) "Продолжить" else "Смотреть"
    val actionDetail = when {
        hasRealProgress && season != null && episode != null && remainingMinutes > 0 ->
            "Сезон $season · Серия $episode · осталось $remainingMinutes мин"
        hasRealProgress && season != null && episode != null -> "Сезон $season · Серия $episode"
        hasRealProgress && remainingMinutes > 0 -> "осталось $remainingMinutes мин"
        else -> null
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Match DetailsHero exactly. The old 490dp minimum forced a portrait-shaped crop,
    // which made every backdrop look heavily zoomed on Home.
    val artworkHeight = if (isLandscape) {
        (configuration.screenHeightDp * 0.58f).dp
    } else {
        (configuration.screenHeightDp * 0.42f).dp
    }
    val heroShape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
    val posterShape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val heroScale = if (pressed) 0.996f else 1f
    val pageBackground = Color(0xFF0E1015)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = heroScale; scaleY = heroScale }
            .clip(heroShape)
            .background(pageBackground)
            .semantics(mergeDescendants = true) {
                contentDescription = "$displayTitle. $actionLabel"
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(artworkHeight)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    if (displayTitle.isNotBlank()) onOpenDetails(displayTitle)
                },
            contentAlignment = Alignment.Center,
        ) {
            // Same physical image treatment as Details: same viewport height, same crop,
            // same bottom alpha dissolve. This keeps framing consistent for all titles.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        val alphaMask = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.White,
                                0.68f to Color.White,
                                0.76f to Color.White.copy(alpha = 0.96f),
                                0.84f to Color.White.copy(alpha = 0.82f),
                                0.91f to Color.White.copy(alpha = 0.58f),
                                0.96f to Color.White.copy(alpha = 0.30f),
                                0.99f to Color.White.copy(alpha = 0.08f),
                                1.00f to Color.Transparent,
                            ),
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(
                                brush = alphaMask,
                                blendMode = BlendMode.DstIn,
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    hasBackdrop -> {
                        MoviaArtwork(
                            url = backdropUrl,
                            modifier = Modifier.fillMaxSize(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            placeholderStyle = MediaArtworkPlaceholderStyle.HERO,
                        )
                    }
                    !posterUrl.isNullOrBlank() -> {
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
                    else -> MediaArtworkPlaceholder(
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
                                0.80f to Color.Transparent,
                                0.90f to pageBackground.copy(alpha = 0.08f),
                                0.96f to pageBackground.copy(alpha = 0.18f),
                                1.00f to pageBackground.copy(alpha = 0.38f),
                            ),
                        ),
                    ),
            )

            HomeHeader(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 24.dp, top = 18.dp, end = 24.dp),
            )
        }

        // Identity and playback now live below the artwork, following Details.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = displayTitle,
                color = Color(0xFFF8F4E6),
                fontSize = 32.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    if (displayTitle.isNotBlank()) onOpenDetails(displayTitle)
                },
            )

            if (ratingText != null || metadataTail.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (ratingText != null) {
                        Text(
                            text = "★ $ratingText",
                            color = MoviaBrandAmber,
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (metadataTail.isNotBlank()) {
                        Text(
                            text = if (ratingText != null) "•  $metadataTail" else metadataTail,
                            modifier = Modifier.weight(1f),
                            color = Color(0xFFF8F4E6).copy(alpha = 0.88f),
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (actionDetail == null) 56.dp else 68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MoviaBrandAmber)
                    .clickable {
                        if (playbackTitle.isNotBlank()) onContinue(playbackTitle)
                    },
                contentAlignment = Alignment.Center,
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
                        Icon(
                            imageVector = Icons.Outlined.PlayCircleOutline,
                            contentDescription = null,
                            tint = MoviaOnBrandAmber,
                            modifier = Modifier.size(30.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = actionLabel,
                            color = MoviaOnBrandAmber,
                            fontSize = 18.sp,
                            lineHeight = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    actionDetail?.let { detail ->
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
    }
}

@Composable
private fun HomeMediaSection(
    title: String,
    items: List<MediaContent>,
    onOpenDetails: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(title = title, onClick = onViewAll)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 48.dp),
        ) {
            items(items, key = { "$title-${it.id}" }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.width(134.dp),
                    onClick = { onOpenDetails(item.title) },
                )
            }
        }
    }
}
