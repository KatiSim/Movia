package app.movia.android.ui.home

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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
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
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.RecommendationEngine
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.ui.components.MediaArtworkPlaceholder
import app.movia.android.ui.components.MediaArtworkPlaceholderStyle
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.SectionHeader
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
    onOpenDetails: (String) -> Unit,
    onContinue: (String) -> Unit,
    onOpenCatalog: (CatalogLaunchPreset) -> Unit,
    onOpenProfile: () -> Unit,
    onOpenNotifications: () -> Unit,
) {
    val recommendation = RecommendationEngine.recommend(history, limit = 8)
    val allItems = DemoCatalogRepository.all()
    val newItems = allItems
        .filter { it.isNew }
        .sortedByDescending { it.popularity }
        .take(8)
    val popularItems = allItems
        .sortedByDescending { it.popularity }
        .take(8)
    val forYouItems = recommendation.items.take(8)

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
            HomeHeader(
                onOpenNotifications = onOpenNotifications,
                onOpenProfile = onOpenProfile,
            )
        }

        item(key = "continue") {
            ContinueWatchingCard(
                progress = progress,
                onContinue = onContinue,
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
                    title = "Популярно сейчас",
                    items = popularItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                )
            }
        }

        item(key = "for-you") {
            HomeMediaSection(
                title = "Для вас",
                items = forYouItems,
                onOpenDetails = onOpenDetails,
                onViewAll = { onOpenCatalog(CatalogLaunchPreset.RECOMMENDED) },
            )
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenNotifications: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val profileInteractionSource = remember { MutableInteractionSource() }
    val profilePressed = profileInteractionSource.collectIsPressedAsState().value
    val profileScale = animateFloatAsState(
        targetValue = if (profilePressed) 0.96f else 1f,
        label = "profile-press-scale",
    ).value

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

        IconButton(
            onClick = onOpenNotifications,
            modifier = Modifier.size(48.dp),
        ) {
            MoviaBellIcon(
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(Modifier.width(8.dp))

        Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (profilePressed) {
                Canvas(
                    modifier = Modifier
                        .requiredSize(52.dp)
                        .blur(12.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                ) {
                    drawCircle(MoviaGlowLuminescence)
                }
            }

            Surface(
                onClick = onOpenProfile,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer {
                        scaleX = profileScale
                        scaleY = profileScale
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(2.dp, MoviaBrandAmber),
                interactionSource = profileInteractionSource,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = "Профиль",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoviaBellIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val path = Path().apply {
            moveTo(5.5f * u, 17.5f * u)
            lineTo(7f * u, 15.5f * u)
            lineTo(7f * u, 10.2f * u)
            quadraticBezierTo(7f * u, 5.8f * u, 12f * u, 5.8f * u)
            quadraticBezierTo(17f * u, 5.8f * u, 17f * u, 10.2f * u)
            lineTo(17f * u, 15.5f * u)
            lineTo(18.5f * u, 17.5f * u)
            close()
        }
        drawPath(path, tint, style = Stroke(width = 1.8f * u, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(9.6f * u, 20f * u),
            end = androidx.compose.ui.geometry.Offset(14.4f * u, 20f * u),
            strokeWidth = 1.8f * u,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(
            color = tint,
            radius = 1.2f * u,
            center = androidx.compose.ui.geometry.Offset(12f * u, 4f * u),
        )
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: PlaybackProgress,
    onContinue: (String) -> Unit,
) {
    val hasRealProgress = progress.title.isNotBlank() && progress.positionMs > 0L
    val playbackTitle = if (hasRealProgress) progress.title else "Нулевая орбита · S01E04 · Эпизод 4"
    val displayTitle = playbackTitle.substringBefore(" · S").substringBefore(" · E")
    val heroContent = progress.contentId?.let(DemoCatalogRepository::findById)
        ?: DemoCatalogRepository.findByTitle(displayTitle)
    val heroArtworkUrl = heroContent?.posterUrl
    val episodeMatch = Regex(""" · S(\d{2})E(\d{2})""").find(playbackTitle)
    val season = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
    val episode = episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
    val hasRealTimeline = progress.title.isNotBlank() && progress.durationMs > 0L && progress.positionMs >= 0L
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
        6
    }
    val episodeLabel = if (season != null && episode != null) {
        "Сезон $season, Эпизод $episode"
    } else {
        "Продолжить просмотр"
    }
    val subtitle = "$episodeLabel • Осталось $remainingMinutes мин"
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onContinue(playbackTitle) }
            .semantics(mergeDescendants = true) {
                contentDescription = "Продолжить просмотр. $displayTitle. $subtitle"
            },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The visual banner contains no duplicated title/episode text. Because MediaContent
        // currently exposes no artwork URL, this is the truthful no-artwork placeholder.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .blur(18.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            ) {
                drawRoundRect(
                    color = MoviaHeroGlow,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                )
            }

            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MoviaBrandAmber),
            ) {
                MoviaArtwork(
                    url = heroArtworkUrl,
                    modifier = Modifier.fillMaxSize(),
                    placeholderStyle = MediaArtworkPlaceholderStyle.HERO,
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 24.dp)
                            .size(56.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(18.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                        ) {
                            drawCircle(MoviaGlowLuminescence.copy(alpha = 0.14f))
                        }
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(y = 4.dp)
                                .blur(10.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                        ) {
                            drawCircle(MoviaPlayShadow)
                        }
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val oneDp = 1.dp.toPx()
                            val centerPoint = Offset(size.width / 2f, size.height / 2f)
                            val radius = size.minDimension / 2f - oneDp
                            drawCircle(
                                color = MoviaPlayBackground,
                                radius = radius,
                                center = centerPoint,
                            )
                            drawCircle(
                                color = MoviaBrandAmber,
                                radius = radius,
                                center = centerPoint,
                                style = Stroke(width = oneDp),
                            )
                            drawArc(
                                color = MoviaPlayHighlight,
                                startAngle = 205f,
                                sweepAngle = 130f,
                                useCenter = false,
                                topLeft = Offset(oneDp * 2f, oneDp * 2f),
                                size = androidx.compose.ui.geometry.Size(size.width - oneDp * 4f, size.height - oneDp * 4f),
                                style = Stroke(width = oneDp),
                            )

                            val opticalOffset = 1.dp.toPx()
                            val cx = size.width / 2f + opticalOffset
                            val cy = size.height / 2f
                            val halfH = 8.5.dp.toPx()
                            val leftX = cx - 5.0.dp.toPx()
                            val rightX = cx + 8.0.dp.toPx()
                            val round = 2.5.dp.toPx()
                            val playPath = Path().apply {
                                moveTo(leftX, cy - halfH + round)
                                quadraticTo(leftX, cy - halfH, leftX + round, cy - halfH + round * 0.18f)
                                lineTo(rightX - round, cy - round * 0.55f)
                                quadraticTo(rightX, cy, rightX - round, cy + round * 0.55f)
                                lineTo(leftX + round, cy + halfH - round * 0.18f)
                                quadraticTo(leftX, cy + halfH, leftX, cy + halfH - round)
                                close()
                            }
                            drawPath(playPath, MoviaBrandAmber)
                        }
                    }

                    Canvas(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // 12dp outer padding + 4dp thumb radius = exact 16dp track inset.
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                            .height(8.dp),
                    ) {
                        val thumbRadius = 4.dp.toPx()
                        val trackHeight = 3.dp.toPx()
                        val trackStart = thumbRadius
                        val trackEnd = size.width - thumbRadius
                        val trackWidth = (trackEnd - trackStart).coerceAtLeast(0f)
                        val trackTop = (size.height - trackHeight) / 2f
                        val trackCorner = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f, trackHeight / 2f)
                        val clampedFraction = fraction.coerceIn(0f, 1f)
                        val progressWidth = trackWidth * clampedFraction

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

                        // Thumb is data-bound, never decorative: no valid duration means no thumb.
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

        // All text lives outside the artwork/placeholder area.
        Text(
            text = displayTitle,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 20.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HomeMediaSection(
    title: String,
    items: List<MediaContent>,
    onOpenDetails: (String) -> Unit,
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
                    onClick = { onOpenDetails(item.title) },
                )
            }
        }
    }
}
