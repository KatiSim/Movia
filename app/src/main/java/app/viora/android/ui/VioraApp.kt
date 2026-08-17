package app.viora.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import app.viora.android.data.download.DownloadScheduler
import app.viora.android.data.download.DownloadStatus
import app.viora.android.data.preferences.AppPreferences
import app.viora.android.data.preferences.PlaybackPreferences
import app.viora.android.data.preferences.PlaybackProgress
import app.viora.android.data.preferences.TitlePlaybackPreferences
import app.viora.android.data.preferences.VioraPreferencesRepository
import app.viora.android.ui.catalog.CatalogScreen
import app.viora.android.ui.details.DetailsScreen
import app.viora.android.ui.home.HomeScreen
import app.viora.android.ui.library.LibraryScreen
import app.viora.android.ui.player.PlayerScreen
import app.viora.android.ui.profile.ProfileScreen
import app.viora.android.ui.search.SearchScreen
import app.viora.android.ui.settings.AccessibilitySettingsScreen
import app.viora.android.ui.settings.AppearanceSettingsScreen
import app.viora.android.ui.settings.DevicesSettingsScreen
import app.viora.android.ui.settings.DownloadsSettingsScreen
import app.viora.android.ui.settings.HelpSettingsScreen
import app.viora.android.ui.settings.NotificationsSettingsScreen
import app.viora.android.ui.settings.PlaybackSettingsScreen
import app.viora.android.ui.theme.VioraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("Главная", Icons.Filled.Home, Icons.Outlined.Home),
    TopLevelDestination("Каталог", Icons.Filled.ViewModule, Icons.Outlined.ViewModule),
    TopLevelDestination("Поиск", Icons.Filled.Search, Icons.Outlined.Search),
    TopLevelDestination("Моё", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary),
    TopLevelDestination("Профиль", Icons.Filled.Person, Icons.Outlined.Person),
)

internal fun nextEpisodeTitle(current: String): String? {
    val match = Regex("^(.*) · E(\\d{2}) · Эпизод (\\d+)$").matchEntire(current) ?: return null
    val base = match.groupValues[1]
    val episode = match.groupValues[2].toIntOrNull() ?: return null
    val next = episode + 1
    if (next > 8) return null
    return "$base · E${next.toString().padStart(2, '0')} · Эпизод $next"
}

@Composable
fun VioraApp() {
    val context = LocalContext.current
    val preferencesRepository = remember(context) {
        VioraPreferencesRepository(context.applicationContext)
    }
    val appPreferences by preferencesRepository.appPreferences.collectAsState(initial = AppPreferences())

    VioraTheme(
        themeMode = appPreferences.themeMode,
        highContrast = appPreferences.highContrast,
    ) {
        VioraContent(
            context = context,
            preferencesRepository = preferencesRepository,
            appPreferences = appPreferences,
        )
    }
}

@Composable
private fun VioraContent(
    context: Context,
    preferencesRepository: VioraPreferencesRepository,
    appPreferences: AppPreferences,
) {
    val scope = rememberCoroutineScope()
    val playbackPreferences by preferencesRepository.playbackPreferences.collectAsState(initial = PlaybackPreferences())
    val favorites by preferencesRepository.favorites.collectAsState(initial = emptySet())
    val watchLater by preferencesRepository.watchLater.collectAsState(initial = emptySet())
    val downloads by preferencesRepository.downloads.collectAsState(initial = emptySet())
    val history by preferencesRepository.history.collectAsState(initial = emptyList())
    val recentSearches by preferencesRepository.recentSearches.collectAsState(initial = emptyList())
    val lastProgress by preferencesRepository.lastProgress.collectAsState(initial = PlaybackProgress())

    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var detailsTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var playTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsRoute by rememberSaveable { mutableStateOf<String?>(null) }

    if (playTitle != null) {
        val title = playTitle.orEmpty()
        val baseTitle = title.substringBefore(" · E")
        val titlePreferencesFlow = remember(baseTitle, preferencesRepository) {
            preferencesRepository.titlePlaybackPreferences(baseTitle)
        }
        val titlePreferences by titlePreferencesFlow.collectAsState(initial = TitlePlaybackPreferences())
        val localSource = (
            DownloadScheduler.localFile(context.applicationContext, title)
                ?: DownloadScheduler.localFile(context.applicationContext, baseTitle)
            )?.toURI()?.toString()
        val resolvedAudio = titlePreferences.audio ?: playbackPreferences.audio
        val resolvedQuality = titlePreferences.quality ?: playbackPreferences.quality

        PlayerScreen(
            title = title,
            onBack = { playTitle = null },
            startPositionMs = if (lastProgress.title == title) lastProgress.positionMs else 0L,
            sourceUri = localSource,
            preferredAudio = resolvedAudio,
            preferredQuality = resolvedQuality,
            subtitlesEnabled = playbackPreferences.subtitlesEnabled,
            autoNextEnabled = playbackPreferences.autoNextEnabled,
            onSubtitlesChanged = { enabled ->
                scope.launch { preferencesRepository.setSubtitlesEnabled(enabled) }
            },
            onNextEpisode = {
                nextEpisodeTitle(title)?.let { playTitle = it }
            },
            onProgress = { positionMs, durationMs ->
                scope.launch { preferencesRepository.saveProgress(title, positionMs, durationMs) }
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    if (detailsTitle != null) {
        val title = detailsTitle.orEmpty()
        val titlePreferencesFlow = remember(title, preferencesRepository) {
            preferencesRepository.titlePlaybackPreferences(title)
        }
        val titlePreferences by titlePreferencesFlow.collectAsState(initial = TitlePlaybackPreferences())
        val resolvedAudio = titlePreferences.audio ?: playbackPreferences.audio
        val resolvedQuality = titlePreferences.quality ?: playbackPreferences.quality
        var downloadStatus by remember(title) { mutableStateOf(DownloadStatus()) }
        LaunchedEffect(title) {
            while (true) {
                downloadStatus = DownloadScheduler.status(context.applicationContext, title)
                delay(1_000L)
            }
        }
        val downloadLabel = when {
            title in downloads -> "Скачано"
            downloadStatus.state == WorkInfo.State.RUNNING -> "Скачивается ${downloadStatus.progressPercent}%"
            downloadStatus.state == WorkInfo.State.ENQUEUED || downloadStatus.state == WorkInfo.State.BLOCKED -> "Ожидание"
            downloadStatus.state == WorkInfo.State.FAILED -> "Ошибка · Повторить"
            downloadStatus.state == WorkInfo.State.SUCCEEDED -> "Завершено"
            else -> "Скачать ~65 МБ"
        }
        val downloadActionEnabled = title in downloads || downloadStatus.state !in setOf(
            WorkInfo.State.RUNNING,
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
        )

        DetailsScreen(
            title = title,
            onBack = { detailsTitle = null },
            onPlay = { playTitle = it },
            favorite = title in favorites,
            watchLater = title in watchLater,
            downloaded = title in downloads,
            downloadLabel = downloadLabel,
            downloadActionEnabled = downloadActionEnabled,
            selectedAudio = resolvedAudio,
            selectedQuality = resolvedQuality,
            onFavoriteChange = { favorite ->
                scope.launch { preferencesRepository.setFavorite(title, favorite) }
            },
            onWatchLaterChange = { enabled ->
                scope.launch { preferencesRepository.setWatchLater(title, enabled) }
            },
            onDownloadClick = {
                if (title in downloads) {
                    if (DownloadScheduler.delete(context.applicationContext, title)) {
                        scope.launch { preferencesRepository.setDownloaded(title, false) }
                    }
                } else {
                    DownloadScheduler.enqueue(
                        context = context.applicationContext,
                        title = title,
                        wifiOnly = playbackPreferences.wifiOnlyDownloads,
                    )
                }
            },
            onAudioSelected = { audio ->
                scope.launch { preferencesRepository.setTitleAudio(title, audio) }
            },
            onQualitySelected = { quality ->
                scope.launch { preferencesRepository.setTitleQuality(title, quality) }
            },
            audioIsOverride = titlePreferences.audio != null,
            qualityIsOverride = titlePreferences.quality != null,
            onResetAudio = { scope.launch { preferencesRepository.setTitleAudio(title, null) } },
            onResetQuality = { scope.launch { preferencesRepository.setTitleQuality(title, null) } },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    settingsRoute?.let { route ->
        val closeSettings = { settingsRoute = null }
        when (route) {
            "playback" -> PlaybackSettingsScreen(
                preferences = playbackPreferences,
                onBack = closeSettings,
                onAudioSelected = { value -> scope.launch { preferencesRepository.setAudio(value) } },
                onQualitySelected = { value -> scope.launch { preferencesRepository.setQuality(value) } },
                onSubtitlesChanged = { value -> scope.launch { preferencesRepository.setSubtitlesEnabled(value) } },
                onAutoNextChanged = { value -> scope.launch { preferencesRepository.setAutoNextEnabled(value) } },
                modifier = Modifier.fillMaxSize(),
            )
            "downloads" -> DownloadsSettingsScreen(
                preferences = playbackPreferences,
                downloadedCount = downloads.size,
                onBack = closeSettings,
                onWifiOnlyChanged = { value -> scope.launch { preferencesRepository.setWifiOnlyDownloads(value) } },
                onDeleteAll = {
                    if (DownloadScheduler.deleteAll(context.applicationContext)) {
                        scope.launch { preferencesRepository.clearDownloaded() }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            "notifications" -> NotificationsSettingsScreen(
                preferences = appPreferences,
                onBack = closeSettings,
                onEnabledChanged = { value -> scope.launch { preferencesRepository.setNotificationsEnabled(value) } },
                modifier = Modifier.fillMaxSize(),
            )
            "appearance" -> AppearanceSettingsScreen(
                preferences = appPreferences,
                onBack = closeSettings,
                onThemeModeChanged = { value -> scope.launch { preferencesRepository.setThemeMode(value) } },
                modifier = Modifier.fillMaxSize(),
            )
            "accessibility" -> AccessibilitySettingsScreen(
                preferences = appPreferences,
                onBack = closeSettings,
                onHighContrastChanged = { value -> scope.launch { preferencesRepository.setHighContrast(value) } },
                modifier = Modifier.fillMaxSize(),
            )
            "devices" -> DevicesSettingsScreen(onBack = closeSettings, modifier = Modifier.fillMaxSize())
            "help" -> HelpSettingsScreen(onBack = closeSettings, modifier = Modifier.fillMaxSize())
            else -> settingsRoute = null
        }
        return
    }

    val openDetails: (String) -> Unit = { title ->
        detailsTitle = title
        scope.launch { preferencesRepository.addHistory(title) }
    }

    val screenContent: @Composable (PaddingValues) -> Unit = { innerPadding ->
        when (selectedIndex) {
            0 -> HomeScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                progress = lastProgress,
                history = history,
                onOpenDetails = openDetails,
                onContinue = { playTitle = it },
            )
            1 -> CatalogScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                onOpenDetails = openDetails,
            )
            2 -> SearchScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                recentQueries = recentSearches,
                onSearchCommitted = { query -> scope.launch { preferencesRepository.addSearchQuery(query) } },
                onClearRecent = { scope.launch { preferencesRepository.clearSearchHistory() } },
                onOpenDetails = openDetails,
            )
            3 -> LibraryScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                favorites = favorites,
                watchLater = watchLater,
                history = history,
                downloads = downloads,
                hasProgress = lastProgress.title.isNotBlank() && lastProgress.positionMs > 0L,
                onOpenDetails = openDetails,
                onClearHistory = { scope.launch { preferencesRepository.clearHistory() } },
            )
            4 -> ProfileScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                onOpenSettings = { settingsRoute = it },
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= 600.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    topLevelDestinations.forEachIndexed { index, destination ->
                        val selected = selectedIndex == index
                        NavigationRailItem(
                            selected = selected,
                            onClick = { selectedIndex = index },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    screenContent(WindowInsets.safeDrawing.asPaddingValues())
                }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        topLevelDestinations.forEachIndexed { index, destination ->
                            val selected = selectedIndex == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { selectedIndex = index },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                screenContent(innerPadding)
            }
        }
    }

}
