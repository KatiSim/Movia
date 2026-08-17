package app.viora.android.ui.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.domain.model.ContentType
import app.viora.android.domain.model.MediaContent
import app.viora.android.domain.model.Person
import app.viora.android.ui.components.MediaCard
import app.viora.android.ui.components.SectionHeader
import java.util.Locale

private val popularQueries = listOf("2026", "Фантастика", "Комедия", "Драма")

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    recentQueries: List<String> = emptyList(),
    onSearchCommitted: (String) -> Unit = {},
    onClearRecent: () -> Unit = {},
    onOpenDetails: (String) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var voiceUnavailable by rememberSaveable { mutableStateOf(false) }
    val results = DemoCatalogRepository.search(query)
    val people = DemoCatalogRepository.searchPeople(query)

    fun commitSearch(value: String) {
        val normalized = value.trim()
        if (normalized.isNotEmpty()) onSearchCommitted(normalized)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val recognized = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!recognized.isNullOrEmpty()) {
                query = recognized
                commitSearch(recognized)
                voiceUnavailable = false
            }
        }
    }

    fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Что найти в Viora?")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            voiceLauncher.launch(intent)
            voiceUnavailable = false
        } catch (_: ActivityNotFoundException) {
            voiceUnavailable = true
        }
    }

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
                "Поиск",
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
                label = { Text("Фильм, сериал, актёр, режиссёр…") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Close, contentDescription = "Очистить поиск")
                            }
                        }
                        IconButton(onClick = ::startVoiceSearch) {
                            Icon(Icons.Outlined.Mic, contentDescription = "Голосовой поиск")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { commitSearch(query) }),
            )
        }

        if (voiceUnavailable) {
            item {
                Text(
                    "На устройстве не найден системный сервис распознавания речи.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (query.isBlank()) {
            if (recentQueries.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            SectionHeader(title = "Недавние запросы", modifier = Modifier.weight(1f))
                            TextButton(onClick = onClearRecent) { Text("Очистить") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(recentQueries) { recent ->
                                AssistChip(
                                    onClick = {
                                        query = recent
                                        commitSearch(recent)
                                    },
                                    label = { Text(recent) },
                                )
                            }
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
                                onClick = {
                                    query = popular
                                    commitSearch(popular)
                                },
                                label = { Text(popular) },
                            )
                        }
                    }
                }
            }
        } else {
            val total = results.size + people.size
            item {
                Text(
                    text = if (total == 0) "Ничего не найдено" else "Найдено: $total",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }

            if (total == 0) {
                item {
                    Text(
                        "Проверьте запрос или попробуйте название, человека, жанр, страну либо год.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ContentType.entries.forEach { type ->
                    val typed = results.filter { it.type == type }
                    if (typed.isNotEmpty()) {
                        item {
                            MediaResultSection(
                                title = type.label,
                                items = typed,
                                onOpenDetails = {
                                    commitSearch(query)
                                    onOpenDetails(it)
                                },
                            )
                        }
                    }
                }
                if (people.isNotEmpty()) {
                    item {
                        PeopleResultSection(
                            people = people,
                            onPersonClick = { person ->
                                query = person.name
                                commitSearch(person.name)
                            },
                            onKnownForClick = { title ->
                                commitSearch(query)
                                onOpenDetails(title)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaResultSection(
    title: String,
    items: List<MediaContent>,
    onOpenDetails: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(title = title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { item ->
                MediaCard(
                    title = item.title,
                    meta = buildString {
                        append(item.year)
                        append(" · ★ ")
                        append(item.rating)
                        item.originalTitle?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    onClick = { onOpenDetails(item.title) },
                )
            }
        }
    }
}

@Composable
private fun PeopleResultSection(
    people: List<Person>,
    onPersonClick: (Person) -> Unit,
    onKnownForClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title = "Люди")
        people.forEach { person ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPersonClick(person) },
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.large,
            ) {
                ListItem(
                    headlineContent = { Text(person.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Связанный контент: ${person.knownFor.size}")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(person.knownFor) { title ->
                                    AssistChip(
                                        onClick = { onKnownForClick(title) },
                                        label = { Text(title) },
                                    )
                                }
                            }
                        }
                    },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}
