package app.viora.android.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.viora.android.ui.components.MediaCard
import app.viora.android.ui.components.SectionHeader

private val recentQueries = listOf("Космос", "Триллер", "Испания")
private val popularQueries = listOf("Новинки 2026", "Фантастика", "Комедии", "Мини-сериалы")

private val demoResults = listOf(
    Pair("Нулевая орбита", "2026 · ★ 8.3"),
    Pair("Граница миров", "2026 · ★ 8.1"),
    Pair("Точка возврата", "2026 · ★ 7.8"),
    Pair("Тихий сигнал", "2025 · ★ 7.9"),
)

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenDetails: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }

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
            Text(
                text = "Поиск",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Фильм, сериал, актёр…") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Очистить поиск",
                                )
                            }
                        }
                        IconButton(onClick = {}) {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                contentDescription = "Голосовой поиск",
                            )
                        }
                    }
                },
            )
        }

        if (query.isBlank()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(title = "Недавние запросы")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recentQueries) { recent ->
                            AssistChip(
                                onClick = { query = recent },
                                label = { Text(recent) },
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionHeader(title = "Популярное в поиске")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(popularQueries) { popular ->
                            AssistChip(
                                onClick = { query = popular },
                                label = { Text(popular) },
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Text(
                    text = "Результаты по запросу «$query»",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(demoResults) { result ->
                        MediaCard(
                            title = result.first,
                            meta = result.second,
                            onClick = { onOpenDetails(result.first) },
                        )
                    }
                }
            }
        }
    }
}
