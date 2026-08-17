package app.viora.android.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val audioOptions = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val qualityOptions = listOf("Auto", "1080p", "720p", "480p")
private val episodes = (1..8).map { "E${it.toString().padStart(2, '0')} · Эпизод $it" }

@Composable
fun DetailsScreen(
    title: String,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    favorite: Boolean = false,
    watchLater: Boolean = false,
    downloaded: Boolean = false,
    selectedAudio: String = "Auto",
    selectedQuality: String = "Auto",
    onFavoriteChange: (Boolean) -> Unit = {},
    onWatchLaterChange: (Boolean) -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onAudioSelected: (String) -> Unit = {},
    onQualitySelected: (String) -> Unit = {},
    audioIsOverride: Boolean = false,
    qualityIsOverride: Boolean = false,
    onResetAudio: () -> Unit = {},
    onResetQuality: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isSeries = title == "Нулевая орбита" || title == "Граница миров"

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Movie,
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        .align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopStart)
                        .background(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(999.dp),
                        ),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (isSeries) "2026 · 16+ · 1 сезон · ★ 8.3" else "2026 · 16+ · 1 ч 56 мин · ★ 7.8",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("Фантастика", "Драма", "Триллер")) { genre ->
                        AssistChip(onClick = {}, label = { Text(genre) })
                    }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = { onPlay(title) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSeries) "Продолжить S01E04" else "Смотреть")
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { onFavoriteChange(!favorite) },
                            label = { Text(if (favorite) "В избранном" else "В избранное") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = null,
                                )
                            },
                        )
                        AssistChip(
                            onClick = { onWatchLaterChange(!watchLater) },
                            label = { Text(if (watchLater) "В списке" else "Посмотреть позже") },
                        )
                    }
                    AssistChip(
                        onClick = onDownloadClick,
                        label = { Text(if (downloaded) "Скачано" else "Скачать ~65 МБ") },
                    )
                }
            }
        }

        item {
            SelectorSection(
                title = "Озвучка",
                options = audioOptions,
                selected = selectedAudio,
                onSelect = onAudioSelected,
                isOverride = audioIsOverride,
                onReset = onResetAudio,
            )
        }

        item {
            SelectorSection(
                title = "Качество",
                options = qualityOptions,
                selected = selectedQuality,
                onSelect = onQualitySelected,
                isOverride = qualityIsOverride,
                onReset = onResetQuality,
            )
        }

        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Описание",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "История о людях, которым приходится сделать выбор на границе знакомого мира. Демонстрационный контент используется для проверки интерфейса Viora.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (isSeries) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Сезон 1",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    episodes.forEachIndexed { index, episode ->
                        EpisodeRow(
                            title = episode,
                            subtitle = if (index < 3) "Просмотрено" else if (index == 3) "32% · осталось 18 мин" else "46 мин",
                            onClick = { onPlay("$title · $episode") },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun SelectorSection(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    isOverride: Boolean,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(options) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(option) },
                )
            }
        }
        if (isOverride) {
            TextButton(onClick = onReset) {
                Text("Использовать настройку профиля")
            }
        }
        if (isOverride) {
            TextButton(onClick = onReset) {
                Text("Использовать настройку профиля")
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 96.dp, height = 60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
