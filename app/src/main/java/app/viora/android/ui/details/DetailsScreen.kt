package app.viora.android.ui.details

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.domain.model.ContentType

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
    downloadLabel: String = "Скачать ~65 МБ",
    downloadActionEnabled: Boolean = true,
    selectedAudio: String = "Auto",
    selectedQuality: String = "Auto",
    onFavoriteChange: (Boolean) -> Unit,
    onWatchLaterChange: (Boolean) -> Unit,
    onDownloadClick: () -> Unit,
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    audioIsOverride: Boolean = false,
    qualityIsOverride: Boolean = false,
    onResetAudio: () -> Unit,
    onResetQuality: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = remember(title) { DemoCatalogRepository.findByTitle(title) }
    val isSeries = content?.type == ContentType.SERIES
    val isTv = content?.type == ContentType.TV
    val meta = when {
        content == null -> "Демонстрационный контент"
        isTv -> "${content.year} · Прямой эфир · ★ ${content.rating} · ${content.quality}"
        isSeries -> "${content.year} · 16+ · 1 сезон · ★ ${content.rating} · ${content.quality}"
        else -> "${content.year} · 16+ · ${formatDuration(content.durationMinutes)} · ★ ${content.rating} · ${content.quality}"
    }
    val genres = content?.genres?.sorted().orEmpty()
    BackHandler(onBack = onBack)

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
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(genres) { genre ->
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = genre,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
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
                    onClick = { onPlay(if (isSeries) "$title · E04 · Эпизод 4" else title) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (isSeries) "Продолжить S01E04" else if (isTv) "Смотреть эфир" else "Смотреть")
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
                        enabled = downloadActionEnabled,
                        label = { Text(if (downloaded) "Скачано" else downloadLabel) },
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
                    text = if (content == null) {
                        "Демонстрационный контент используется для проверки интерфейса Viora."
                    } else {
                        "${content.type.label}: ${content.country}, ${content.year}. Жанры: ${content.genres.sorted().joinToString()}. В этой сборке медиаданные демонстрационные и отделены от playback-провайдера."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (content != null && (content.originalTitle != null || content.director != null || content.cast.isNotEmpty())) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Сведения",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    content.originalTitle?.let { original ->
                        Text(
                            text = "Оригинальное название: $original",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    content.director?.let { director ->
                        Text(
                            text = "Режиссёр: $director",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (content.cast.isNotEmpty()) {
                        Text(
                            text = "В ролях: ${content.cast.joinToString()}",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
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
    }
}

private fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "—"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours == 0) "$rest мин" else "$hours ч ${rest.toString().padStart(2, '0')} мин"
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
