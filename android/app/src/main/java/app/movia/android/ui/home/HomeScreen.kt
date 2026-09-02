package app.movia.android.ui.home

import java.util.Locale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
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
import app.movia.android.ui.theme.MoviaHeroGlow
import app.movia.android.ui.theme.MoviaPlayBackground
import app.movia.android.ui.theme.MoviaGlowLuminescence
import app.movia.android.ui.theme.MoviaPlayShadow
import app.movia.android.ui.theme.MoviaPlayHighlight
import app.movia.android.ui.theme.MoviaProgressTrack
import kotlin.math.ceil

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress = PlaybackProgress(),
    history: List<String> = emptyList(),
    favorites: Set<String> = emptySet(),
    onOpenDetails: (String, String?) -> Unit,
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

    val newItems = remember(newPool) { newPool.take(12) }
    val popularItems = remember(popularPool) { popularPool.take(12) }
    val forYouItems = remember(forYouPool, recommendation.items) {
        if (recommendation.items.isNotEmpty()) recommendation.items.take(12) else forYouPool.take(12)
    }
    val seriesItems = remember(seriesPool) { seriesPool.take(12) }
    val topNewHit = remember(newItems, newPool) { newItems.firstOrNull() ?: newPool.firstOrNull() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "home-header") {
            HomeHeader()
        }

        item(key = "continue") {
            ContinueWatchingCard(
                progress = progress,
                fallbackItem = topNewHit,
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
private fun HomeHeader() {
    val profileInteractionSource = remember { MutableInteractionSource() }
    val profilePressed = profileInteractionSource.collectIsPressedAsState().value
    val profileScale = if (profilePressed) 0.96f else 1f

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
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
}

@Composable
private fun ContinueWatchingCard(
    progress: PlaybackProgress,
    fallbackItem: MediaContent?,
    onContinue: (String) -> Unit,
    onOpenDetails: (String, String?) -> Unit,
) {
    val hasRealProgress = progress.title.isNotBlank() && progress.positionMs > 0L
    val heroContent = if (hasRealProgress) {
        val display = progress.title.substringBefore(" · S").substringBefore(" · E")
        progress.contentId?.let(DemoCatalogRepository::findById) ?: DemoCatalogRepository.findByTitle(display)
    } else {
        fallbackItem
    }
    val playbackTitle = if (hasRealProgress) progress.title else (fallbackItem?.title ?: "")
    val displayTitle = if (hasRealProgress) {
        playbackTitle.substringBefore(" · S").substringBefore(" · E")
    } else {
        fallbackItem?.title ?: "Новинка"
    }

    val heroBackdropUrl = heroContent?.backdropUrl?.takeIf { it.isNotBlank() } ?: heroContent?.posterUrl?.takeIf { it.isNotBlank() }
    val episodeMatch = Regex(""" · S(\d{2})E(\d{2})""").find(playbackTitle)
    val season = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
    val hasRealTimeline = hasRealProgress && progress.durationMs > 0L && progress.positionMs >= 0L
    val fraction = if (hasRealTimeline) {
        (progress.positionMs.toDouble() / progress.durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        0f
    }
    val remainingMinutes = if (hasRealProgress && progress.durationMs > 0L) {
        ceil((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000.0)
            .toInt()
            .coerceAtLeast(1)
    } else {
        0
    }
    val episodeLabel = when {
        season != null && episode != null -> "Сезон $season, Эпизод $episode"
        hasRealProgress -> "Продолжить просмотр"
        else -> "Главный хит"
    }
    val progressMeta = when {
        season != null && episode != null -> {
            if (remainingMinutes > 0) "Сезон $season • Серия $episode • Осталось $remainingMinutes мин"
            else "Сезон $season • Серия $episode"
        }
        hasRealProgress && remainingMinutes > 0 -> {
            "Осталось $remainingMinutes мин"
        }
        else -> {
            val genreOrType = heroContent?.let(::moviaPrimaryGenre) ?: heroContent?.let(::moviaContentTypeLabel)
            val country = heroContent?.country?.takeIf { it.isNotBlank() }
            listOfNotNull(
                heroContent?.rating?.takeIf { it > 0.0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) },
                heroContent?.year?.takeIf { it > 0 }?.toString(),
                country,
                genreOrType,
            ).joinToString(" • ").ifBlank { "Премьера" }
        }
    }
    val badgeLabel = if (hasRealProgress) "ПРОДОЛЖИТЬ" else "ГЛАВНЫЙ ХИТ"
    val shape = RoundedCornerShape(16.dp)

    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val cardScale = if (pressed) 0.97f else 1f
    val glowBlur = if (pressed) 10.dp else 18.dp
    val glowAlpha = if (pressed) 0.22f else 0.36f
    val borderAlpha = if (pressed) 0.72f else 0.92f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = cardScale
                scaleY = cardScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (displayTitle.isNotBlank()) {
                    onOpenDetails(displayTitle, heroContent?.id)
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = "$badgeLabel. $displayTitle. $episodeLabel. $progressMeta"
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            // Outer ambient glow remains outside the clipped artwork surface.
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(glowBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            ) {
                drawRoundRect(
                    color = MoviaHeroGlow.copy(alpha = glowAlpha),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.5.dp, MoviaBrandAmber.copy(alpha = borderAlpha)),
            ) {
                MoviaArtwork(
                    url = heroBackdropUrl,
                    modifier = Modifier.fillMaxSize(),
                    placeholderStyle = MediaArtworkPlaceholderStyle.HERO,
                ) {
                    // Designer-style text protection: atmospheric wash + long floor fade
                    // + a localized blurred haze behind the title and progress.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF0E1015).copy(alpha = 0.12f)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.22f to Color.Transparent,
                                        0.42f to Color(0xFF0E1015).copy(alpha = 0.12f),
                                        0.62f to Color(0xFF0E1015).copy(alpha = 0.44f),
                                        0.80f to Color(0xFF0E1015).copy(alpha = 0.78f),
                                        1.0f to Color(0xFF0E1015).copy(alpha = 0.98f),
                                    ),
                                ),
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(116.dp)
                            .blur(18.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            .background(Color(0xFF0E1015).copy(alpha = 0.38f)),
                    )

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        shape = RoundedCornerShape(9.dp),
                        color = MoviaPlayBackground.copy(alpha = 0.82f),
                        border = BorderStroke(1.dp, MoviaBrandAmber.copy(alpha = 0.84f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MoviaBrandAmber,
                                modifier = Modifier.size(15.dp),
                            )
                            Text(
                                text = badgeLabel,
                                color = Color.White,
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.45.sp,
                            )
                        }
                    }

                    // Centered play badge with a restrained local gold glow.
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(16.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                        ) {
                            drawCircle(MoviaGlowLuminescence.copy(alpha = 0.24f))
                        }
                        Surface(
                            onClick = {
                                if (playbackTitle.isNotBlank()) {
                                    onContinue(playbackTitle)
                                }
                            },
                            modifier = Modifier.size(58.dp),
                            shape = CircleShape,
                            color = MoviaPlayBackground.copy(alpha = 0.84f),
                            border = BorderStroke(1.dp, MoviaBrandAmber.copy(alpha = 0.92f)),
                            shadowElevation = 4.dp,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.PlayArrow,
                                    contentDescription = "Смотреть $displayTitle",
                                    tint = MoviaBrandAmber,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = displayTitle,
                            color = Color.White,
                            fontSize = 17.sp,
                            lineHeight = 21.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = progressMeta,
                            color = Color.White.copy(alpha = 0.86f),
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Canvas(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // Track inset is 16dp including the 4dp thumb radius.
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            .height(8.dp),
                    ) {
                        val thumbRadius = 4.dp.toPx()
                        val trackHeight = 3.dp.toPx()
                        val trackStart = thumbRadius
                        val trackEnd = size.width - thumbRadius
                        val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
                        val trackTop = (size.height - trackHeight) / 2f
                        val trackCorner = androidx.compose.ui.geometry.CornerRadius(
                            trackHeight / 2f,
                            trackHeight / 2f,
                        )
                        val progressWidth = trackWidth * fraction

                        drawRoundRect(
                            color = MoviaProgressTrack,
                            topLeft = Offset(trackStart, trackTop),
                            size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                            cornerRadius = trackCorner,
                        )
                        if (progressWidth > 0f) {
                            drawRoundRect(
                                color = MoviaBrandAmber,
                                topLeft = Offset(trackStart, trackTop),
                                size = androidx.compose.ui.geometry.Size(progressWidth, trackHeight),
                                cornerRadius = trackCorner,
                            )
                        }
                        if (hasRealTimeline) {
                            drawCircle(
                                color = MoviaBrandAmber,
                                radius = thumbRadius,
                                center = Offset(trackStart + progressWidth, size.height / 2f),
                            )
                        }
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
    onOpenDetails: (String, String?) -> Unit,
    onViewAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = title, onClick = onViewAll)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 48.dp),
        ) {
            items(items, key = { "$title-${it.id}" }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.width(134.dp),
                    onClick = { onOpenDetails(item.title, item.id) },
                )
            }
        }
    }
}
