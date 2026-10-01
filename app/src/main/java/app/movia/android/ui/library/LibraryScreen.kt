package app.movia.android.ui.library

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.R
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.domain.model.LibraryMediaRecord
import app.movia.android.ui.components.rememberMoviaMediaCoverFeedback
import app.movia.android.ui.components.MoviaMediaCoverFrame
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MediaMetadataRow
import app.movia.android.ui.components.MoviaAmbientStrength
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.MoviaFadeOverflowText
import app.movia.android.ui.components.MoviaGoldMedallionKind
import app.movia.android.ui.components.MoviaHeaderCircleControl
import app.movia.android.ui.components.MoviaHeaderGlyph
import app.movia.android.ui.components.MoviaHeaderGlyphIcon
import app.movia.android.ui.components.MoviaIconMotionKind
import app.movia.android.ui.components.rememberMoviaIconMotion
import app.movia.android.ui.components.rememberMoviaIconTapAlpha
import app.movia.android.ui.components.rememberMoviaGearMotion
import app.movia.android.ui.components.rememberMoviaAnimatedAction
import app.movia.android.ui.components.rememberMoviaActionTriggerState
import app.movia.android.ui.components.rememberMoviaPlaybackActionMotion
import app.movia.android.ui.components.MoviaSectionHeaderContentGap
import app.movia.android.ui.components.MoviaSectionAction
import app.movia.android.ui.components.MediaReleaseState
import app.movia.android.ui.components.moviaMediaReleaseState
import app.movia.android.ui.components.MoviaGoldMedallion
import app.movia.android.ui.components.MoviaChildTopBar
import app.movia.android.ui.components.moviaAmbient
import app.movia.android.ui.components.moviaPrimaryGenre
import app.movia.android.ui.components.moviaRatingLabel
import app.movia.android.ui.components.moviaYearLabel
import app.movia.android.ui.theme.MoviaTextPrimary
import app.movia.android.ui.theme.MoviaBackgroundPrimary
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaIconPrimary
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaSurfaceSecondary
import kotlin.math.ceil

private enum class LibrarySection { BOOKMARKS, FAVORITES, LATER, DOWNLOADS, HISTORY }
private enum class MyListTab { FAVORITES, WAITING_RELEASE }

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    favoriteRecords: List<LibraryMediaRecord> = emptyList(),
    waitingRelease: Set<String> = emptySet(),
    waitingReleaseRecords: List<LibraryMediaRecord> = emptyList(),
    history: List<String> = emptyList(),
    historyRecords: List<LibraryMediaRecord> = emptyList(),
    downloads: Set<String> = emptySet(),
    downloadRecords: List<LibraryMediaRecord> = emptyList(),
    catalog: List<MediaContent> = emptyList(),
    progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    progressByMediaRef: Map<String, PlaybackProgress> = emptyMap(),
    resumeHeroState: ResumeHeroState = ResumeHeroState.None,
    onContinue: (MediaRef?, String) -> Unit,
    onToggleFavorite: (String, String, Boolean) -> Unit,
    onToggleWaitingRelease: (String, String, Boolean) -> Unit,
    onOpenDetails: (String, String) -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClearHistory: (List<String>) -> Unit,
) {
    val availableCatalog = remember(catalog) { catalog.distinctBy { it.id } }
    var routeName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedListTab by rememberSaveable { mutableStateOf(MyListTab.FAVORITES) }
    val route = routeName?.let(LibrarySection::valueOf)
    BackHandler(enabled = route != null) { routeName = null }

    if (route != null) {
        val sourceTitles = when (route) {
            LibrarySection.FAVORITES -> favorites.sorted()
            LibrarySection.BOOKMARKS, LibrarySection.LATER -> favorites.sorted()
            LibrarySection.DOWNLOADS -> downloads.sorted()
            LibrarySection.HISTORY -> history
        }
        val sourceRecords = when (route) {
            LibrarySection.FAVORITES -> favoriteRecords
            LibrarySection.DOWNLOADS -> downloadRecords
            LibrarySection.HISTORY -> historyRecords
            LibrarySection.BOOKMARKS, LibrarySection.LATER -> emptyList()
        }
        LibraryCollectionScreen(
            section = route,
            titles = sourceTitles,
            records = sourceRecords,
            catalog = availableCatalog,
            contentPadding = contentPadding,
            modifier = modifier,
            onBack = { routeName = null },
            onOpenDetails = onOpenDetails,
            onOpenCatalog = onOpenCatalog,
            onClearHistory = if (route == LibrarySection.HISTORY && history.isNotEmpty()) {
                { onClearHistory(history) }
            } else null,
        )
        return
    }

    val catalogByTitle = remember(availableCatalog) {
        availableCatalog.associateBy { it.title.lowercase().trim() }
    }
    val catalogById = remember(availableCatalog) { availableCatalog.associateBy { it.id } }
    fun resolve(record: LibraryMediaRecord): MediaContent? {
        val base = record.title.substringBefore(" · S").substringBefore(" · E").trim()
        return record.contentId?.let(catalogById::get)
            ?: catalogByTitle[base.lowercase()]
    }
    fun resolve(storedTitle: String): MediaContent? {
        val base = storedTitle.substringBefore(" · S").substringBefore(" · E").trim()
        return catalogByTitle[base.lowercase()]
    }

    val favoriteRows = favoriteRecords.ifEmpty {
        favorites.map { title -> LibraryMediaRecord("legacy:${title.length}:$title", null, title, 0L) }
    }
    val waitingReleaseRows = waitingReleaseRecords.ifEmpty {
        waitingRelease.map { title -> LibraryMediaRecord("legacy:${title.length}:$title", null, title, 0L) }
    }
    val historyRows = historyRecords.ifEmpty {
        history.map { title -> LibraryMediaRecord("legacy:${title.length}:$title", null, title, 0L) }
    }
    val favoriteItems = favoriteRows.mapNotNull(::resolve).distinctBy { it.id }
    val waitingReleaseItems = waitingReleaseRows.mapNotNull(::resolve).distinctBy { it.id }
    val legacyUpcomingFavorites = favoriteItems.filter { moviaMediaReleaseState(it) == MediaReleaseState.UPCOMING }
    val displayFavoriteItems = favoriteItems.filterNot { item -> legacyUpcomingFavorites.any { it.id == item.id } }
    val displayWaitingReleaseItems = (waitingReleaseItems + legacyUpcomingFavorites).distinctBy { it.id }
    val recentItems = historyRows.mapNotNull(::resolve).distinctBy { it.id }
    val resumeProgress = when (resumeHeroState) {
        ResumeHeroState.None -> null
        is ResumeHeroState.Fallback -> resumeHeroState.progress
        is ResumeHeroState.Ready -> resumeHeroState.progress
    }
    val resumeItem = (resumeHeroState as? ResumeHeroState.Ready)?.item
    val hasHero = resumeProgress != null
    val resumeBaseTitle = resumeProgress?.title
        ?.substringBefore(" · S")
        ?.substringBefore(" · E")
        ?.trim()
        ?.lowercase()
    val resumeContentId = resumeProgress?.contentId?.takeIf { it.isNotBlank() } ?: resumeItem?.id
    val displayRecentItems = if (hasHero) {
        recentItems.filterNot { item ->
            (resumeContentId != null && item.id == resumeContentId) ||
                (resumeBaseTitle != null && item.title.trim().lowercase() == resumeBaseTitle)
        }
    } else {
        recentItems
    }

    val heroReleaseState = resumeItem?.let(::moviaMediaReleaseState) ?: MediaReleaseState.UNKNOWN
    val heroIsFavorite = resumeItem?.let { item ->
        favoriteItems.any { it.id == item.id } || favorites.any { it.equals(item.title, ignoreCase = true) }
    } ?: false
    val heroStoredWaitingRelease = resumeItem?.let { item ->
        waitingReleaseItems.any { it.id == item.id } ||
            waitingRelease.any { it.equals(item.title, ignoreCase = true) }
    } ?: false
    val heroIsWaitingRelease = heroStoredWaitingRelease ||
        (heroReleaseState == MediaReleaseState.UPCOMING && heroIsFavorite)
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MoviaBackgroundPrimary),
        contentPadding = PaddingValues(
            start = 0.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 0.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "my-profile") {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ProfileHeader(
                    onOpenDownloads = onOpenDownloads,
                    onOpenSettings = onOpenSettings,
                )
            }
        }

        if (hasHero) {
            item(key = "my-profile-hero-gap") { Spacer(Modifier.height(18.dp)) }
            item(key = "my-resume") {
                ResumeHero(
                    item = resumeItem,
                    progress = requireNotNull(resumeProgress),
                    releaseState = heroReleaseState,
                    isFavorite = heroIsFavorite,
                    isWaitingRelease = heroIsWaitingRelease,
                    onContinue = {
                        val mediaRef = resumeProgress.mediaRef
                            ?: resumeItem?.id?.let { MediaRef(it) }
                        onContinue(mediaRef, resumeProgress.title)
                    },
                    onToggleFavorite = {
                        resumeItem?.let { item ->
                            onToggleFavorite(item.id, item.title, !heroIsFavorite)
                        }
                    },
                    onToggleWaitingRelease = {
                        resumeItem?.let { item ->
                            val enabled = !heroIsWaitingRelease
                            if (heroIsFavorite) onToggleFavorite(item.id, item.title, false)
                            onToggleWaitingRelease(item.id, item.title, enabled)
                        }
                    },
                    onOpenDetails = {
                        resumeItem?.let { item -> onOpenDetails(item.id, item.title) }
                    },
                )
            }
        }

        if (displayRecentItems.isNotEmpty()) {
            item(key = "my-recent-gap") { Spacer(Modifier.height(10.dp)) }
            item(key = "my-recent") {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    MediaShelfSection(
                        title = "Недавно смотрели",
                        actionText = "Вся история",
                        items = displayRecentItems,
                        itemTestTagPrefix = "library.history.item",
                        onAction = { routeName = LibrarySection.HISTORY.name },
                        onOpenDetails = onOpenDetails,
                    )
                }
            }
        }

        item(key = "my-lists-gap") { Spacer(Modifier.height(22.dp)) }
        item(key = "my-lists") {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                MyListsSection(
                    selectedTab = selectedListTab,
                    onSelectedTabChange = { selectedListTab = it },
                    favoriteItems = displayFavoriteItems,
                    waitingReleaseItems = displayWaitingReleaseItems,
                    onOpenDetails = onOpenDetails,
                    onOpenCatalog = onOpenCatalog,
                )
            }
        }
    }

}

@Composable
private fun ProfileHeader(
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(69.dp),
            contentAlignment = Alignment.Center,
        ) {
            MoviaHeaderCircleControl(
                glyph = MoviaHeaderGlyph.Profile,
                modifier = Modifier.graphicsLayer {
                    scaleX = 0.95f
                    scaleY = 0.95f
                },
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Пользователь",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderUtilityAction(
                onClick = onOpenDownloads,
                modifier = Modifier.testTag("my_downloads"),
            ) { imageModifier, feedbackAlpha ->
                DownloadProfileControl(imageModifier, feedbackAlpha)
            }
            SettingsHeaderUtilityAction(
                onClick = onOpenSettings,
                modifier = Modifier.testTag("my_settings"),
            )
        }
    }
}

@Composable
private fun HeaderUtilityAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier, Float) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val reduceMotion = rememberHeaderReduceMotion()
    val scale = remember { Animatable(1f) }
    val actionTrigger = rememberMoviaActionTriggerState()
    val iconTapAlpha = rememberMoviaIconTapAlpha(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 500L, onClick)

    LaunchedEffect(pressed, reduceMotion) {
        if (reduceMotion) scale.snapTo(1f)
        else if (pressed) scale.animateTo(0.96f, tween(500, easing = FastOutSlowInEasing))
        else if (scale.value != 1f) scale.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = animatedOnClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content(
            Modifier.graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
            iconTapAlpha,
        )
    }
}

@Composable
private fun SettingsHeaderUtilityAction(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val actionTrigger = rememberMoviaActionTriggerState()
    val motion = rememberMoviaGearMotion(actionTrigger)
    val iconTapAlpha = rememberMoviaIconTapAlpha(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 500L, onClick)
    Box(
        modifier = modifier
            .size(56.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = animatedOnClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SettingsProfileIcon(
            glyphColor = lerp(MoviaTextPrimary, MoviaBrandAmber, iconTapAlpha.coerceIn(0f, 1f)),
            glyphModifier = Modifier.graphicsLayer {
                scaleX = motion.scale
                scaleY = motion.scale
                rotationZ = motion.rotationZ
            },
        )
    }
}

@Composable
private fun rememberHeaderReduceMotion(): Boolean = app.movia.android.ui.components.rememberMoviaReducedMotion()


@Composable
private fun DownloadProfileControl(
    modifier: Modifier = Modifier,
    iconTapAlpha: Float = 0f,
) {
    MoviaHeaderCircleControl(
        glyph = MoviaHeaderGlyph.Download,
        modifier = modifier,
        glyphColor = lerp(MoviaTextPrimary, MoviaBrandAmber, iconTapAlpha.coerceIn(0f, 1f)),
        description = "Загрузки",
    )
}

@Composable
private fun SettingsProfileIcon(
    modifier: Modifier = Modifier,
    glyphModifier: Modifier = Modifier,
    glyphColor: Color = MoviaTextPrimary,
) {
    MoviaHeaderCircleControl(
        glyph = MoviaHeaderGlyph.Settings,
        modifier = modifier,
        glyphModifier = glyphModifier,
        glyphColor = glyphColor,
        description = "Настройки",
    )
}

@Composable
private fun ResumeHero(
    item: MediaContent?,
    progress: PlaybackProgress,
    releaseState: MediaReleaseState,
    isFavorite: Boolean,
    isWaitingRelease: Boolean,
    onContinue: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleWaitingRelease: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = (maxWidth - 30.dp).coerceAtLeast(0.dp)
        val contentWidth = (cardWidth - 32.dp).coerceAtLeast(0.dp)
        val heroWidth = cardWidth
        val heroHeight = contentWidth / (16f / 10.2465f)
        val heroWidthDp = heroWidth.value
        val heroHeightDp = heroHeight.value
        val ambientOverflow = 56.dp

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(cardWidth),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(heroHeight),
            ) {
                if (item != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .requiredSize(
                                width = heroWidth + ambientOverflow * 2,
                                height = heroHeight + ambientOverflow,
                            )
                            .moviaAmbient(
                                artworkUrl = item.backdropUrl ?: item.posterUrl,
                                strength = MoviaAmbientStrength.MY,
                                cacheKey = item.id,
                                posterLeftDp = ambientOverflow.value,
                                posterTopDp = ambientOverflow.value,
                                posterWidthDp = heroWidthDp,
                                posterHeightDp = heroHeightDp,
                            ),
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomEnd = 0.dp,
                        bottomStart = 0.dp,
                    ),
                    color = MoviaBackgroundPrimary,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (item != null) {
                            MoviaArtwork(
                                url = item.backdropUrl ?: item.posterUrl,
                                modifier = Modifier.fillMaxSize(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                            )
                        }

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Existing readability shadow.
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.28f to Color.Transparent,
                                        0.52f to Color.Black.copy(alpha = 0.10f),
                                        0.70f to Color.Black.copy(alpha = 0.38f),
                                        0.86f to Color.Black.copy(alpha = 0.68f),
                                        1.00f to Color.Black.copy(alpha = 0.88f),
                                    ),
                                ),
                            )

                            // Restore the previous lower fog: artwork dissolves into the page background.
                            val dissolveHeight = 72.dp.toPx()
                            val solidBackgroundHeight = 24.dp.toPx()
                            val dissolveStart = (size.height - dissolveHeight).coerceAtLeast(0f)
                            val dissolveEnd = (size.height - solidBackgroundHeight).coerceAtLeast(dissolveStart)
                            if (dissolveEnd > dissolveStart) {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.00f to Color.Transparent,
                                            0.42f to MoviaBackgroundPrimary.copy(alpha = 0.30f),
                                            0.76f to MoviaBackgroundPrimary.copy(alpha = 0.72f),
                                            1.00f to MoviaBackgroundPrimary,
                                        ),
                                        startY = dissolveStart,
                                        endY = dissolveEnd,
                                    ),
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, dissolveStart),
                                    size = androidx.compose.ui.geometry.Size(size.width, dissolveEnd - dissolveStart),
                                )
                            }
                            if (solidBackgroundHeight > 0f) {
                                drawRect(
                                    color = MoviaBackgroundPrimary,
                                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - solidBackgroundHeight),
                                    size = androidx.compose.ui.geometry.Size(size.width, solidBackgroundHeight),
                                )
                            }
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(x = (-10).dp)
                                .width(contentWidth)
                                .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            MoviaFadeOverflowText(
                                text = item?.title ?: progress.title
                                    .substringBefore(" · S")
                                    .substringBefore(" · E")
                                    .trim(),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    lineHeight = 26.sp,
                                    fontWeight = FontWeight.Bold,
                                ),
                                maxLines = 2,
                            )

                            if (item != null) {
                                val rating = (item.imdbRating ?: item.rating)?.let(::moviaRatingLabel)
                                val genre = moviaPrimaryGenre(item)
                                val year = moviaYearLabel(item.year)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    rating?.let { HeroMetaChip(text = "★ $it", highlight = true) }
                                    genre?.let { HeroMetaChip(text = it) }
                                    year?.let { HeroMetaChip(text = it) }
                                }
                            }
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                shape = RoundedCornerShape(
                    topStart = 0.dp,
                    topEnd = 0.dp,
                    bottomEnd = 18.dp,
                    bottomStart = 18.dp,
                ),
                color = MoviaBackgroundPrimary,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .width(contentWidth)
                            .fillMaxHeight()
                            .padding(vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                    ResumeHeroActionButton(
                        secondaryLabel = remainingLabel(progress),
                        onClick = onContinue,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("library.resume.continue"),
                    )
                    if (releaseState == MediaReleaseState.UPCOMING) {
                        HeroRoundAction(
                            icon = if (isWaitingRelease) {
                                Icons.Outlined.NotificationsActive
                            } else {
                                Icons.Outlined.NotificationsNone
                            },
                            contentDescription = if (isWaitingRelease) {
                                "Убрать из списка «Жду выхода»"
                            } else {
                                "Добавить в список «Жду выхода»"
                            },
                            selected = isWaitingRelease,
                            motionKind = MoviaIconMotionKind.BELL,
                            enabled = item != null,
                            onClick = onToggleWaitingRelease,
                        )
                    } else {
                        HeroRoundAction(
                            icon = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            contentDescription = if (isFavorite) {
                                "Убрать из избранного"
                            } else {
                                "Добавить в избранное"
                            },
                            selected = isFavorite,
                            motionKind = MoviaIconMotionKind.HEART,
                            enabled = item != null,
                            onClick = onToggleFavorite,
                        )
                    }
                    HeroRoundAction(
                        icon = Icons.Outlined.Info,
                        iconSize = 30.dp,
                        contentDescription = "О фильме",
                        motionKind = MoviaIconMotionKind.INFO,
                        enabled = item != null,
                        onClick = onOpenDetails,
                    )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroMetaChip(
    text: String,
    highlight: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xB31A2130),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (highlight) MoviaBrandAmber else Color.White.copy(alpha = 0.86f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun HeroRoundAction(
    icon: ImageVector,
    iconSize: Dp = 22.4.dp,
    contentDescription: String,
    selected: Boolean = false,
    motionKind: MoviaIconMotionKind? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val actionTrigger = rememberMoviaActionTriggerState()
    val iconTapAlpha = rememberMoviaIconTapAlpha(actionTrigger)
    val actionDelayMs = if (motionKind == MoviaIconMotionKind.INFO) 500L else 0L
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, actionDelayMs, onClick)
    val iconMotion = motionKind?.let { kind ->
        rememberMoviaIconMotion(
            kind = kind,
            active = selected,
            trigger = if (kind == MoviaIconMotionKind.INFO) actionTrigger.token else 0,
            animateStateChanges = kind != MoviaIconMotionKind.INFO,
        )
    }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = animatedOnClick,
            )
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(44.8.dp),
            shape = CircleShape,
            color = Color(0xE61A2130),
            border = BorderStroke(
                1.dp,
                if (selected) MoviaBrandAmber.copy(alpha = 0.74f) else Color.White.copy(alpha = 0.14f),
            ),
            contentColor = if (selected) {
                MoviaBrandAmber
            } else {
                lerp(Color.White, MoviaBrandAmber, iconTapAlpha.coerceIn(0f, 1f))
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (motionKind == MoviaIconMotionKind.BELL) {
                    MoviaHeaderGlyphIcon(
                        glyph = MoviaHeaderGlyph.Notification,
                        color = if (selected) {
                            MoviaBrandAmber
                        } else {
                            lerp(Color.White, MoviaBrandAmber, iconTapAlpha.coerceIn(0f, 1f))
                        },
                        modifier = Modifier
                            .size(iconSize)
                            .then(
                                if (iconMotion == null) Modifier else Modifier.graphicsLayer {
                                    scaleX = iconMotion.scale
                                    scaleY = iconMotion.scale
                                    rotationZ = iconMotion.rotationZ
                                    transformOrigin = iconMotion.transformOrigin
                                },
                            ),
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(iconSize)
                            .then(
                                if (iconMotion == null) Modifier else Modifier.graphicsLayer {
                                    scaleX = iconMotion.scale
                                    scaleY = iconMotion.scale
                                    rotationZ = iconMotion.rotationZ
                                    transformOrigin = iconMotion.transformOrigin
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaShelfSection(
    title: String,
    actionText: String,
    items: List<MediaContent>,
    itemTestTagPrefix: String,
    onAction: () -> Unit,
    onOpenDetails: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MoviaSectionHeaderContentGap)) {
        SectionHeader(
            title = title,
            actionText = actionText,
            onAction = onAction,
            actionTestTag = "library.history.all",
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val cardWidth = (maxWidth - 24.dp) * 0.38f
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                items(items, key = { "$title-${it.id}" }) { item ->
                    RecentMediaCard(
                        item = item,
                        modifier = Modifier
                            .width(cardWidth)
                            .testTag("$itemTestTagPrefix.${item.id}"),
                        onClick = { onOpenDetails(item.id, item.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentMediaCard(
    item: MediaContent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val coverFeedback = rememberMoviaMediaCoverFeedback(onClick)
    Column(
        modifier = modifier.clickable(onClick = coverFeedback.onClick),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        MoviaMediaCoverFrame(
            feedback = coverFeedback,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
            shape = RoundedCornerShape(14.dp),
            backgroundColor = MaterialTheme.colorScheme.surface,
        ) {
            MoviaArtwork(
                url = item.posterUrl,
                modifier = Modifier.fillMaxSize(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
        }
        MoviaFadeOverflowText(
            text = item.title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 2,
        )
        MediaMetadataRow(
            item = item,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun MyListsSection(
    selectedTab: MyListTab,
    onSelectedTabChange: (MyListTab) -> Unit,
    favoriteItems: List<MediaContent>,
    waitingReleaseItems: List<MediaContent>,
    onOpenDetails: (String, String) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Мои списки")

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 16.dp),
        ) {
            item("favorites") {
                MyListChip(
                    label = "Избранное",
                    icon = Icons.Filled.Favorite,
                    selected = selectedTab == MyListTab.FAVORITES,
                    motionKind = MoviaIconMotionKind.HEART,
                    onClick = { onSelectedTabChange(MyListTab.FAVORITES) },
                )
            }
            item("waiting") {
                MyListChip(
                    label = "Жду выхода",
                    icon = Icons.Outlined.NotificationsNone,
                    selected = selectedTab == MyListTab.WAITING_RELEASE,
                    motionKind = MoviaIconMotionKind.BELL,
                    onClick = { onSelectedTabChange(MyListTab.WAITING_RELEASE) },
                )
            }
        }

        when (selectedTab) {
            MyListTab.FAVORITES -> {
                if (favoriteItems.isEmpty()) {
                    Text(
                        text = "В избранном пока ничего нет",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenCatalog)
                            .padding(vertical = 10.dp),
                    )
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val cardWidth = (maxWidth - 24.dp) * 0.38f
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 16.dp),
                        ) {
                            items(favoriteItems, key = { "favorite-${it.id}" }) { item ->
                                MediaContentCard(
                                    item = item,
                                    modifier = Modifier
                                        .width(cardWidth)
                                        .testTag("library.favorite.item.${item.id}"),
                                    titleFontSize = 14.sp,
                                    onClick = { onOpenDetails(item.id, item.title) },
                                )
                            }
                        }
                    }
                }
            }
            MyListTab.WAITING_RELEASE -> {
                if (waitingReleaseItems.isEmpty()) {
                    EmptyListHint("Здесь будут фильмы и сериалы, выхода которых вы ждёте")
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val cardWidth = (maxWidth - 24.dp) * 0.38f
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 16.dp),
                        ) {
                            items(waitingReleaseItems, key = { "waiting-${it.id}" }) { item ->
                                MediaContentCard(
                                    item = item,
                                    modifier = Modifier
                                        .width(cardWidth)
                                        .testTag("library.waiting.item.${item.id}"),
                                    titleFontSize = 14.sp,
                                    onClick = { onOpenDetails(item.id, item.title) },
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
private fun MyListChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    motionKind: MoviaIconMotionKind,
    onClick: () -> Unit,
) {
    val iconMotion = rememberMoviaIconMotion(motionKind, selected)
    Surface(
        onClick = onClick,
        modifier = Modifier.height(42.dp),
        shape = RoundedCornerShape(21.dp),
        color = if (selected) MoviaBrandAmber.copy(alpha = 0.10f) else Color(0xCC15181D),
        border = BorderStroke(
            1.dp,
            if (selected) MoviaBrandAmber else Color.White.copy(alpha = 0.10f),
        ),
        contentColor = if (selected) MoviaBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (motionKind == MoviaIconMotionKind.BELL) {
                MoviaHeaderGlyphIcon(
                    glyph = MoviaHeaderGlyph.Notification,
                    color = if (selected) MoviaBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = iconMotion.scale
                            scaleY = iconMotion.scale
                            rotationZ = iconMotion.rotationZ
                            transformOrigin = iconMotion.transformOrigin
                        },
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(20.dp)
                        .graphicsLayer {
                            scaleX = iconMotion.scale
                            scaleY = iconMotion.scale
                            rotationZ = iconMotion.rotationZ
                            transformOrigin = iconMotion.transformOrigin
                        },
                )
            }
            Text(
                text = label,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyListHint(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

@Composable
private fun SectionHeader(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    actionTestTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (actionText != null && onAction != null) {
            MoviaSectionAction(
                label = actionText,
                actionTestTag = actionTestTag,
                onClick = onAction,
            )
        }
    }
}

private fun remainingLabel(progress: PlaybackProgress): String {
    val remainingMs = (progress.durationMs - progress.positionMs).coerceAtLeast(0L)
    val minutes = ceil(remainingMs / 60_000.0).toInt()
    return "Осталось $minutes мин"
}

private fun seasonEpisodeLabel(title: String): String? {
    val match = Regex("S(\\d{1,2})E(\\d{1,2})").find(title) ?: return null
    val season = match.groupValues[1].toIntOrNull() ?: return null
    val episode = match.groupValues[2].toIntOrNull() ?: return null
    return "$season сезон · $episode серия"
}

@Composable
private fun ResumeHeroActionButton(
    secondaryLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val actionTrigger = rememberMoviaActionTriggerState()
    val motion = rememberMoviaPlaybackActionMotion(actionTrigger)
    val animatedOnClick = rememberMoviaAnimatedAction(actionTrigger, 220L, onClick)
    val shape = RoundedCornerShape(18.dp)
    Surface(
        onClick = animatedOnClick,
        interactionSource = interactionSource,
        modifier = modifier
            .height(64.dp)
            .graphicsLayer {
                scaleX = motion.surfaceScale
                scaleY = motion.surfaceScale
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Продолжить просмотр"
            },
        shape = shape,
        color = Color.Transparent,
        contentColor = MoviaOnBrandAmber,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .background(
                        color = if (pressed) MoviaBrandAmber else MoviaBrandAmber,
                        shape = shape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.offset(x = (-10).dp),
                    horizontalArrangement = Arrangement.spacedBy(12.1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = MoviaOnBrandAmber,
                        modifier = Modifier.size(37.268.dp),
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.Start,
                    ) {
                        Text(
                            text = "Продолжить",
                            color = MoviaOnBrandAmber,
                            fontSize = 15.sp,
                            lineHeight = 19.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Text(
                            text = secondaryLabel,
                            color = MoviaOnBrandAmber.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private val HistoryIcon: ImageVector by lazy {
    ImageVector.Builder("MoviaHistory", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
            moveTo(3f,12f); curveTo(3f,16.97f,7.03f,21f,12f,21f); curveTo(16.97f,21f,21f,16.97f,21f,12f); curveTo(21f,7.03f,16.97f,3f,12f,3f); curveTo(9.77f,3f,7.73f,3.81f,6f,5.3f)
            moveTo(3f,4.5f); lineTo(3f,9.5f); lineTo(8f,9.5f)
            moveTo(12f,7f); lineTo(12f,12f); lineTo(15.5f,14f)
        }
    }.build()
}

private val DownloadIcon: ImageVector by lazy {
    ImageVector.Builder("MoviaDownload", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
            moveTo(12f,3f); lineTo(12f,15f); moveTo(7f,10f); lineTo(12f,15f); lineTo(17f,10f); moveTo(5f,18f); lineTo(5f,21f); lineTo(19f,21f); lineTo(19f,18f)
        }
    }.build()
}

private val CloudIcon: ImageVector by lazy {
    ImageVector.Builder("MoviaCloud", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19.35f, 10.04f)
            curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
            curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
            curveTo(2.34f, 8.36f, 0f, 10.9f, 0f, 14f)
            curveTo(0f, 17.31f, 2.69f, 20f, 6f, 20f)
            horizontalLineTo(19f)
            curveTo(21.76f, 20f, 24f, 17.76f, 24f, 15f)
            curveTo(24f, 12.36f, 21.95f, 10.22f, 19.35f, 10.04f)
            close()
        }
    }.build()
}

private val ProfileIcon: ImageVector by lazy {
    ImageVector.Builder("MoviaProfile", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f) {
            moveTo(12f,4f); curveTo(13.93f,4f,15.5f,5.57f,15.5f,7.5f); curveTo(15.5f,9.43f,13.93f,11f,12f,11f); curveTo(10.07f,11f,8.5f,9.43f,8.5f,7.5f); curveTo(8.5f,5.57f,10.07f,4f,12f,4f)
            moveTo(5.5f,20f); lineTo(5.5f,18.6f); curveTo(5.5f,15.5f,8.4f,13.5f,12f,13.5f); curveTo(15.6f,13.5f,18.5f,15.5f,18.5f,18.6f); lineTo(18.5f,20f); close()
        }
    }.build()
}

@Composable
private fun LibraryCollectionScreen(
    section: LibrarySection,
    titles: List<String>,
    records: List<LibraryMediaRecord>,
    catalog: List<MediaContent>,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onBack: () -> Unit,
    onOpenDetails: (String, String) -> Unit,
    onOpenCatalog: () -> Unit,
    onClearHistory: (() -> Unit)?,
) {
    val catalogByTitle = remember(catalog) { catalog.associateBy { it.title.lowercase().trim() } }
    val catalogById = remember(catalog) { catalog.associateBy { it.id } }
    val rows = remember(records, titles) {
        records.ifEmpty {
            titles.map { title -> LibraryMediaRecord("legacy:${title.length}:$title", null, title, 0L) }
        }
    }
    val resolvedItems = remember(rows, catalog) {
        rows.mapNotNull { record ->
            val base = record.title.substringBefore(" · S").substringBefore(" · E").trim()
            record.contentId?.let(catalogById::get)
                ?: catalogByTitle[base.lowercase()]
        }.distinctBy { it.id }
    }

    val resolvedIds = remember(resolvedItems) { resolvedItems.map { it.id }.toSet() }
    val resolvedTitles = remember(resolvedItems) { resolvedItems.map { it.title.lowercase().trim() }.toSet() }
    val unresolved = remember(rows, resolvedIds, resolvedTitles) {
        rows.filter { record ->
            val base = record.title.substringBefore(" · S").substringBefore(" · E").trim().lowercase()
            record.contentId?.let { it !in resolvedIds } ?: (base !in resolvedTitles)
        }.distinctBy { it.mediaKey }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 168.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, key = "library-child-header") {
            MoviaChildTopBar(
                title = sectionTitle(section),
                onBack = onBack,
                actionText = if (onClearHistory != null) "Очистить" else null,
                actionTestTag = if (onClearHistory != null) "library.history.clear" else null,
                onAction = onClearHistory,
            )
        }

        if (resolvedItems.isEmpty() && unresolved.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "library-empty") {
                LibraryEmptyState(
                    section = section,
                    onOpenCatalog = onOpenCatalog,
                )
            }
        } else {
            val itemTestTagPrefix = when (section) {
                LibrarySection.FAVORITES, LibrarySection.BOOKMARKS, LibrarySection.LATER -> "library.favorite.item"
                LibrarySection.DOWNLOADS -> "library.download.item"
                LibrarySection.HISTORY -> "library.history.item"
            }
            items(resolvedItems, key = { it.id }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth().testTag("$itemTestTagPrefix.${item.id}"),
                    onClick = { onOpenDetails(item.id, item.title) },
                )
            }
            items(unresolved, key = { it.mediaKey }) { record ->
                val item = legacyLibraryMediaContent(record.title, record.contentId)
                MediaContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth().testTag("$itemTestTagPrefix.${record.contentId ?: record.mediaKey}"),
                    onClick = {
                        onOpenDetails(
                            record.contentId.orEmpty(),
                            record.title.substringBefore(" · S").substringBefore(" · E"),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptyState(
    section: LibrarySection,
    onOpenCatalog: () -> Unit,
) {
    val icon = when (section) {
        LibrarySection.BOOKMARKS, LibrarySection.FAVORITES, LibrarySection.LATER -> Icons.Outlined.BookmarkBorder
        LibrarySection.DOWNLOADS -> Icons.Outlined.Download
        LibrarySection.HISTORY -> Icons.Outlined.History
    }
    val title = when (section) {
        LibrarySection.BOOKMARKS, LibrarySection.FAVORITES, LibrarySection.LATER -> "Закладки пока пусты"
        LibrarySection.DOWNLOADS -> "Скачанных материалов пока нет"
        LibrarySection.HISTORY -> "История просмотра пока пуста"
    }
    val description = when (section) {
        LibrarySection.BOOKMARKS, LibrarySection.FAVORITES, LibrarySection.LATER -> "Сохраняйте интересные фильмы и сериалы, чтобы быстро вернуться к ним."
        LibrarySection.DOWNLOADS -> "Загруженные материалы для офлайн-просмотра появятся здесь."
        LibrarySection.HISTORY -> "После просмотра контент появится здесь автоматически."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        Text(
            text = description,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 320.dp),
        )
        if (section != LibrarySection.HISTORY) {
            Button(
                onClick = onOpenCatalog,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MoviaBrandAmber,
                    contentColor = MoviaOnBrandAmber,
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Перейти в каталог", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun legacyLibraryMediaContent(storedTitle: String, contentId: String? = null): MediaContent {
    val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
    val episodic = storedTitle.contains(Regex(" · S\\d{2}E\\d{2}"))
    return MediaContent(
        id = contentId?.takeIf { it.isNotBlank() } ?: "saved:${storedTitle.hashCode()}",
        title = base,
        type = if (episodic) ContentType.SERIES else ContentType.MOVIE,
        year = 0,
        rating = 0.0,
        genres = emptySet(),
        country = "",
        quality = "",
        durationMinutes = 0,
        ageRating = 0,
        category = if (episodic) CatalogCategory.TV_SERIES else CatalogCategory.MOVIES,
    )
}

private fun sectionTitle(section: LibrarySection): String = when (section) {
    LibrarySection.BOOKMARKS, LibrarySection.FAVORITES, LibrarySection.LATER -> "Закладки"
    LibrarySection.DOWNLOADS -> "Скачанное"
    LibrarySection.HISTORY -> "История"
}

private fun collectionCountLabel(section: LibrarySection, count: Int): String = when (section) {
    LibrarySection.BOOKMARKS, LibrarySection.FAVORITES, LibrarySection.LATER -> "$count ${pluralRu(count, "материал", "материала", "материалов")}"
    LibrarySection.DOWNLOADS -> "$count ${pluralRu(count, "загрузка", "загрузки", "загрузок")}"
    LibrarySection.HISTORY -> "$count ${pluralRu(count, "просмотр", "просмотра", "просмотров")}"
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
