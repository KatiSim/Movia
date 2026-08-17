package app.viora.android.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.domain.model.ContentType

private val VioraBrandAmber = Color(0xFFF4B343)
private val VioraOnBrandAmber = Color(0xFF241800)

private val audioOptions = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val qualityOptions = listOf("Auto", "1080p", "720p", "480p")

private data class EpisodeUiState(
    val number: Int,
    val durationMinutes: Int = 46,
    val progress: Float = 0f,
    val remainingMinutes: Int? = null,
) {
    val code: String = "E${number.toString().padStart(2, '0')}"
    val title: String = "$code · Эпизод $number"
}

private val episodes = (1..8).map { number ->
    when (number) {
        1, 2, 3 -> EpisodeUiState(number = number, progress = 1f)
        4 -> EpisodeUiState(number = number, progress = 0.32f, remainingMinutes = 18)
        else -> EpisodeUiState(number = number)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
        isSeries -> "${content.year} · ${content.ageRating}+ · 1 сезон · ★ ${content.rating} · ${content.quality}"
        else -> "${content.year} · ${content.ageRating}+ · ${formatDuration(content.durationMinutes)} · ★ ${content.rating} · ${content.quality}"
    }
    val genres = content?.genres?.sorted().orEmpty()
    var playbackSheetOpen by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(184.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f),
                                ),
                            ),
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f),
                    )
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                                ),
                            )
                            .padding(start = 8.dp, top = 8.dp)
                            .size(48.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = "Назад",
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
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
                                shape = RoundedCornerShape(9.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                            ) {
                                Text(
                                    text = genre,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VioraBrandAmber,
                            contentColor = VioraOnBrandAmber,
                        ),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when {
                                isSeries -> "Продолжить эпизод 4"
                                isTv -> "Смотреть эфир"
                                else -> "Смотреть"
                            },
                        )
                    }
                    if (isSeries) {
                        Text(
                            text = "18 мин осталось",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DetailQuickAction(
                            label = if (favorite) "В избранном" else "Избранное",
                            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            active = favorite,
                            onClick = { onFavoriteChange(!favorite) },
                            contentDescription = if (favorite) "Удалить из избранного" else "Добавить в избранное",
                            modifier = Modifier.weight(1f),
                        )
                        DetailQuickAction(
                            label = if (watchLater) "Добавлено" else "Позже",
                            icon = Icons.Outlined.WatchLater,
                            active = watchLater,
                            onClick = { onWatchLaterChange(!watchLater) },
                            contentDescription = if (watchLater) "Удалить из списка Посмотреть позже" else "Добавить в список Посмотреть позже",
                            modifier = Modifier.weight(1f),
                        )
                        DetailQuickAction(
                            label = if (downloaded) "Скачано" else downloadPrimaryLabel(downloadLabel),
                            secondary = if (downloaded) null else downloadSecondaryLabel(downloadLabel),
                            icon = if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                            active = downloaded,
                            enabled = downloadActionEnabled,
                            onClick = onDownloadClick,
                            contentDescription = if (downloaded) "Удалить скачанное" else downloadLabel,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            item {
                PlaybackPreferencesRow(
                    audio = selectedAudio,
                    quality = selectedQuality,
                    onClick = { playbackSheetOpen = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                DetailsTextSection(
                    title = "Описание",
                    body = content?.synopsis ?: "Описание пока недоступно.",
                )
            }

            if (content != null) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle("Сведения")
                        InfoField("Страна", content.country)
                        content.originalTitle?.let { InfoField("Оригинальное название", it) }
                        content.director?.let { InfoField("Режиссёр", it) }
                        if (content.cast.isNotEmpty()) {
                            InfoField("В ролях", content.cast.joinToString(" · "))
                        }
                    }
                }
            }

            if (isSeries) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SectionTitle("Сезон 1")
                    }
                }
                items(episodes, key = { it.number }) { episode ->
                    EpisodeRow(
                        episode = episode,
                        onClick = { onPlay("$title · ${episode.title}") },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item { Spacer(Modifier.height(28.dp)) }
        }
    }

    if (playbackSheetOpen) {
        PlaybackPreferencesSheet(
            selectedAudio = selectedAudio,
            selectedQuality = selectedQuality,
            audioIsOverride = audioIsOverride,
            qualityIsOverride = qualityIsOverride,
            onAudioSelected = onAudioSelected,
            onQualitySelected = onQualitySelected,
            onResetDefaults = {
                onResetAudio()
                onResetQuality()
            },
            onDismiss = { playbackSheetOpen = false },
        )
    }
}

@Composable
private fun DetailQuickAction(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    enabled: Boolean = true,
) {
    val containerColor = when {
        active -> VioraBrandAmber
        enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    }
    val contentColor = when {
        active -> VioraOnBrandAmber
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            secondary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaybackPreferencesRow(
    audio: String,
    quality: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = VioraBrandAmber,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Параметры воспроизведения",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "$audio · $quality",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackPreferencesSheet(
    selectedAudio: String,
    selectedQuality: String,
    audioIsOverride: Boolean,
    qualityIsOverride: Boolean,
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text(
                "Параметры воспроизведения",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (audioIsOverride || qualityIsOverride) {
                Text(
                    "Индивидуальные настройки этого материала",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PlaybackChoiceSection(
                title = "Озвучка",
                options = audioOptions,
                selected = selectedAudio,
                onSelect = onAudioSelected,
            )
            PlaybackChoiceSection(
                title = "Качество",
                options = qualityOptions,
                selected = selectedQuality,
                onSelect = onQualitySelected,
            )
            if (audioIsOverride || qualityIsOverride) {
                TextButton(
                    onClick = onResetDefaults,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Использовать настройки по умолчанию")
                }
            } else {
                Text(
                    "Используются настройки профиля по умолчанию.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlaybackChoiceSection(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(options) { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = Modifier.heightIn(min = 48.dp),
                    label = { Text(option) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        selectedContainerColor = VioraBrandAmber,
                        selectedLabelColor = VioraOnBrandAmber,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DetailsTextSection(
    title: String,
    body: String,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle(title)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun EpisodeRow(
    episode: EpisodeUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = when {
        episode.progress >= 1f -> "✓ Просмотрено · ${episode.durationMinutes} мин"
        episode.progress > 0f -> "Осталось ${episode.remainingMinutes ?: 0} мин · ${(episode.progress * 100).toInt()}%"
        else -> "${episode.durationMinutes} мин"
    }
    val semanticsText = buildString {
        append("Эпизод ${episode.number}. ")
        when {
            episode.progress >= 1f -> append("Просмотрено. ")
            episode.progress > 0f -> append("Просмотрено ${(episode.progress * 100).toInt()} процентов, осталось ${episode.remainingMinutes ?: 0} минут. ")
            else -> append("Длительность ${episode.durationMinutes} минут. ")
        }
        append("Воспроизвести.")
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .semantics { contentDescription = semanticsText },
        shape = RoundedCornerShape(14.dp),
        color = if (episode.progress in 0.001f..0.999f) {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 84.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 58.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = VioraBrandAmber,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center),
                )
                if (episode.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { episode.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .align(Alignment.BottomCenter),
                        color = VioraBrandAmber,
                        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    episode.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (episode.progress in 0.001f..0.999f) VioraBrandAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun downloadPrimaryLabel(downloadLabel: String): String = when {
    downloadLabel.startsWith("Скачать") -> "Скачать"
    else -> downloadLabel
}

private fun downloadSecondaryLabel(downloadLabel: String): String? = when {
    downloadLabel.startsWith("Скачать") -> downloadLabel.removePrefix("Скачать").trim().takeIf { it.isNotBlank() }
    else -> null
}

private fun formatDuration(minutes: Int): String {
    if (minutes <= 0) return "—"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours == 0) "$rest мин" else "$hours ч ${rest.toString().padStart(2, '0')} мин"
}
