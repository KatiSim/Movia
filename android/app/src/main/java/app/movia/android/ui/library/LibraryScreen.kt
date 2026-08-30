package app.movia.android.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.WatchLater
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

private enum class LibrarySection { FAVORITES, LATER, DOWNLOADS, HISTORY }

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
    onOpenDetails: (String) -> Unit,
    onOpenCatalog: () -> Unit,
    onClearHistory: (List<String>) -> Unit,
) {
    var routeName by rememberSaveable { mutableStateOf<String?>(null) }
    val route = routeName?.let(LibrarySection::valueOf)

    BackHandler(enabled = route != null) { routeName = null }

    if (route != null) {
        val sourceTitles = when (route) {
            LibrarySection.FAVORITES -> favorites.sorted()
            LibrarySection.LATER -> watchLater.sorted()
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

    val savedEntries = listOf(
        LibraryEntry(LibrarySection.FAVORITES, "Избранное", favorites.size, Icons.Outlined.FavoriteBorder),
        LibraryEntry(LibrarySection.LATER, "Посмотреть позже", watchLater.size, Icons.Outlined.WatchLater),
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
            MoviaPageTitle(text = "Моё")
        }

        item {
            LibraryGroup(
                title = "Сохранённое",
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
            shape = RoundedCornerShape(16.dp),
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
                            Icon(
                                entry.icon,
                                contentDescription = null,
                                tint = MoviaBrandAmber,
                                modifier = Modifier.size(24.dp),
                            )
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
                            .height(64.dp)
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
    onOpenDetails: (String) -> Unit,
    onOpenCatalog: () -> Unit,
    onClearHistory: (() -> Unit)?,
) {
    val catalogByTitle = catalog.associateBy { it.title.lowercase() }
    val resolved = titles.mapNotNull { storedTitle ->
        val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
        catalogByTitle[base.lowercase()]
    }.distinctBy { it.id }
    val unresolved = titles.filter { storedTitle ->
        val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
        catalogByTitle[base.lowercase()] == null
    }.distinct()

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

        if (resolved.isEmpty() && unresolved.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "library-empty") {
                LibraryEmptyState(
                    section = section,
                    onOpenCatalog = onOpenCatalog,
                )
            }
        } else {
            items(resolved, key = { it.id }) { item ->
                MediaContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onOpenDetails(item.title) },
                )
            }
            items(unresolved, key = { "legacy-$it" }) { storedTitle ->
                val item = legacyLibraryMediaContent(storedTitle)
                MediaContentCard(
                    item = item,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onOpenDetails(storedTitle.substringBefore(" · S").substringBefore(" · E"))
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
        LibrarySection.FAVORITES -> Icons.Outlined.FavoriteBorder
        LibrarySection.LATER -> Icons.Outlined.WatchLater
        LibrarySection.DOWNLOADS -> Icons.Outlined.Download
        LibrarySection.HISTORY -> Icons.Outlined.History
    }
    val title = when (section) {
        LibrarySection.FAVORITES -> "В избранном пока ничего нет"
        LibrarySection.LATER -> "Список «Посмотреть позже» пуст"
        LibrarySection.DOWNLOADS -> "Скачанных материалов пока нет"
        LibrarySection.HISTORY -> "История просмотра пока пуста"
    }
    val description = when (section) {
        LibrarySection.FAVORITES -> "Добавляйте фильмы и сериалы в избранное, чтобы быстро возвращаться к ним."
        LibrarySection.LATER -> "Сохраняйте интересный контент, который хотите посмотреть позже."
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
    LibrarySection.FAVORITES -> "Избранное"
    LibrarySection.LATER -> "Посмотреть позже"
    LibrarySection.DOWNLOADS -> "Скачанное"
    LibrarySection.HISTORY -> "История"
}

private fun collectionCountLabel(section: LibrarySection, count: Int): String = when (section) {
    LibrarySection.FAVORITES, LibrarySection.LATER -> "$count ${pluralRu(count, "материал", "материала", "материалов")}"
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
