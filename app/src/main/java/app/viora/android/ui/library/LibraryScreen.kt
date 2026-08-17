package app.viora.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class LibraryEntry(val title: String, val subtitle: String, val icon: ImageVector)

@Composable
fun LibraryScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    watchLater: Set<String> = emptySet(),
    history: List<String> = emptyList(),
    downloads: Set<String> = emptySet(),
    hasProgress: Boolean = false,
    onOpenDetails: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    val entries = listOf(
        LibraryEntry("Продолжить просмотр", if (hasProgress) "Есть незавершённый просмотр" else "Пока пусто", Icons.Outlined.PlayCircleOutline),
        LibraryEntry("Избранное", "${favorites.size} сохранённых", Icons.Outlined.FavoriteBorder),
        LibraryEntry("Посмотреть позже", "${watchLater.size} позиций", Icons.Outlined.WatchLater),
        LibraryEntry("История", "${history.size} недавних", Icons.Outlined.History),
        LibraryEntry("Скачанное", "${downloads.size} офлайн", Icons.Outlined.Download),
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Моё", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Text("Ваш контент и история просмотра", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        items(entries.size) { index ->
            val entry = entries[index]
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                ListItem(
                    headlineContent = { Text(entry.title) },
                    supportingContent = { Text(entry.subtitle) },
                    leadingContent = { Icon(entry.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }

        if (favorites.isNotEmpty()) {
            item { CollectionSection("Избранное", favorites.sorted(), onOpenDetails) }
        }
        if (watchLater.isNotEmpty()) {
            item { CollectionSection("Посмотреть позже", watchLater.sorted(), onOpenDetails) }
        }
        if (history.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("История", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    history.take(10).forEach { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth().clickable { onOpenDetails(title) }.padding(vertical = 8.dp),
                        )
                    }
                    TextButton(onClick = onClearHistory) { Text("Очистить историю") }
                }
            }
        }
        if (downloads.isNotEmpty()) {
            item { CollectionSection("Скачанное", downloads.sorted(), onOpenDetails) }
        }
    }
}

@Composable
private fun CollectionSection(
    title: String,
    items: List<String>,
    onOpenDetails: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        items.forEach { item ->
            Text(
                text = item,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onOpenDetails(item) }.padding(vertical = 8.dp),
            )
        }
    }
}
