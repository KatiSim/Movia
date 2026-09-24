package app.movia.android.ui.home

import android.content.res.Configuration
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.ReportDrawnWhen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.ui.catalog.CatalogLaunchPreset
import app.movia.android.ui.components.MediaMetadataRow
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MoviaAmbientStrength
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.MoviaSpotlightActionButton
import app.movia.android.ui.components.SectionHeader
import app.movia.android.ui.components.moviaAmbient
import app.movia.android.ui.components.moviaDisplayTitle
import app.movia.android.ui.components.moviaPrimaryGenre
import app.movia.android.ui.components.moviaRatingLabel
import app.movia.android.ui.components.moviaYearLabel
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import app.movia.android.ui.theme.MoviaBorderMedium
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaAccent
import app.movia.android.ui.theme.MoviaAccentPressed
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaRadius
import app.movia.android.ui.theme.MoviaFontFamily
import app.movia.android.ui.theme.MoviaLogoGradientCream
import app.movia.android.ui.theme.MoviaLogoGradientEnd
import app.movia.android.ui.theme.MoviaLogoGradientIvory
import app.movia.android.ui.theme.MoviaLogoGradientLightBronze
import app.movia.android.ui.theme.MoviaLogoGradientMilk
import app.movia.android.ui.theme.MoviaLogoGradientPastelGold
import app.movia.android.ui.theme.MoviaLogoGradientSoftGold
import app.movia.android.ui.theme.MoviaLogoGradientStart
import app.movia.android.ui.theme.MoviaSurfaceElevated
import app.movia.android.ui.theme.MoviaTextPrimary
import app.movia.android.ui.theme.MoviaTextSecondary
import app.movia.android.ui.theme.MoviaTextTertiary
import kotlin.math.abs
import kotlinx.coroutines.launch

private val SpotlightPosterWidth = 171.82.dp
private val SpotlightPosterHeight = 257.73.dp

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress = PlaybackProgress(),
    history: List<String> = emptyList(),
    favorites: Set<String> = emptySet(),
    favoriteContentIds: Set<String> = emptySet(),
    onOpenDetails: (String, String) -> Unit,
    onContinue: (String, String) -> Unit,
    onToggleFavorite: (String, String, Boolean) -> Unit,
    onOpenCatalog: (CatalogLaunchPreset) -> Unit,
) {
    val homeViewModel: HomeViewModel = viewModel()
    val homeUiState by homeViewModel.uiState.collectAsStateWithLifecycle()
    val homeFeed = homeUiState.feed
    val recommendation = homeUiState.recommendation
    LaunchedEffect(history, favorites) {
        homeViewModel.updateRecommendations(history, favorites)
    }
    LaunchedEffect(homeFeed.animations, homeFeed.catalog) {
        homeViewModel.updateAnimationSeries(homeFeed)
    }
    ReportDrawnWhen {
        homeFeed.popular.isNotEmpty() ||
            homeFeed.newReleases.isNotEmpty() ||
            homeFeed.series.isNotEmpty() ||
            homeFeed.catalog.isNotEmpty() ||
            homeFeed.error != null ||
            homeFeed.lastUpdatedMs > 0L
    }
    val cachedCatalog = homeFeed.catalog
    val popularPool = (homeFeed.popular.ifEmpty {
        cachedCatalog.sortedWith(compareByDescending<MediaContent> { it.popularity }.thenByDescending { it.rating })
    }).take(12)
    val newPool = (homeFeed.newReleases.ifEmpty {
        cachedCatalog.sortedWith(compareByDescending<MediaContent> { it.year }.thenByDescending { it.rating })
    }).take(12)
    val forYouPool = homeFeed.forYou.take(12)
    val seriesPool = (homeFeed.series.ifEmpty {
        cachedCatalog.filter { it.type == ContentType.SERIES || it.type == ContentType.TV }
    }).take(12)
    val animationSeriesPool = homeUiState.animationSeries

    // Curate up to 7 items for Spotlight from recommendation pool with diversity and deduplication
    val spotlightItems = remember(recommendation.items, forYouPool, newPool, popularPool, history) {
        buildSpotlightItems(
            recommendations = recommendation.items,
            forYou = forYouPool,
            newItems = newPool,
            popular = popularPool,
            history = history,
            maxItems = 7,
        )
    }
    val newItems = remember(newPool) { newPool.take(12) }
    val popularItems = remember(popularPool) { popularPool.take(12) }
    val animationItems = remember(animationSeriesPool) { animationSeriesPool.distinctBy { it.id }.take(12) }
    val animationIds = remember(animationItems) { animationItems.map { it.id }.toSet() }
    val seriesItems = remember(seriesPool, animationIds) {
        seriesPool
            .filterNot { item ->
                item.id in animationIds || item.genres.any { it.equals("мультфильм", ignoreCase = true) }
            }
            .distinctBy { it.id }
            .take(12)
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val statusBarHeight = with(density) { WindowInsets.statusBars.getTop(this).toDp() }
    val activePosterWidth = SpotlightPosterWidth
    val activePosterHeight = SpotlightPosterHeight
    val logoLineHeight = 30.dp
    val logoTopY = statusBarHeight + 10.dp
    val logoBottomY = logoTopY + logoLineHeight
    val posterTop = logoBottomY + 14.dp + 16.dp
    val posterLeft = (screenWidth - activePosterWidth) / 2

    // Active spotlight artwork for ambient glow
    val initialArtworkUrl = spotlightItems.firstOrNull()?.let {
        it.posterUrl?.takeIf { u -> u.isNotBlank() } ?: it.backdropUrl
    }
    var activeAmbientUrl by remember(initialArtworkUrl) { mutableStateOf(initialArtworkUrl) }
    var activeAmbientId by remember(spotlightItems) { mutableStateOf(spotlightItems.firstOrNull()?.id) }

    var rootTopInWindowPx by remember { mutableFloatStateOf(0f) }
    var firstSectionTopInWindowPx by remember { mutableFloatStateOf(0f) }
    val lazyListState = rememberLazyListState()

    val scrollOffsetDp = with(density) {
        if (lazyListState.firstVisibleItemIndex == 0) {
            lazyListState.firstVisibleItemScrollOffset.toDp()
        } else {
            10000.dp
        }
    }
    val effectivePosterTopDp = posterTop - scrollOffsetDp

    // Dynamic measurement of the first lower shelf ("Новинки") top position.
    val estimatedSpotlightInfoHeight = 26.62.dp * 2 + 4.84.dp + 19.36.dp
    val estimatedFirstSectionTopDp = statusBarHeight + 10.dp + logoLineHeight + 14.dp + 16.456.dp +
        activePosterHeight + 9.68.dp + estimatedSpotlightInfoHeight + 9.68.dp + 67.76.dp + 20.4.dp
    val firstSectionRelativeTopDp = if (firstSectionTopInWindowPx > 0f) {
        with(density) { (firstSectionTopInWindowPx - rootTopInWindowPx).toDp() }
    } else {
        estimatedFirstSectionTopDp
    }
    val ambientBottomDp = (firstSectionRelativeTopDp - 5.dp).coerceAtLeast(0.dp)

    LaunchedEffect(firstSectionTopInWindowPx, rootTopInWindowPx) {
        if (firstSectionTopInWindowPx > 0f) {
            android.util.Log.i(
                "AmbientGlowV4",
                "DYNAMIC_BOUNDS: rootTop=$rootTopInWindowPx, firstSectionTopInWindow=$firstSectionTopInWindowPx, firstSectionTopDp=$firstSectionRelativeTopDp, ambientBottomDp=$ambientBottomDp, delta=5.dp"
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                rootTopInWindowPx = coordinates.positionInWindow().y
            },
    ) {
        // Dedicated Ambient Glow block: starts at physical y=0, ends 5dp before the first lower shelf
        if (ambientBottomDp > 0.dp) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ambientBottomDp)
                    .clipToBounds()
                    .moviaAmbient(
                        artworkUrl = activeAmbientUrl,
                        strength = MoviaAmbientStrength.HOME,
                        cacheKey = activeAmbientId,
                        posterLeftDp = posterLeft.value,
                        posterTopDp = effectivePosterTopDp.value,
                        posterWidthDp = activePosterWidth.value,
                        posterHeightDp = activePosterHeight.value,
                    ),
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 0.dp,
                top = 0.dp,
                end = 0.dp,
                bottom = contentPadding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(20.4.dp),
        ) {
            val coreHomeReady = spotlightItems.isNotEmpty() || newItems.isNotEmpty() ||
                popularItems.isNotEmpty() || seriesItems.isNotEmpty() || homeFeed.lastUpdatedMs > 0L
            val hasUsableCoreCache = spotlightItems.isNotEmpty() || newItems.isNotEmpty() ||
                popularItems.isNotEmpty() || seriesItems.isNotEmpty() || homeFeed.catalog.isNotEmpty()
            val waitingForCoreHome = !coreHomeReady && homeFeed.error == null
            val showHomeStatus = waitingForCoreHome || (homeFeed.error != null && !hasUsableCoreCache)

            item(key = "home-top-shell") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HomeHeaderContainer(statusBarTopInset = statusBarHeight)
                    if (showHomeStatus) {
                        Spacer(modifier = Modifier.height(20.4.dp))
                        HomeFeedStatus(
                            loading = waitingForCoreHome || homeFeed.isRefreshing,
                            hasCachedContent = hasUsableCoreCache,
                            onRetry = homeViewModel::refreshHome,
                        )
                    }
                    if (spotlightItems.isNotEmpty()) {
                        if (showHomeStatus) Spacer(modifier = Modifier.height(20.4.dp))
                        HomeSpotlightSection(
                            items = spotlightItems,
                            favorites = favorites,
                            favoriteContentIds = favoriteContentIds,
                            onOpenDetails = onOpenDetails,
                            onContinue = onContinue,
                            onToggleFavorite = onToggleFavorite,
                            onActiveItemChanged = { item ->
                                val url = item.posterUrl?.takeIf { u -> u.isNotBlank() } ?: item.backdropUrl
                                activeAmbientUrl = url
                                activeAmbientId = item.id
                            },
                        )
                    }
                }
            }

            if (newItems.isNotEmpty()) {
                item(key = "new") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coordinates ->
                                firstSectionTopInWindowPx = coordinates.positionInWindow().y
                            },
                    ) {
                        HomeMediaSection(
                            title = "Новинки",
                            items = newItems,
                            onOpenDetails = onOpenDetails,
                            viewAllTestTag = "home.section.viewAll.new",
                            onViewAll = { onOpenCatalog(CatalogLaunchPreset.NEW) },
                        )
                    }
                }
            }

            if (popularItems.isNotEmpty()) {
                item(key = "popular-now") {
                    HomeMediaSection(
                        title = "Популярное",
                        items = popularItems.take(10),
                        onOpenDetails = onOpenDetails,
                        viewAllTestTag = "home.section.viewAll.popular",
                        onViewAll = { onOpenCatalog(CatalogLaunchPreset.POPULAR) },
                    )
                }
            }

            if (seriesItems.isNotEmpty()) {
                item(key = "series-section") {
                    HomeMediaSection(
                        title = "Сериалы",
                        items = seriesItems,
                        onOpenDetails = onOpenDetails,
                        viewAllTestTag = "home.section.viewAll.all",
                        onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                    )
                }
            }

            if (coreHomeReady && animationItems.isNotEmpty()) {
                item(key = "animation-series-section") {
                    HomeMediaSection(
                        title = "Мультсериалы",
                        items = animationItems,
                        onOpenDetails = onOpenDetails,
                        viewAllTestTag = "home.section.viewAll.all",
                        onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeFeedStatus(
    loading: Boolean,
    hasCachedContent: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        shape = RoundedCornerShape(16.dp),
        color = MoviaSurfaceElevated,
        border = BorderStroke(1.dp, MoviaBorderMedium),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = when {
                        loading && hasCachedContent -> "Обновляем подборки"
                        loading -> "Загружаем главную"
                        hasCachedContent -> "Показан сохранённый каталог"
                        else -> "Не удалось загрузить главную"
                    },
                    color = MoviaTextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!loading) {
                    Text(
                        text = if (hasCachedContent) "Проверьте подключение и попробуйте обновить." else "Проверьте подключение и повторите загрузку.",
                        color = MoviaTextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!loading) {
                Button(onClick = onRetry) {
                    Text("Повторить")
                }
            }
        }
    }
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val durationScale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1.0f,
            )
        }.getOrDefault(1.0f)
        durationScale == 0f
    }
}

private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private fun buildSpotlightItems(
    recommendations: List<MediaContent>,
    forYou: List<MediaContent>,
    newItems: List<MediaContent>,
    popular: List<MediaContent>,
    history: List<String>,
    maxItems: Int = 7,
): List<MediaContent> {
    val watchedTitles = history.map { moviaDisplayTitle(it).trim().lowercase() }.toSet()
    val result = mutableListOf<MediaContent>()
    val seenIds = mutableSetOf<String>()

    fun canAdd(candidate: MediaContent): Boolean {
        if (candidate.id in seenIds) return false
        val displayTitle = moviaDisplayTitle(candidate.title).trim().lowercase()
        if (displayTitle in watchedTitles) return false
        // Diversity: do not allow more than 2 of the exact same primary genre in a row
        val candidateGenre = moviaPrimaryGenre(candidate)?.trim().orEmpty()
        if (candidateGenre.isNotBlank() && result.size >= 2) {
            val prev1 = moviaPrimaryGenre(result[result.size - 1])?.trim().orEmpty()
            val prev2 = moviaPrimaryGenre(result[result.size - 2])?.trim().orEmpty()
            if (candidateGenre.equals(prev1, ignoreCase = true) && candidateGenre.equals(prev2, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    for (item in recommendations) {
        if (result.size >= maxItems) break
        if (canAdd(item)) {
            result.add(item)
            seenIds.add(item.id)
        }
    }

    for (item in forYou) {
        if (result.size >= maxItems) break
        if (canAdd(item)) {
            result.add(item)
            seenIds.add(item.id)
        }
    }

    for (item in (newItems + popular)) {
        if (result.size >= maxItems) break
        if (canAdd(item)) {
            result.add(item)
            seenIds.add(item.id)
        }
    }

    return result
}

@Composable
private fun HomeHeaderContainer(statusBarTopInset: Dp) {
    val logoLineHeight = 30.dp
    val logoTopY = statusBarTopInset + 10.dp
    val logoBottomY = logoTopY + logoLineHeight
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(logoBottomY + 14.dp),
    ) {
        HomeHeader(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = logoTopY, end = 24.dp),
        )
    }
}

@Composable
private fun HomeSpotlightSection(
    items: List<MediaContent>,
    favorites: Set<String>,
    favoriteContentIds: Set<String>,
    onOpenDetails: (String, String) -> Unit,
    onContinue: (String, String) -> Unit,
    onToggleFavorite: (String, String, Boolean) -> Unit,
    onActiveItemChanged: (MediaContent) -> Unit,
) {
    if (items.isEmpty()) return

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = configuration.screenWidthDp.dp
    val reduceMotion = rememberReduceMotion()

    // Locked Spotlight geometry, shared with ambient positioning.
    val activePosterWidth = SpotlightPosterWidth
    val activePosterHeight = SpotlightPosterHeight
    val posterShape = RoundedCornerShape(21.78.dp)

    val pagerState = rememberPagerState(initialPage = 0, pageCount = { items.size })
    val coroutineScope = rememberCoroutineScope()

    // Settled active item (stable during swipe, snaps smoothly)
    val settledIndex = pagerState.settledPage.coerceIn(0, items.size - 1)
    val activeItem = items[settledIndex]

    android.util.Log.i("SpotlightDebug", "settledIndex=$settledIndex, activeTitle=${activeItem.title}, items=${items.mapIndexed { i, it -> "$i: id=${it.id}, title=${it.title}, poster=${it.posterUrl}" }}")

    // Crossfade ambient on settled change
    LaunchedEffect(settledIndex, activeItem.id) {
        onActiveItemChanged(activeItem)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "${moviaDisplayTitle(activeItem.title)}. Рекомендация Spotlight"
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(modifier = Modifier.height(16.456.dp))

        // Spotlight 3D Carousel (or single centered card if size == 1)
        if (items.size == 1) {
            Box(
                modifier = Modifier
                    .width(activePosterWidth)
                    .height(activePosterHeight)
                    .clip(posterShape)
                    .border(1.dp, MoviaBorderSubtle, posterShape)
                    .clickable { onOpenDetails(items[0].id, items[0].title) }
                    .testTag("home.hero.open.${items[0].id}"),
            ) {
                MoviaArtwork(
                    url = items[0].posterUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
                )
            }
        } else {
            val pageSlotWidth = (activePosterWidth * 0.875f - 9.68.dp).coerceAtLeast(0.dp)
            val horizontalPadding = ((screenWidth - pageSlotWidth) / 2).coerceAtLeast(0.dp)
            val pagerHeight = activePosterHeight

            HorizontalPager(
                state = pagerState,
                key = { index -> items[index].id },
                pageSize = PageSize.Fixed(pageSlotWidth),
                contentPadding = PaddingValues(horizontal = horizontalPadding),
                pageSpacing = 19.36.dp,
                beyondViewportPageCount = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(pagerHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) { pageIndex ->
                val item = items[pageIndex]
                val pageOffset = (pageIndex - pagerState.currentPage) - pagerState.currentPageOffsetFraction
                val p = pageOffset
                val d = kotlin.math.abs(p)
                val t = d.coerceIn(0f, 1f)
                val t2 = (d - 1f).coerceIn(0f, 1f)
                val sign = if (p > 0.0001f) 1f else if (p < -0.0001f) -1f else 0f

                // Continuous scale: direct draw transform from the live pager offset.
                // Center = 1.00; adjacent pages = 0.70. No selected-state animation.
                val scaleDistance = kotlin.math.abs(pageOffset).coerceIn(0f, 1f)
                val scale = 1.00f - 0.30f * scaleDistance

                // Continuous rotationY: inward cylindrical arc (Section 5 & 31 & 33)
                // Left card (p < 0): +30° turning right inward towards center
                // Right card (p > 0): -30° turning left inward towards center
                val rotationY = if (reduceMotion) 0f else {
                    val baseAngle = if (d <= 1f) 30f * t else 30f + 6f * t2
                    -sign * baseAngle.coerceAtMost(36f)
                }

                // Horizontal arc compression (Section 8 & 33):
                // -sign(p) * 24dp * sin(t * PI / 2)
                val arcCorrectionPx = if (reduceMotion) 0f else {
                    val curve = kotlin.math.sin(t * (Math.PI / 2.0)).toFloat()
                    -sign * with(density) { 29.04.dp.toPx() } * curve
                }

                // Dark overlay alpha (Section 13 & 30 & 31 & 33):
                // Settled center = 0.00 (0%), settled adjacent = 0.41 (41%), continuous linear interpolation
                val overlayAlpha = (0.41f * t).coerceIn(0.00f, 0.41f)

                // Continuous zIndex: active is highest, adjacent lower, distant lowest (Section 11)
                val zIndex = (2f - d).coerceAtLeast(0f)

                Box(
                    modifier = Modifier
                        .zIndex(zIndex)
                        .graphicsLayer {
                            this.cameraDistance = 12f * density.density
                            this.transformOrigin = TransformOrigin.Center
                            this.rotationY = rotationY
                            this.scaleX = scale
                            this.scaleY = scale
                            this.translationX = arcCorrectionPx
                            this.alpha = 1.0f // Section 12: card image alpha remains 1.00
                        }
                        .wrapContentWidth(Alignment.CenterHorizontally, unbounded = true)
                        .requiredWidth(activePosterWidth)
                        .requiredHeight(activePosterHeight)
                        .onGloballyPositioned { coordinates ->
                            if (kotlin.math.abs(pageOffset) <= 1.01f && kotlin.math.abs(pagerState.currentPageOffsetFraction) < 0.001f) {
                                val b = coordinates.boundsInWindow()
                                android.util.Log.i("SpotlightBounds", "page=$pageIndex settled=${pagerState.settledPage} offset=$pageOffset left=${b.left} right=${b.right} width=${b.width}")
                                if (pageIndex == pagerState.settledPage) {
                                    android.util.Log.i("SpotlightGap", "ACTIVE: left=${b.left}, right=${b.right}, width=${b.width}")
                                } else if (pageIndex == pagerState.settledPage + 1) {
                                    android.util.Log.i("SpotlightGap", "RIGHT_ADJACENT: left=${b.left}, right=${b.right}, width=${b.width}")
                                } else if (pageIndex == pagerState.settledPage - 1) {
                                    android.util.Log.i("SpotlightGap", "LEFT_ADJACENT: left=${b.left}, right=${b.right}, width=${b.width}")
                                }
                            }
                        }
                        .clip(posterShape)
                        .border(1.dp, MoviaBorderSubtle, posterShape)
                        .clickable {
                            if (pageIndex == pagerState.currentPage) {
                                onOpenDetails(item.id, item.title)
                            } else {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(pageIndex)
                                }
                            }
                        }
                        .testTag("home.hero.open.${item.id}"),
                    contentAlignment = Alignment.Center,
                ) {
                    MoviaArtwork(
                        url = item.posterUrl,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
                    )

                    // Black overlay for realistic shadow depth without poster transparency (Section 13 & 29)
                    if (overlayAlpha > 0.001f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = overlayAlpha)),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(9.68.dp))

        // Active Title and Metadata with 200ms Crossfade (Section 26 & 27)
        AnimatedContent(
            targetState = activeItem,
            transitionSpec = {
                fadeIn(animationSpec = tween(durationMillis = 200)) togetherWith
                    fadeOut(animationSpec = tween(durationMillis = 200))
            },
            label = "SpotlightActiveMetadataCrossfade",
            modifier = Modifier.fillMaxWidth(),
        ) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 29.04.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.84.dp),
            ) {
                val displayTitle = moviaDisplayTitle(item.title)
                Text(
                    text = displayTitle,
                    color = MoviaTextPrimary,
                    fontSize = 19.36.sp,
                    lineHeight = 26.62.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenDetails(item.id, item.title) },
                )

                MediaMetadataRow(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = 14.52.sp,
                    lineHeight = 19.36.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(modifier = Modifier.height(9.68.dp))

        // Primary CTA centered independently from the secondary favorite action.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 29.04.dp)
                .height(67.76.dp),
        ) {
            val title = activeItem.title
            val isFavorite = activeItem.id in favoriteContentIds ||
                (title.isNotBlank() && (title in favorites || moviaDisplayTitle(title) in favorites))

            SpotlightPrimaryActionButton(
                onClick = {
                    if (title.isNotBlank()) onContinue(activeItem.id, title)
                },
                modifier = Modifier.align(Alignment.Center).testTag("home.hero.play.${activeItem.id}"),
            )

            Surface(
                onClick = {
                    if (title.isNotBlank()) onToggleFavorite(activeItem.id, title, !isFavorite)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(46.dp)
                    .clip(CircleShape)
                    .testTag("home.hero.favorite.${activeItem.id}"),
                shape = CircleShape,
                color = MoviaSurfaceElevated,
                border = BorderStroke(1.dp, MoviaBorderMedium),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isFavorite) "Удалить из избранного" else "Добавить в избранное",
                        tint = if (isFavorite) MoviaBrandAmber else MoviaTextPrimary,
                        modifier = Modifier.size(26.62.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SpotlightPrimaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MoviaSpotlightActionButton(
        label = "Смотреть",
        contentDescription = "Смотреть",
        onClick = onClick,
        modifier = modifier,
    )
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
                fontFamily = MoviaFontFamily,
                fontSize = 27.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.15.sp,
            ),
        )
    }
}

@Composable
private fun HomeMediaSection(
    title: String,
    items: List<MediaContent>,
    onOpenDetails: (String, String) -> Unit,
    viewAllTestTag: String,
    onViewAll: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionHeader(title = title, actionTestTag = viewAllTestTag, onClick = onViewAll)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 48.dp),
        ) {
            items(items, key = { "$title-${it.id}" }) { item ->
                HomeDiscoveryCard(
                    item = item,
                    modifier = Modifier.width(142.dp).testTag("home.section.item.open.${item.id}"),
                    onClick = { onOpenDetails(item.id, item.title) },
                )
            }
        }
    }
}

@Composable
private fun HomeDiscoveryCard(
    item: MediaContent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val title = moviaDisplayTitle(item.title)
    Column(
        modifier = modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MoviaArtwork(
            url = item.posterUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, MoviaBorderSubtle, RoundedCornerShape(14.dp)),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            placeholderStyle = MediaArtworkPlaceholderStyle.POSTER,
        )
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            color = MoviaTextPrimary,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MediaMetadataRow(
            item = item,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}
