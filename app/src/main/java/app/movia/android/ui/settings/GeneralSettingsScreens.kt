package app.movia.android.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.movia.android.data.preferences.PlaybackPreferences
import app.movia.android.domain.model.LibraryMediaRecord
import app.movia.android.domain.model.MediaRef
import app.movia.android.ui.components.MoviaTapIconButton
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaBrandAmber

@Composable
fun DownloadsSettingsScreen(
    preferences: PlaybackPreferences,
    downloads: List<LibraryMediaRecord>,
    onBack: () -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onDeleteTitle: (MediaRef?, String) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val diskBytes by androidx.compose.runtime.produceState(0L, downloads.size) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            java.io.File(context.filesDir,"offline").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
    }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    if (confirmDeleteAll) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text("Удалить все загрузки?") },
            text = { Text("Будет удалено загрузок: ${downloads.size} · ${diskBytes / 1_048_576L} МБ. Для просмотра без сети их понадобится скачать заново.") },
            confirmButton = { TextButton(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Отмена") } },
        )
    }
    BackHandler(onBack = onBack)
    SettingsPage(title = "Загрузки и память", onBack = onBack, modifier = modifier) {
        item {
            SettingsInfoCard(
                title = "Офлайн-хранилище",
                body = if (downloads.isEmpty()) {
                    "Скачанных файлов нет"
                } else {
                    downloads.size.toString() + " " + downloadCountLabel(downloads.size) +
                        " доступны без подключения к сети"
                },
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Скачанные файлы",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (downloads.isNotEmpty()) {
                    TextButton(onClick = { confirmDeleteAll = true }) {
                        Text("Удалить всё", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (downloads.isEmpty()) {
            item {
                Text(
                    "Здесь появятся фильмы и серии, сохранённые для офлайн-просмотра.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(downloads, key = { "download-" + it.mediaKey }) { download ->
                DownloadedTitleRow(
                    title = download.title,
                    onDelete = { onDeleteTitle(download.mediaRef, download.title) },
                )
            }
        }
        item {
            SettingsSwitch(
                title = "Только по Wi‑Fi",
                subtitle = "Не начинать новые загрузки через мобильную сеть",
                checked = preferences.wifiOnlyDownloads,
                onCheckedChange = onWifiOnlyChanged,
                testTag = "settings.downloads.wifiOnly",
            )
        }
    }
}

@Composable
private fun DownloadedTitleRow(
    title: String,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Download,
                contentDescription = null,
                tint = MoviaBrandAmber,
                modifier = Modifier.padding(end = 14.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    "Офлайн-файл Movia",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoviaTapIconButton(
                icon = Icons.Outlined.DeleteOutline,
                contentDescription = "Удалить " + title,
                onClick = onDelete,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun downloadCountLabel(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "загрузок"
        mod10 == 1 -> "загрузка"
        mod10 in 2..4 -> "загрузки"
        else -> "загрузок"
    }
}

@Composable
fun HelpSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    var expandedQuestion by rememberSaveable { mutableStateOf<String?>(null) }
    val questions = listOf(
        "Как продолжить просмотр?" to "На Главной нажмите «Продолжить». Плеер откроется с сохранённой позиции.",
        "Где найти скачанные фильмы и серии?" to "Откройте «Настройки и профиль» → «Скачанное и память». Там можно удалить один файл или все загрузки.",
        "Как изменить язык озвучки и субтитры?" to "Эти параметры находятся в разделе «Воспроизведение и звук» на главном экране настроек.",
        "Почему некоторые релизы недоступны?" to "Доступность зависит от наличия легального источника и соединения с сетью.",
    )

    SettingsPage(title = "Справка и FAQ", onBack = onBack, modifier = modifier) {
        item {
            Text(
                "Популярные вопросы",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        questions.forEach { (question, answer) ->
            item(key = question) {
                FaqItem(
                    question = question,
                    answer = answer,
                    expanded = expandedQuestion == question,
                    onClick = {
                        expandedQuestion = if (expandedQuestion == question) null else question
                    },
                )
            }
        }
        item {
            Text(
                "Настройки можно изменить в любой момент — изменения сохраняются автоматически.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FaqItem(
    question: String,
    answer: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    question,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                    contentDescription = if (expanded) "Свернуть" else "Раскрыть",
                    tint = MoviaBrandAmber,
                )
            }
            if (expanded) {
                Text(
                    answer,
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsInfoCard(
    title: String,
    body: String,
    action: @Composable () -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MoviaBorderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            action()
        }
    }
}

@Composable
private fun HelpItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun manufacturerName(): String = Build.MANUFACTURER.replaceFirstChar { char ->
    if (char.isLowerCase()) char.titlecase() else char.toString()
}
