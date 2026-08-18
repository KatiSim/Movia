package app.viora.android.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viora.android.ui.catalog.CatalogLaunchPreset
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.data.catalog.RecommendationEngine
import app.viora.android.domain.model.MediaContent
import app.viora.android.domain.model.PlaybackProgress
import app.viora.android.ui.components.MediaCard
import app.viora.android.ui.components.MediaMetadataText
import app.viora.android.ui.components.SectionHeader
import app.viora.android.ui.theme.VioraBrandAmber
import app.viora.android.ui.theme.VioraOnBrandAmber

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress = PlaybackProgress(),
    history: List<String> = emptyList(),
    onOpenDetails: (String) -> Unit,
    onContinue: (String) -> Unit,
    onOpenCatalog: (CatalogLaunchPreset) -> Unit,
    onOpenProfile: () -> Unit,
) {
    val recommendation = RecommendationEngine.recommend(history)
    val allContent = DemoCatalogRepository.all()
    val newItems = allContent.filter { it.isNew }.sortedByDescending { it.popularity }.take(6)
    val popularItems = allContent.sortedByDescending { it.popularity }.take(6)
    val sciFiItems = allContent.filter { "Фантастика" in it.genres }.sortedByDescending { it.rating }.take(6)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 16.dp,
            top = contentPadding.calculateTopPadding() + 20.dp,
            end = 16.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Viora", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Смотрите дальше с того места, где остановились", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onOpenProfile, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Person, contentDescription = "Профиль")
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Продолжить просмотр")
                ContinueWatchingCard(progress = progress, onContinue = onContinue)
            }
        }
        if (recommendation.items.isNotEmpty()) {
            item {
                MediaSection(
                    title = "Похожее для вас",
                    items = recommendation.items,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                )
            }
        }
        if (newItems.isNotEmpty()) {
            item {
                NewMediaSection(
                    items = newItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.NEW) },
                )
            }
        }
        if (popularItems.isNotEmpty()) {
            item {
                MediaSection(
                    title = "Популярное",
                    items = popularItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                )
            }
        }
        if (sciFiItems.isNotEmpty()) {
            item {
                MediaSection(
                    title = "Фантастика для вас",
                    items = sciFiItems,
                    onOpenDetails = onOpenDetails,
                    onViewAll = { onOpenCatalog(CatalogLaunchPreset.ALL) },
                )
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: PlaybackProgress,
    onContinue: (String) -> Unit,
) {
    val hasRealProgress = progress.title.isNotBlank() && progress.positionMs > 0L
    val title = if (hasRealProgress) progress.title else "Нулевая орбита · S01E04 · Эпизод 4"
    val fraction = if (hasRealProgress) progress.fraction else 0.62f
    val subtitle = if (hasRealProgress && progress.durationMs > 0L) {
        val remainingMinutes = ((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000L)
        "Осталось ≈ $remainingMinutes мин"
    } else {
        "S01E04 · осталось 18 мин"
    }

    Surface(
        onClick = { onContinue(title) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Продолжить просмотр. $title. $subtitle"
            },
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(128.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    color = VioraBrandAmber,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = title.substringBefore(" · Эпизод"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun MediaSection(
    title: String,
    items: List<MediaContent>,
    onOpenDetails: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = title, onClick = onViewAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 24.dp)) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    title = item.title,
                    meta = "${item.year} · ★ ${item.rating}",
                    onClick = { onOpenDetails(item.title) },
                )
            }
        }
    }
}

@Composable
private fun NewMediaSection(
    items: List<MediaContent>,
    onOpenDetails: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = "Новинки", onClick = onViewAll)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 24.dp)) {
            items(items, key = { "new-${it.id}" }) { item ->
                WideNewMediaCard(item = item, onClick = { onOpenDetails(item.title) })
            }
        }
    }
}

@Composable
private fun WideNewMediaCard(
    item: MediaContent,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(232.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "${item.title}. ${item.year}. Новинка"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .height(131.dp),
        ) {
            Box(
                modifier = Modifier.background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surface,
                        ),
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Movie,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = VioraBrandAmber,
                    contentColor = VioraOnBrandAmber,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                ) {
                    Text(
                        text = "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                }
            }
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        MediaMetadataText(
            text = "${item.year} · ★ ${item.rating}",
        )
    }
}
