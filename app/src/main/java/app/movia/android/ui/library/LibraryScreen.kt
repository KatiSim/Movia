package app.movia.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.domain.model.LibraryMediaRecord
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MediaMetadataRow
import app.movia.android.ui.components.MoviaAmbientStrength
import app.movia.android.ui.components.MoviaArtwork
import app.movia.android.ui.components.MoviaSpotlightActionButton
import app.movia.android.ui.components.MoviaChildTopBar
import app.movia.android.ui.components.moviaAmbient
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaIconPrimary
import app.movia.android.ui.theme.MoviaOnBrandAmber
import kotlin.math.ceil

private enum class LibrarySection { BOOKMARKS, FAVORITES, LATER, DOWNLOADS, HISTORY }

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    favoriteRecords: List<LibraryMediaRecord> = emptyList(),
    history: List<String> = emptyList(),
    historyRecords: List<LibraryMediaRecord> = emptyList(),
    downloads: Set<String> = emptySet(),
    downloadRecords: List<LibraryMediaRecord> = emptyList(),
    catalog: List<MediaContent> = emptyList(),
    progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    progressByMediaRef: Map<String, PlaybackProgress> = emptyMap(),
    resumeHeroState: ResumeHeroState = ResumeHeroState.None,
    onContinue: (MediaRef?, String) -> Unit,
    onOpenDetails: (String, String) -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClearHistory: (List<String>) -> Unit,
) {
    val availableCatalog = remember(catalog) { catalog.distinctBy { it.id } }
    var routeName by rememberSaveable { mutableStateOf<String?>(null) }
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
    val historyRows = historyRecords.ifEmpty {
        history.map { title -> LibraryMediaRecord("legacy:${title.length}:$title", null, title, 0L) }
    }
    val favoriteItems = favoriteRows.mapNotNull(::resolve).distinctBy { it.id }
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding(),
        ),
    ) {
        item(key = "my-profile") {
            ProfileHeader(
                onOpenDownloads = onOpenDownloads,
                onOpenSettings = onOpenSettings,
            )
        }

        if (hasHero) {
            item(key = "my-profile-hero-gap") { Spacer(Modifier.height(26.dp)) }
            item(key = "my-resume") {
                ResumeHero(
                    item = resumeItem,
                    progress = requireNotNull(resumeProgress),
                    onContinue = {
                        val mediaRef = resumeProgress.mediaRef
                            ?: resumeItem?.id?.let { MediaRef(it) }
                        onContinue(mediaRef, resumeProgress.title)
                    },
                )
            }
        }

        if (displayRecentItems.isNotEmpty()) {
            item(key = "my-recent-gap") { Spacer(Modifier.height(24.dp)) }
            item(key = "my-recent") {
                MediaShelfSection(
                    title = "Недавнее",
                    actionText = "Вся история",
                    items = displayRecentItems,
                    itemTestTagPrefix = "library.history.item",
                    onAction = { routeName = LibrarySection.HISTORY.name },
                    onOpenDetails = onOpenDetails,
                )
            }
        }

        item(key = "my-favorites-gap") { Spacer(Modifier.height(24.dp)) }
        item(key = "my-favorites") {
            FavoritesSection(
                items = favoriteItems,
                itemTestTagPrefix = "library.favorite.item",
                onOpenAll = { routeName = LibrarySection.FAVORITES.name },
                onOpenDetails = onOpenDetails,
                onOpenCatalog = onOpenCatalog,
            )
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
        Box(modifier = Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MoviaBorderSubtle),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        ProfileIcon,
                        contentDescription = null,
                        tint = MoviaIconPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Пользователь",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = CloudIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Синхронизация",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onOpenDownloads, modifier = Modifier.size(48.dp).testTag("my_downloads")) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = "Загрузки",
                    tint = MoviaIconPrimary,
                    modifier = Modifier.size(26.dp),
                )
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp).testTag("my_settings")) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Настройки",
                    tint = MoviaIconPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun ResumeHero(
    item: MediaContent?,
    progress: PlaybackProgress,
    onContinue: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val heroWidth = maxWidth
        val heroHeight = heroWidth / (16f / 9.9f)
        val heroWidthDp = heroWidth.value
        val heroHeightDp = heroHeight.value
        val ambientOverflow = 56.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9.9f),
        ) {
            if (item != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(
                            width = heroWidth + ambientOverflow * 2,
                            height = heroHeight + ambientOverflow * 2,
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
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MoviaBorderSubtle),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (item != null) {
                        MoviaArtwork(
                            url = item.backdropUrl ?: item.posterUrl,
                            modifier = Modifier.fillMaxSize(),
                            contentDescription = null,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.00f to Color.Transparent,
                                        0.38f to Color.Transparent,
                                        0.66f to Color.Black.copy(alpha = 0.46f),
                                        1.00f to Color.Black.copy(alpha = 0.88f),
                                    ),
                                ),
                            ),
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = item?.title ?: progress.title
                                .substringBefore(" · S")
                                .substringBefore(" · E")
                                .trim(),
                            color = Color.White,
                            fontSize = 20.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = listOfNotNull(episodeLabel(progress.title), remainingLabel(progress)).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MoviaBrandAmber,
                            trackColor = Color.White.copy(alpha = 0.24f),
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            MoviaSpotlightActionButton(
                                label = "Продолжить",
                                contentDescription = "Продолжить просмотр",
                                onClick = onContinue,
                                modifier = Modifier.testTag("library.resume.continue"),
                            )
                        }
                    }
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            title = title,
            actionText = actionText,
            onAction = onAction,
            actionTestTag = if (title == "Недавнее") "library.history.all" else null,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 48.dp),
        ) {
            items(items, key = { "$title-${it.id}" }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.width(142.dp).testTag("$itemTestTagPrefix.${item.id}"),
                    titleFontSize = 14.sp,
                    onClick = { onOpenDetails(item.id, item.title) },
                )
            }
        }
    }
}

@Composable
private fun FavoritesSection(
    items: List<MediaContent>,
    itemTestTagPrefix: String,
    onOpenAll: () -> Unit,
    onOpenDetails: (String, String) -> Unit,
    onOpenCatalog: () -> Unit,
) {
    if (items.isEmpty()) {
        SectionHeader(title = "Избранное", actionText = "Все", onAction = onOpenAll, actionTestTag = "library.favorites.all")
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader(title = "Избранное", actionText = "Все", onAction = onOpenAll, actionTestTag = "library.favorites.all")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(end = 48.dp),
            ) {
                items(items, key = { "favorite-${it.id}" }) { item ->
                    MediaContentCard(
                        item = item,
                        modifier = Modifier.width(142.dp).testTag("$itemTestTagPrefix.${item.id}"),
                        titleFontSize = 14.sp,
                        onClick = { onOpenDetails(item.id, item.title) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionText: String,
    onAction: () -> Unit,
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
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(onClick = onAction)
                .then(if (actionTestTag != null) Modifier.testTag(actionTestTag) else Modifier)
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(actionText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun remainingLabel(progress: PlaybackProgress): String {
    val remainingMs = (progress.durationMs - progress.positionMs).coerceAtLeast(0L)
    val minutes = ceil(remainingMs / 60_000.0).toInt()
    return "Осталось $minutes мин"
}

private fun episodeLabel(title: String): String? {
    val match = Regex(" · S(\\d{2})E(\\d{2})").find(title) ?: return null
    return "S${match.groupValues[1].toInt()} · E${match.groupValues[2].toInt()}"
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
