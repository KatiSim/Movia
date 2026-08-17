package app.viora.android.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.domain.model.PlaybackProgress
import kotlin.math.ceil

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
    progress: PlaybackProgress = PlaybackProgress(),
    onContinuePlayback: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onClearHistory: (List<String>) -> Unit,
) {
    var expandedSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val expandedSection = expandedSectionName?.let(LibrarySection::valueOf)
    val hasProgress = progress.title.isNotBlank() && progress.positionMs > 0L && progress.durationMs > 0L

    val savedEntries = listOf(
        LibraryEntry(LibrarySection.FAVORITES, "Избранное", favorites.size, Icons.Outlined.FavoriteBorder),
        LibraryEntry(LibrarySection.LATER, "Посмотреть позже", watchLater.size, Icons.Outlined.WatchLater),
        LibraryEntry(LibrarySection.DOWNLOADS, "Скачанное", downloads.size, Icons.Outlined.Download),
    )
    val activityEntries = listOf(
        LibraryEntry(LibrarySection.HISTORY, "История", history.size, Icons.Outlined.History),
    )

    fun toggle(section: LibrarySection) {
        expandedSectionName = if (expandedSection == section) null else section.name
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Моё",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    "Сохранённое, загрузки и история",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (hasProgress) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Продолжить просмотр", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    ContinueWatchingCard(
                        progress = progress,
                        onClick = { onContinuePlayback(progress.title) },
                    )
                }
            }
        }

        item {
            LibraryGroup(
                title = "Сохранённое",
                entries = savedEntries,
                expanded = expandedSection,
                onEntryClick = ::toggle,
            )
        }

        if (expandedSection in setOf(LibrarySection.FAVORITES, LibrarySection.LATER, LibrarySection.DOWNLOADS)) {
            item {
                when (expandedSection) {
                    LibrarySection.FAVORITES -> ExpandedCollection(
                        emptyText = "В избранном пока ничего нет.",
                        items = favorites.sorted(),
                        onOpenDetails = onOpenDetails,
                    )
                    LibrarySection.LATER -> ExpandedCollection(
                        emptyText = "Список «Посмотреть позже» пока пуст.",
                        items = watchLater.sorted(),
                        onOpenDetails = onOpenDetails,
                    )
                    LibrarySection.DOWNLOADS -> ExpandedCollection(
                        emptyText = "Офлайн-загрузок пока нет.",
                        items = downloads.sorted(),
                        onOpenDetails = onOpenDetails,
                    )
                    else -> Unit
                }
            }
        }

        item {
            LibraryGroup(
                title = "Активность",
                entries = activityEntries,
                expanded = expandedSection,
                onEntryClick = ::toggle,
            )
        }

        if (expandedSection == LibrarySection.HISTORY) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExpandedCollection(
                        emptyText = "История просмотра пока пуста.",
                        items = history.take(10),
                        onOpenDetails = onOpenDetails,
                    )
                    if (history.isNotEmpty()) {
                        TextButton(onClick = { onClearHistory(history) }) {
                            Text("Очистить историю")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: PlaybackProgress,
    onClick: () -> Unit,
) {
    val remainingMinutes = ceil(((progress.durationMs - progress.positionMs).coerceAtLeast(0L)) / 60_000.0).toInt()
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Продолжить ${progress.title}. Осталось около $remainingMinutes мин"
            },
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(132.dp)
                    .height(82.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    Icons.Outlined.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp).align(Alignment.Center),
                )
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    progress.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Осталось ≈ $remainingMinutes мин",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Outlined.PlayCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun LibraryGroup(
    title: String,
    entries: List<LibraryEntry>,
    expanded: LibrarySection?,
    onEntryClick: (LibrarySection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                entries.forEachIndexed { index, entry ->
                    ListItem(
                        headlineContent = { Text(entry.title, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(collectionCountLabel(entry.section, entry.count)) },
                        leadingContent = {
                            Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        trailingContent = {
                            Icon(
                                if (expanded == entry.section) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp)
                            .clickable { onEntryClick(entry.section) },
                    )
                    if (index != entries.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 64.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpandedCollection(
    emptyText: String,
    items: List<String>,
    onOpenDetails: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (items.isEmpty()) {
                Text(
                    emptyText,
                    modifier = Modifier.padding(vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                items.forEachIndexed { index, title ->
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenDetails(title) }
                            .padding(vertical = 14.dp),
                    )
                    if (index != items.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}

private fun collectionCountLabel(section: LibrarySection, count: Int): String = when (section) {
    LibrarySection.FAVORITES -> "$count ${pluralRu(count, "сохранённый", "сохранённых", "сохранённых")}"
    LibrarySection.LATER -> "$count ${pluralRu(count, "позиция", "позиции", "позиций")}"
    LibrarySection.DOWNLOADS -> "$count ${pluralRu(count, "загрузка", "загрузки", "загрузок")}"
    LibrarySection.HISTORY -> "$count ${pluralRu(count, "недавний", "недавних", "недавних")}"
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
