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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.domain.model.ContentType

private val VioraBrandAmber = Color(0xFFF4B343)
private val VioraOnBrandAmber = Color(0xFF241800)

private val audioOptions = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val qualityOptions = listOf("Auto", "1080p", "720p", "480p")
private val episodes = (1..8).map { "E${it.toString().padStart(2, '0')} · Эпизод $it" }

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
        isSeries -> "${content.year} · 16+ · 1 сезон · ★ ${content.rating} · ${content.quality}"
        else -> "${content.year} · 16+ · ${formatDuration(content.durationMinutes)} · ★ ${content.rating} · ${content.quality}"
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
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier
                            .size(64.dp)
                            .align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .padding(12.dp)
                            .size(48.dp)
                            .align(Alignment.TopStart)
                            .background(
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                shape = RoundedCornerShape(999.dp),
                            ),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
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
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                            ) {
                                Text(
                                    text = genre,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
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
                    verticalArrangement = Arrangement.spacedBy(12.dp),
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
                        Text(if (isSeries) "Продолжить S01E04" else if (isTv) "Смотреть эфир" else "Смотреть")
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DetailAction(
                            label = if (favorite) "В избранном" else "Избранное",
                            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            active = favorite,
                            onClick = { onFavoriteChange(!favorite) },
                            contentDescription = if (favorite) "Удалить из избранного" else "Добавить в избранное",
                        )
                        DetailAction(
                            label = if (watchLater) "Добавлено в «Позже»" else "Посмотреть позже",
                            icon = Icons.Outlined.WatchLater,
                            active = watchLater,
                            onClick = { onWatchLaterChange(!watchLater) },
                            contentDescription = if (watchLater) "Удалить из списка Посмотреть позже" else "Добавить в список Посмотреть позже",
                        )
                        DetailAction(
                            label = if (downloaded) "Скачано" else downloadLabel,
                            icon = if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                            active = downloaded,
                            enabled = downloadActionEnabled,
                            onClick = onDownloadClick,
                            contentDescription = if (downloaded) "Удалить скачанное" else downloadLabel,
                        )
                    }
                }
            }

            item {
                PlaybackPreferencesRow(
                    audio = selectedAudio,
                    quality = selectedQuality,
                    hasOverride = audioIsOverride || qualityIsOverride,
                    onClick = { playbackSheetOpen = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item {
                DetailsTextSection(
                    title = "Описание",
                    body = if (content == null) {
                        "Демонстрационный контент используется для проверки интерфейса Viora."
                    } else {
                        "${content.type.label}: ${content.country}, ${content.year}. Жанры: ${content.genres.sorted().joinToString()}. В этой сборке медиаданные демонстрационные и отделены от playback-провайдера."
                    },
                )
            }

            if (content != null && (content.originalTitle != null || content.director != null || content.cast.isNotEmpty())) {
                item {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SectionTitle("Сведения")
                        content.originalTitle?.let { original ->
                            Text(
                                text = "Оригинальное название: $original",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        content.director?.let { director ->
                            Text(
                                text = "Режиссёр: $director",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        if (content.cast.isNotEmpty()) {
                            Text(
                                text = "В ролях: ${content.cast.joinToString()}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
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
                        SectionTitle("Сезон 1")
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
private fun DetailAction(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .semantics { this.contentDescription = contentDescription },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (active) VioraBrandAmber else Color.Transparent,
            contentColor = if (active) VioraOnBrandAmber else MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaybackPreferencesRow(
    audio: String,
    quality: String,
    hasOverride: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = VioraBrandAmber,
                contentColor = VioraOnBrandAmber,
            ) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Параметры воспроизведения",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    buildString {
                        append(audio)
                        append(" · ")
                        append(quality)
                        if (hasOverride) append(" · для этого фильма")
                    },
                    style = MaterialTheme.typography.bodyMedium,
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
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
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
