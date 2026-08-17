package app.viora.android.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.viora.android.data.preferences.PlaybackProgress
import app.viora.android.ui.components.MediaCard
import app.viora.android.ui.components.SectionHeader

data class HomeMediaItem(val title: String, val meta: String)

private val recommendedItems = listOf(
    HomeMediaItem("Граница миров", "2026 · ★ 8.1"),
    HomeMediaItem("Тихий сигнал", "2025 · ★ 7.9"),
    HomeMediaItem("Последний рейс", "2026 · ★ 7.7"),
    HomeMediaItem("Северный ветер", "2024 · ★ 8.0"),
)

private val newItems = listOf(
    HomeMediaItem("Касание света", "2026 · 1 ч 48 мин"),
    HomeMediaItem("Нулевая орбита", "2026 · 2 ч 06 мин"),
    HomeMediaItem("Точка возврата", "2026 · 1 ч 56 мин"),
    HomeMediaItem("Город после дождя", "2026 · 1 ч 42 мин"),
)

@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress = PlaybackProgress(),
    onOpenDetails: (String) -> Unit = {},
    onContinue: (String) -> Unit = {},
) {
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Viora", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Смотрите дальше с того места, где остановились", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Продолжить просмотр")
                ContinueWatchingCard(progress = progress, onOpenDetails = onOpenDetails, onContinue = onContinue)
            }
        }

        item { MediaSection("Для вас", recommendedItems, onOpenDetails) }
        item { MediaSection("Новинки", newItems, onOpenDetails) }
    }
}

@Composable
private fun ContinueWatchingCard(
    progress: PlaybackProgress,
    onOpenDetails: (String) -> Unit,
    onContinue: (String) -> Unit,
) {
    val hasRealProgress = progress.title.isNotBlank() && progress.positionMs > 0L
    val title = if (hasRealProgress) progress.title else "Нулевая орбита"
    val fraction = if (hasRealProgress) progress.fraction else 0.62f
    val subtitle = if (hasRealProgress && progress.durationMs > 0L) {
        val remainingMinutes = ((progress.durationMs - progress.positionMs).coerceAtLeast(0L) / 60_000L)
        "Продолжить · осталось ≈ $remainingMinutes мин"
    } else {
        "S01E04 · осталось 18 мин"
    }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.width(112.dp).height(72.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(999.dp)),
        )

        Button(
            onClick = { if (hasRealProgress) onContinue(title) else onOpenDetails(title) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Продолжить")
        }
    }
}

@Composable
private fun MediaSection(
    title: String,
    items: List<HomeMediaItem>,
    onOpenDetails: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(items) { item ->
                MediaCard(title = item.title, meta = item.meta, onClick = { onOpenDetails(item.title) })
            }
        }
    }
}
