package app.viora.android.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.viora.android.data.download.DownloadScheduler
import app.viora.android.data.library.LibraryRepository
import app.viora.android.data.preferences.AppPreferences
import app.viora.android.data.preferences.PlaybackPreferences
import app.viora.android.domain.model.PlaybackProgress
import app.viora.android.data.preferences.TitlePlaybackPreferences
import app.viora.android.data.catalog.DemoCatalogRepository
import app.viora.android.data.preferences.VioraPreferencesRepository
import app.viora.android.ui.catalog.CatalogLaunchPreset
import app.viora.android.ui.catalog.CatalogScreen
import app.viora.android.ui.details.DetailsScreen
import app.viora.android.ui.home.HomeScreen
import app.viora.android.ui.library.LibraryScreen
import app.viora.android.ui.player.MiniPlayerBar
import app.viora.android.ui.player.PlaybackSession
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
import app.viora.android.ui.theme.VioraBrandAmber
import app.viora.android.ui.theme.VioraOnBrandAmber

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
)

internal fun playbackBaseTitle(current: String): String =
    current.substringBefore(" · S").substringBefore(" · E")

internal fun nextEpisodeTitle(current: String): String? {
    val seasonMatch = Regex("^(.*) · S(\\d{2})E(\\d{2}) · Эпизод (\\d+)$").matchEntire(current)
    if (seasonMatch != null) {
        val base = seasonMatch.groupValues[1]
        val season = seasonMatch.groupValues[2].toIntOrNull() ?: return null
        val episode = seasonMatch.groupValues[3].toIntOrNull() ?: return null
        val seasonEpisodeCounts = DemoCatalogRepository.findByTitle(base)?.seasonEpisodeCounts.orEmpty()
        val episodeCount = seasonEpisodeCounts.getOrNull(season - 1) ?: return null
        return when {
            episode < episodeCount -> {
                val next = episode + 1
                "$base · S${season.toString().padStart(2, '0')}E${next.toString().padStart(2, '0')} · Эпизод $next"
            }
            season < seasonEpisodeCounts.size -> {
                val nextSeason = season + 1
                "$base · S${nextSeason.toString().padStart(2, '0')}E01 · Эпизод 1"
            }
            else -> null
        }
    }

    // Legacy titles can still exist in persisted playback history from pre-season builds.
    val legacyMatch = Regex("^(.*) · E(\\d{2}) · Эпизод (\\d+)$").matchEntire(current) ?: return null
    val base = legacyMatch.groupValues[1]
    val episode = legacyMatch.groupValues[2].toIntOrNull() ?: return null
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
    val libraryRepository = remember(context) {
        LibraryRepository(context.applicationContext)
    }
    val appPreferences by preferencesRepository.appPreferences.collectAsState(initial = AppPreferences())

    VioraTheme(
        themeMode = appPreferences.themeMode,
        highContrast = appPreferences.highContrast,
    ) {
        VioraContent(
            context = context,
            preferencesRepository = preferencesRepository,
            libraryRepository = libraryRepository,
            appPreferences = appPreferences,
        )
    }
}

@Composable
private fun VioraContent(
    context: Context,
    preferencesRepository: VioraPreferencesRepository,
    libraryRepository: LibraryRepository,
    appPreferences: AppPreferences,
) {
    val scope = rememberCoroutineScope()
    val playbackSession = remember(context) { PlaybackSession(context.applicationContext) }
    DisposableEffect(playbackSession) {
        onDispose { playbackSession.release() }
    }

    LaunchedEffect(preferencesRepository, libraryRepository) {
        if (preferencesRepository.needsRoomLibraryMigration()) {
            val legacy = preferencesRepository.readLegacyLibrarySnapshot()
            libraryRepository.importLegacy(legacy)
            preferencesRepository.finishRoomLibraryMigration()
        }
    }

    val playbackPreferences by preferencesRepository.playbackPreferences.collectAsState(initial = PlaybackPreferences())
    val favorites by libraryRepository.favorites.collectAsState(initial = emptySet())
    val watchLater by libraryRepository.watchLater.collectAsState(initial = emptySet())
    val downloads by libraryRepository.downloads.collectAsState(initial = emptySet())
    val history by libraryRepository.history.collectAsState(initial = emptyList())
    val recentSearches by libraryRepository.recentSearches.collectAsState(initial = emptyList())
    val lastProgress by libraryRepository.lastProgress.collectAsState(initial = PlaybackProgress())
    val progressByTitle by libraryRepository.progressByTitle.collectAsState(initial = emptyMap())
    val playbackState by playbackSession.state.collectAsState()
    val effectiveProgress = if (playbackState.hasMedia && playbackState.totalDurationMs > 0L) {
        PlaybackProgress(
            title = playbackState.displayTitle,
            positionMs = playbackState.currentPositionMs,
            durationMs = playbackState.totalDurationMs,
        )
    } else {
        lastProgress
    }
    val effectiveProgressByTitle = if (playbackState.hasMedia && playbackState.totalDurationMs > 0L) {
        progressByTitle + (playbackState.displayTitle to effectiveProgress)
    } else {
        progressByTitle
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var catalogLaunchPreset by remember { mutableStateOf<CatalogLaunchPreset?>(null) }
    var detailsTitle by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var fullPlayerOpen by rememberSaveable { mutableStateOf(false) }

    val persistActiveProgress: () -> Unit = {
        val state = playbackSession.state.value
        if (state.hasMedia && state.currentPositionMs >= 0L && state.totalDurationMs > 0L) {
            scope.launch {
                libraryRepository.saveProgress(
                    state.displayTitle,
                    state.currentPositionMs,
                    state.totalDurationMs,
                    state.lastUpdatedTimestamp,
                )
            }
        }
    }

    val closePlayback: () -> Unit = {
        persistActiveProgress()
        playbackSession.stopAndClear()
        fullPlayerOpen = false
    }

    val startPlayback: (String) -> Unit = { title ->
        val baseTitle = playbackBaseTitle(title)
        val content = DemoCatalogRepository.findByTitle(baseTitle)
        val episodeMatch = Regex(""".* · S(\d{2})E(\d{2})(?: · Эпизод \d+)?$""").matchEntire(title)
        val seasonNumber = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episodeNumber = episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val localSource = (
            DownloadScheduler.localFile(context.applicationContext, title)
                ?: DownloadScheduler.localFile(context.applicationContext, baseTitle)
            )?.toURI()?.toString()
        val saved = progressByTitle[title] ?: lastProgress.takeIf { it.title == title }
        playbackSession.start(
            mediaId = content?.id ?: baseTitle,
            title = title,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            sourceUri = localSource,
            startPositionMs = saved?.positionMs ?: 0L,
            audioTrackId = playbackPreferences.audio,
            subtitleTrackId = if (playbackPreferences.subtitlesEnabled) "Auto" else null,
        )
        fullPlayerOpen = true
    }

    LaunchedEffect(playbackSession, libraryRepository) {
        while (true) {
            delay(2_000L)
            val state = playbackSession.state.value
            if (state.hasMedia && state.currentPositionMs >= 0L && state.totalDurationMs > 0L) {
                libraryRepository.saveProgress(
                    state.displayTitle,
                    state.currentPositionMs,
                    state.totalDurationMs,
                    state.lastUpdatedTimestamp,
                )
            }
        }
    }

    if (fullPlayerOpen && playbackState.hasMedia) {
        val title = playbackState.displayTitle
        val baseTitle = playbackBaseTitle(title)
        val titlePreferencesFlow = remember(baseTitle, preferencesRepository) {
            preferencesRepository.titlePlaybackPreferences(baseTitle)
        }
        val titlePreferences by titlePreferencesFlow.collectAsState(initial = TitlePlaybackPreferences())
        val resolvedAudio = titlePreferences.audio ?: playbackPreferences.audio
        val resolvedQuality = titlePreferences.quality ?: playbackPreferences.quality

        PlayerScreen(
            session = playbackSession,
            title = title,
            onMinimize = { fullPlayerOpen = false },
            onBack = {
                // Leaving the full player is an explicit playback exit: persist first,
                // then stop/clear so audio/video cannot continue behind the app UI.
                closePlayback()
            },
            preferredAudio = resolvedAudio,
            preferredQuality = resolvedQuality,
            onAudioSelected = { audio ->
                playbackSession.setTrackPreferences(audio, playbackSession.state.value.subtitleTrackId)
                scope.launch { preferencesRepository.setTitleAudio(baseTitle, audio) }
            },
            onQualitySelected = { quality ->
                scope.launch { preferencesRepository.setTitleQuality(baseTitle, quality) }
            },
            subtitlesEnabled = playbackPreferences.subtitlesEnabled,
            autoNextEnabled = playbackPreferences.autoNextEnabled,
            persistentSeekButtons = appPreferences.persistentSeekButtons,
            onSubtitlesChanged = { enabled ->
                playbackSession.setTrackPreferences(
                    playbackSession.state.value.audioTrackId,
                    if (enabled) (playbackSession.state.value.subtitleTrackId ?: "Auto") else null,
                )
                scope.launch { preferencesRepository.setSubtitlesEnabled(enabled) }
            },
            onSubtitleTrackIdChanged = { trackId ->
                playbackSession.setTrackPreferences(
                    playbackSession.state.value.audioTrackId,
                    trackId,
                )
            },
            onNextEpisode = {
                nextEpisodeTitle(title)?.let(startPlayback)
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val miniVisible = playbackState.hasMedia
    val contentBottomPadding = if (miniVisible) 76.dp else 0.dp

    if (detailsTitle != null) {
        val title = detailsTitle.orEmpty()
        val titlePreferencesFlow = remember(title, preferencesRepository) {
            preferencesRepository.titlePlaybackPreferences(title)
        }
        val titlePreferences by titlePreferencesFlow.collectAsState(initial = TitlePlaybackPreferences())
        val resolvedAudio = titlePreferences.audio ?: playbackPreferences.audio
        val resolvedQuality = titlePreferences.quality ?: playbackPreferences.quality
        val inMyList = title in favorites || title in watchLater

        val toggleDownload: (String) -> Unit = { target ->
            if (target in downloads) {
                if (DownloadScheduler.delete(context.applicationContext, target)) {
                    scope.launch { libraryRepository.setDownloaded(target, false) }
                }
            } else {
                DownloadScheduler.enqueue(
                    context = context.applicationContext,
                    title = target,
                    wifiOnly = playbackPreferences.wifiOnlyDownloads,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            DetailsScreen(
                title = title,
                onBack = { detailsTitle = null },
                onPlay = startPlayback,
                inMyList = inMyList,
                onMyListChange = { enabled ->
                    scope.launch {
                        // Keep legacy collections synchronized while the new UI exposes one SSOT-facing action.
                        libraryRepository.setFavorite(title, enabled)
                        libraryRepository.setWatchLater(title, enabled)
                    }
                },
                downloads = downloads,
                onDownloadTitle = toggleDownload,
                selectedAudio = resolvedAudio,
                selectedQuality = resolvedQuality,
                subtitlesEnabled = playbackPreferences.subtitlesEnabled,
                progressByTitle = effectiveProgressByTitle,
                latestProgress = effectiveProgress,
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = contentBottomPadding),
            )
            if (miniVisible) {
                MiniPlayerBar(
                    session = playbackSession,
                    onOpen = { fullPlayerOpen = true },
                    onClose = closePlayback,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
        return
    }

    settingsRoute?.let { route ->
        val closeSettings = { settingsRoute = null }
        val settingsModifier = Modifier
            .fillMaxSize()
            .padding(bottom = contentBottomPadding)
        Box(modifier = Modifier.fillMaxSize()) {
            when (route) {
                "playback" -> PlaybackSettingsScreen(
                    preferences = playbackPreferences,
                    onBack = closeSettings,
                    onAudioSelected = { value -> scope.launch { preferencesRepository.setAudio(value) } },
                    onQualitySelected = { value -> scope.launch { preferencesRepository.setQuality(value) } },
                    onSubtitlesChanged = { value -> scope.launch { preferencesRepository.setSubtitlesEnabled(value) } },
                    onAutoNextChanged = { value -> scope.launch { preferencesRepository.setAutoNextEnabled(value) } },
                    modifier = settingsModifier,
                )
                "downloads" -> DownloadsSettingsScreen(
                    preferences = playbackPreferences,
                    downloadedCount = downloads.size,
                    onBack = closeSettings,
                    onWifiOnlyChanged = { value -> scope.launch { preferencesRepository.setWifiOnlyDownloads(value) } },
                    onDeleteAll = {
                        if (DownloadScheduler.deleteAll(context.applicationContext)) {
                            scope.launch { libraryRepository.clearDownloads() }
                        }
                    },
                    modifier = settingsModifier,
                )
                "notifications" -> NotificationsSettingsScreen(
                    onBack = closeSettings,
                    modifier = settingsModifier,
                )
                "appearance" -> AppearanceSettingsScreen(
                    preferences = appPreferences,
                    onBack = closeSettings,
                    onThemeModeChanged = { value -> scope.launch { preferencesRepository.setThemeMode(value) } },
                    modifier = settingsModifier,
                )
                "accessibility" -> AccessibilitySettingsScreen(
                    preferences = appPreferences,
                    onBack = closeSettings,
                    onHighContrastChanged = { value -> scope.launch { preferencesRepository.setHighContrast(value) } },
                    onPersistentSeekButtonsChanged = { value ->
                        scope.launch { preferencesRepository.setPersistentSeekButtons(value) }
                    },
                    modifier = settingsModifier,
                )
                "devices" -> DevicesSettingsScreen(onBack = closeSettings, modifier = settingsModifier)
                "help" -> HelpSettingsScreen(onBack = closeSettings, modifier = settingsModifier)
            }
            if (miniVisible) {
                MiniPlayerBar(
                    session = playbackSession,
                    onOpen = { fullPlayerOpen = true },
                    onClose = closePlayback,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding(),
                )
            }
        }
        return
    }

    val openDetails: (String) -> Unit = { title ->
        detailsTitle = title
        scope.launch { libraryRepository.addHistory(title) }
    }

    val screenContent: @Composable (PaddingValues) -> Unit = { innerPadding ->
        when (selectedIndex) {
            0 -> HomeScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                progress = effectiveProgress,
                history = history,
                onOpenDetails = openDetails,
                onContinue = startPlayback,
                onOpenCatalog = { preset ->
                    catalogLaunchPreset = preset
                    selectedIndex = 1
                },
                onOpenProfile = { selectedIndex = 4 },
            )
            1 -> CatalogScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                launchPreset = catalogLaunchPreset,
                onLaunchPresetConsumed = { catalogLaunchPreset = null },
                onOpenDetails = openDetails,
            )
            2 -> SearchScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                recentQueries = recentSearches,
                onSearchCommitted = { query -> scope.launch { libraryRepository.addSearchQuery(query) } },
                onClearRecent = { scope.launch { libraryRepository.clearSearchHistory() } },
                onOpenDetails = openDetails,
            )
            3 -> LibraryScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                favorites = favorites,
                watchLater = watchLater,
                history = history,
                downloads = downloads,
                progress = effectiveProgress,
                onContinuePlayback = startPlayback,
                onOpenDetails = openDetails,
                onClearHistory = { snapshot ->
                    scope.launch {
                        libraryRepository.clearHistory()
                        val result = snackbarHostState.showSnackbar(
                            message = "История очищена",
                            actionLabel = "Отменить",
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            libraryRepository.restoreHistory(snapshot)
                        }
                    }
                },
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
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = VioraOnBrandAmber,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = VioraBrandAmber,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        screenContent(WindowInsets.safeDrawing.asPaddingValues())
                    }
                    if (miniVisible) {
                        MiniPlayerBar(
                            session = playbackSession,
                            onOpen = { fullPlayerOpen = true },
                            onClose = closePlayback,
                        )
                    }
                }
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (miniVisible) 88.dp else 16.dp),
            )
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column {
                        if (miniVisible) {
                            MiniPlayerBar(
                                session = playbackSession,
                                onOpen = { fullPlayerOpen = true },
                                onClose = closePlayback,
                            )
                        }
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
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = VioraOnBrandAmber,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = VioraBrandAmber,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { innerPadding ->
                screenContent(innerPadding)
            }
        }
    }
}
