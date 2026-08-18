package app.viora.android.ui.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.R
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.PlaybackProgress
import app.viora.android.ui.theme.VioraBrandAmber
import app.viora.android.ui.theme.VioraOnBrandAmber
import kotlin.math.ceil

private val audioOptions = listOf("Auto", "LostFilm", "HDRezka", "Original")
private val qualityOptions = listOf("Auto", "1080p", "720p", "480p")

private data class EpisodeUiState(
    val season: Int,
    val number: Int,
    val durationMinutes: Int = 46,
    val progress: PlaybackProgress = PlaybackProgress(),
) {
    val code: String = "S${season.toString().padStart(2, '0')}E${number.toString().padStart(2, '0')}"
    val playbackTitle: String get() = "$code · Эпизод $number"
    val progressFraction: Float get() = progress.fraction
    val remainingMinutes: Int?
        get() = if (progress.durationMs > 0L) {
            ceil((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000.0).toInt()
        } else null
}

private fun seasonCountText(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    val word = when {
        mod100 in 11..14 -> "сезонов"
        mod10 == 1 -> "сезон"
        mod10 in 2..4 -> "сезона"
        else -> "сезонов"
    }
    return "$count $word"
}

private fun episodeTitle(baseTitle: String, season: Int, episode: Int): String =
    "$baseTitle · S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · Эпизод $episode"

private fun seasonFromTitle(title: String): Int? =
    Regex(" · S(\\d{2})E\\d{2}").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

private fun episodeFromTitle(title: String): Int? =
    Regex(" · S\\d{2}E(\\d{2})").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsScreen(
    title: String,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier,
    inMyList: Boolean = false,
    onMyListChange: (Boolean) -> Unit,
    downloads: Set<String> = emptySet(),
    onDownloadTitle: (String) -> Unit,
    selectedAudio: String = "Auto",
    selectedQuality: String = "Auto",
    subtitlesEnabled: Boolean = false,
    progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    latestProgress: PlaybackProgress = PlaybackProgress(),
    onAudioSelected: (String) -> Unit,
    onQualitySelected: (String) -> Unit,
    audioIsOverride: Boolean = false,
    qualityIsOverride: Boolean = false,
    onResetAudio: () -> Unit,
    onResetQuality: () -> Unit,
) {
    val content = remember(title) { DemoCatalogRepository.findByTitle(title) }
    val heroHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.38f).coerceIn(280.dp, 420.dp)
    val isSeries = content?.type == ContentType.SERIES
    val isTv = content?.type == ContentType.TV
    val seasonEpisodeCounts = if (isSeries) {
        content?.seasonEpisodeCounts?.takeIf { it.isNotEmpty() } ?: listOf(8)
    } else emptyList()
    val resume = latestProgress.takeIf { it.title == title || it.title.startsWith("$title · S") }
    val initialSeason = seasonFromTitle(resume?.title.orEmpty())?.coerceIn(1, seasonEpisodeCounts.size.coerceAtLeast(1)) ?: 1
    var selectedSeason by remember(title, initialSeason) { mutableIntStateOf(initialSeason) }
    var playbackSheetOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val appBarSolid by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 96 }
    }
    val appBarColor by animateColorAsState(
        targetValue = if (appBarSolid) Color(0xD9121212) else Color.Transparent,
        label = "detailsTopAppBar",
    )

    val meta = when {
        content == null -> "Демонстрационный контент"
        isTv -> "${content.year} · Прямой эфир · ★ ${content.rating} · ${content.quality}"
        isSeries -> "${content.year} · ${content.ageRating}+ · ${seasonCountText(seasonEpisodeCounts.size)} · ★ ${content.rating} · ${content.quality}"
        else -> "${content.year} · ${content.ageRating}+ · ${formatDuration(content.durationMinutes)} · ★ ${content.rating} · ${content.quality}"
    }
    val genres = content?.genres?.sorted().orEmpty()
    val resumeEpisode = episodeFromTitle(resume?.title.orEmpty())
    val resumeTitle = when {
        isSeries && resumeEpisode != null -> resume!!.title
        isSeries -> episodeTitle(title, 1, 1)
        else -> title
    }
    val ctaLabel = when {
        isTv -> "Смотреть эфир"
        isSeries && resumeEpisode != null && (resume?.positionMs ?: 0L) > 0L -> "Продолжить эпизод $resumeEpisode"
        isSeries -> "Смотреть эпизод 1"
        resume?.positionMs?.let { it > 0L } == true -> "Продолжить"
        else -> "Смотреть"
    }

    BackHandler(onBack = onBack)

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            item(key = "hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroHeight),
                ) {
                    Image(
                        painter = painterResource(R.drawable.viora_demo_backdrop),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to Color.Transparent,
                                        0.40f to Color.Transparent,
                                        1.0f to MaterialTheme.colorScheme.background,
                                    ),
                                ),
                            ),
                    )
                }
            }

            item(key = "metadata") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

            item(key = "actions") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = { onPlay(resumeTitle) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF5A623), contentColor = Color(0xFF241800)),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(ctaLabel)
                    }
                    if (resume != null && resume.durationMs > 0L && resume.positionMs > 0L) {
                        val remaining = ceil((resume.durationMs - resume.positionMs).coerceAtLeast(0L) / 60_000.0).toInt()
                        Text(
                            text = "Осталось около $remaining мин",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        MyListButton(
                            selected = inMyList,
                            onClick = { onMyListChange(!inMyList) },
                            modifier = Modifier.weight(1f),
                        )
                        if (!isSeries) {
                            DownloadButton(
                                downloaded = title in downloads,
                                onClick = { onDownloadTitle(title) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item(key = "playback-parameters") {
                PlaybackPreferencesRow(
                    audio = selectedAudio,
                    quality = selectedQuality,
                    subtitlesEnabled = subtitlesEnabled,
                    onClick = { playbackSheetOpen = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            item(key = "description") {
                DetailsTextSection("Описание", content?.synopsis ?: "Описание пока недоступно.")
            }

            if (content != null) {
                item(key = "info") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SectionTitle("Сведения")
                        InfoField("Страна", content.country)
                        content.originalTitle?.let { InfoField("Оригинальное название", it) }
                        content.director?.let { InfoField("Режиссёр", it) }
                        if (content.cast.isNotEmpty()) InfoField("В ролях", content.cast.joinToString(" · "))
                    }
                }
            }

            if (isSeries) {
                item(key = "season-selector") {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionTitle("Эпизоды", Modifier.padding(horizontal = 16.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            items((1..seasonEpisodeCounts.size).toList()) { season ->
                                FilterChip(
                                    selected = selectedSeason == season,
                                    onClick = { selectedSeason = season },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text("Сезон $season") },
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

                val episodeCount = seasonEpisodeCounts.getOrElse(selectedSeason - 1) { 0 }
                items(
                    count = episodeCount,
                    key = { index -> "S$selectedSeason-E${index + 1}" },
                ) { index ->
                    val number = index + 1
                    val fullTitle = episodeTitle(title, selectedSeason, number)
                    val episode = EpisodeUiState(
                        season = selectedSeason,
                        number = number,
                        progress = progressByTitle[fullTitle] ?: PlaybackProgress(title = fullTitle),
                    )
                    EpisodeRow(
                        episode = episode,
                        downloaded = fullTitle in downloads,
                        onPlay = { onPlay(fullTitle) },
                        onDownload = { onDownloadTitle(fullTitle) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        TopAppBar(
            title = {
                if (appBarSolid) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Назад")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = appBarColor,
                navigationIconContentColor = Color.White,
                titleContentColor = Color.White,
            ),
            windowInsets = WindowInsets.statusBars,
        )
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
private fun MyListButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) VioraBrandAmber else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) VioraOnBrandAmber else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(if (selected) Icons.Filled.Check else Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Мой список", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DownloadButton(downloaded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (downloaded) "Скачано" else "Скачать", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlaybackPreferencesRow(
    audio: String,
    quality: String,
    subtitlesEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Tune, contentDescription = null, tint = VioraBrandAmber, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Параметры воспроизведения", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Озвучка: ${displayAudio(audio)} • Субтитры: ${if (subtitlesEnabled) "Вкл" else "Выкл"} • $quality",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun displayAudio(value: String): String = when (value) {
    "Auto" -> "Авто"
    "Original" -> "Оригинал"
    else -> value
}

@Composable
private fun EpisodeRow(
    episode: EpisodeUiState,
    downloaded: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = episode.progressFraction
    val status = when {
        progress >= 0.98f -> "✓ Просмотрено · ${episode.durationMinutes} мин"
        progress > 0f -> "${episode.remainingMinutes?.let { "Осталось $it мин · " }.orEmpty()}${(progress * 100).toInt()}%"
        else -> "${episode.durationMinutes} мин"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Эпизод ${episode.number}. $status"
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 112.dp, height = 68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PlayCircleOutline, contentDescription = "Воспроизвести эпизод ${episode.number}", tint = VioraBrandAmber)
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        color = VioraBrandAmber,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).clickable(onClick = onPlay)) {
                Text("E${episode.number.toString().padStart(2, '0')} · Эпизод ${episode.number}", fontWeight = FontWeight.SemiBold)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
                Icon(
                    if (downloaded) Icons.Outlined.CheckCircle else Icons.Outlined.Download,
                    contentDescription = if (downloaded) "Удалить скачанный эпизод" else "Скачать эпизод",
                    tint = if (downloaded) VioraBrandAmber else MaterialTheme.colorScheme.onSurface,
                )
            }
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
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Text("Параметры воспроизведения", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            PlaybackChoiceSection("Озвучка", audioOptions, selectedAudio, onAudioSelected)
            PlaybackChoiceSection("Качество", qualityOptions, selectedQuality, onQualitySelected)
            if (audioIsOverride || qualityIsOverride) {
                TextButton(onClick = onResetDefaults, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Использовать настройки по умолчанию")
                }
            }
        }
    }
}

@Composable
private fun PlaybackChoiceSection(title: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
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
private fun DetailsTextSection(title: String, body: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier = modifier, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

private fun formatDuration(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return if (hours > 0) "$hours ч $rest мин" else "$minutes мин"
}
