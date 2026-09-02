package app.movia.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.ui.components.MediaContentCard
import app.movia.android.ui.components.MoviaPageTitle
import app.movia.android.ui.components.MoviaChildTopBar
import app.movia.android.ui.components.SectionHeader
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaDividerSubtle
import app.movia.android.ui.theme.MoviaOnBrandAmber

private enum class LibrarySection { BOOKMARKS, FAVORITES, LATER, DOWNLOADS, HISTORY }

private data class LibraryEntry(
    val section: LibrarySection,
    val title: String,
    val count: Int,
    val icon: ImageVector,
)

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    watchLater: Set<String> = emptySet(),
    history: List<String> = emptyList(),
    downloads: Set<String> = emptySet(),
    catalog: List<MediaContent> = emptyList(),
    onOpenDetails: (String, String?) -> Unit,
    onOpenCatalog: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearHistory: (List<String>) -> Unit,
) {
    var routeName by rememberSaveable { mutableStateOf<String?>(null) }
    val route = routeName?.let(LibrarySection::valueOf)

    BackHandler(enabled = route != null) { routeName = null }

    if (route != null) {
        val sourceTitles = when (route) {
            LibrarySection.BOOKMARKS, LibrarySection.FAVORITES, LibrarySection.LATER -> (favorites + watchLater).distinct().sorted()
            LibrarySection.DOWNLOADS -> downloads.sorted()
            LibrarySection.HISTORY -> history
        }
        LibraryCollectionScreen(
            section = route,
            titles = sourceTitles,
            catalog = catalog,
            contentPadding = contentPadding,
            modifier = modifier,
            onBack = { routeName = null },
            onOpenDetails = onOpenDetails,
            onOpenCatalog = onOpenCatalog,
            onClearHistory = if (route == LibrarySection.HISTORY && history.isNotEmpty()) {
                { onClearHistory(history) }
            } else {
                null
            },
        )
        return
    }

    val bookmarkTitles = (favorites + watchLater).distinct()
    val bookmarkItems = bookmarkTitles.mapNotNull { storedTitle ->
        val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
        catalog.firstOrNull { it.title.equals(base, ignoreCase = true) }
    }.distinctBy { it.id }

    val savedEntries = listOf(
        LibraryEntry(LibrarySection.BOOKMARKS, "Закладки", bookmarkTitles.size, Icons.Outlined.BookmarkBorder),
        LibraryEntry(LibrarySection.DOWNLOADS, "Скачанное", downloads.size, Icons.Outlined.Download),
    )
    val activityEntries = listOf(
        LibraryEntry(LibrarySection.HISTORY, "История", history.size, Icons.Outlined.History),
    )

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
        item {
            LibraryHeader(
                onOpenProfile = onOpenProfile,
                onOpenSettings = onOpenSettings,
            )
        }

        item {
            LibraryGroup(
                title = "Медиатека",
                entries = savedEntries,
                onEntryClick = { routeName = it.name },
            )
        }

        item {
            LibraryGroup(
                title = "Активность",
                entries = activityEntries,
                onEntryClick = { routeName = it.name },
            )
        }

        if (bookmarkItems.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(title = "Недавние закладки")
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(end = 12.dp),
                    ) {
                        items(bookmarkItems.take(8), key = { "bookmark-" + it.id }) { item ->
                            MediaContentCard(
                                item = item,
                                modifier = Modifier.width(136.dp),
                                onClick = { onOpenDetails(item.title, item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    onOpenProfile: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MoviaPageTitle(text = "Моё", modifier = Modifier.weight(1f))
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.size(48.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MoviaBrandAmber),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = "Настройки",
                            tint = MoviaBrandAmber,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        Surface(
            onClick = onOpenProfile,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MoviaBorderSubtle),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MoviaBrandAmber),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MoviaBrandAmber,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = "Локальный профиль",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Данные сохраняются только на этом устройстве",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun LibraryGroup(
    title: String,
    entries: List<LibraryEntry>,
    onEntryClick: (LibrarySection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title = title)
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MoviaBorderSubtle),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                entries.forEachIndexed { index, entry ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = entry.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                lineHeight = 20.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        supportingContent = {
                            Text(
                                text = collectionCountLabel(entry.section, entry.count),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(1.dp, MoviaBrandAmber),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        entry.icon,
                                        contentDescription = null,
                                        tint = MoviaBrandAmber,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                        },
                        trailingContent = {
                            Icon(
                                Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        colors = androidx.compose.material3.ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clickable { onEntryClick(entry.section) },
                    )
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp),
                            color = MoviaDividerSubtle,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCollectionScreen(
    section: LibrarySection,
    titles: List<String>,
    catalog: List<MediaContent>,
    contentPadding: PaddingValues,
    modifier: Modifier,
    onBack: () -> Unit,
    onOpenDetails: (String, String?) -> Unit,
    onOpenCatalog: () -> Unit,
    onClearHistory: (() -> Unit)?,
) {
    val catalogByTitle = remember(catalog) { catalog.associateBy { it.title.lowercase().trim() } }
    val resolvedItems by produceState<List<MediaContent>>(
        initialValue = titles.mapNotNull { storedTitle ->
            val base = storedTitle.substringBefore(" · S").substringBefore(" · E").trim()
            catalogByTitle[base.lowercase()] ?: DemoCatalogRepository.findByTitle(base)
        }.distinctBy { it.id },
        titles, catalog
    ) {
        val list = withContext(Dispatchers.IO) {
            titles.mapNotNull { storedTitle ->
                val base = storedTitle.substringBefore(" · S").substringBefore(" · E").trim()
                catalogByTitle[base.lowercase()] ?: DemoCatalogRepository.findFullByTitle(base) ?: DemoCatalogRepository.findByTitle(base)
            }.distinctBy { it.id }
        }
        value = list
    }

    val resolvedIds = remember(resolvedItems) { resolvedItems.map { it.title.lowercase().trim() }.toSet() }
    val unresolved = remember(titles, resolvedIds) {
        titles.filter { storedTitle ->
            val base = storedTitle.substringBefore(" · S").substringBefore(" · E").trim().lowercase()
            base !in resolvedIds
        }.distinct()
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
            items(resolvedItems, key = { it.id }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenDetails(item.title, item.id) },
                )
            }
            items(unresolved, key = { "legacy-$it" }) { storedTitle ->
                val item = legacyLibraryMediaContent(storedTitle)
                MediaContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onOpenDetails(storedTitle.substringBefore(" · S").substringBefore(" · E"), null)
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

private fun legacyLibraryMediaContent(storedTitle: String): MediaContent {
    val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
    val episodic = storedTitle.contains(Regex(" · S\\d{2}E\\d{2}"))
    return MediaContent(
        id = "saved:${storedTitle.hashCode()}",
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
