package app.movia.android.ui

import android.content.Context
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.movia.android.data.download.DownloadScheduler
import app.movia.android.data.library.LibraryRepository
import app.movia.android.data.preferences.AppPreferences
import app.movia.android.data.preferences.PlaybackPreferences
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.data.preferences.TitlePlaybackPreferences
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.preferences.MoviaPreferencesRepository
import app.movia.android.ui.catalog.CatalogLaunchPreset
import app.movia.android.ui.catalog.CatalogScreen
import app.movia.android.ui.details.DetailsScreen
import app.movia.android.ui.home.HomeScreen
import app.movia.android.ui.library.LibraryScreen
import app.movia.android.ui.player.MiniPlayerBar
import app.movia.android.ui.player.PlaybackSession
import app.movia.android.ui.player.PlayerScreen
import app.movia.android.ui.profile.ProfileScreen
import app.movia.android.ui.search.SearchScreen
import app.movia.android.ui.settings.AccessibilitySettingsScreen
import app.movia.android.ui.settings.AppearanceSettingsScreen
import app.movia.android.ui.settings.DevicesSettingsScreen
import app.movia.android.ui.settings.DownloadsSettingsScreen
import app.movia.android.ui.settings.HelpSettingsScreen
import app.movia.android.ui.settings.NotificationsSettingsScreen
import app.movia.android.ui.settings.PlaybackSettingsScreen
import app.movia.android.ui.theme.MoviaTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.movia.android.ui.theme.MoviaBrandAmber
import app.movia.android.ui.theme.MoviaOnBrandAmber
import app.movia.android.ui.theme.MoviaBorderSubtle
import app.movia.android.ui.theme.MoviaNavTopBorder
import app.movia.android.ui.theme.MoviaNavGlassSurface
import app.movia.android.ui.theme.MoviaNavActiveGlow
import app.movia.android.ui.theme.MoviaNavActiveGlowClear
import app.movia.android.ui.theme.MoviaGlowLuminescenceOpaque
import app.movia.android.ui.theme.MoviaLibraryIconTile
import app.movia.android.ui.theme.MoviaLibraryIconPlay

private enum class MoviaNavIcon {
    HOME,
    CATALOG,
    SEARCH,
    LIBRARY,
}

private enum class MoviaNavBadge {
    NONE,
    NEW,
}

private data class TopLevelDestination(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val moviaIcon: MoviaNavIcon,
    val badge: MoviaNavBadge = MoviaNavBadge.NONE,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("Главная", Icons.Filled.Home, Icons.Outlined.Home, MoviaNavIcon.HOME),
    TopLevelDestination("Каталог", Icons.Filled.ViewModule, Icons.Outlined.ViewModule, MoviaNavIcon.CATALOG),
    TopLevelDestination("Поиск", Icons.Filled.Search, Icons.Outlined.Search, MoviaNavIcon.SEARCH),
    TopLevelDestination("Моё", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, MoviaNavIcon.LIBRARY),
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
fun MoviaApp() {
    val context = LocalContext.current
    val preferencesRepository = remember(context) {
        MoviaPreferencesRepository(context.applicationContext)
    }
    val libraryRepository = remember(context) {
        LibraryRepository(context.applicationContext)
    }
    val appPreferences by preferencesRepository.appPreferences.collectAsState(initial = AppPreferences())

    LaunchedEffect(appPreferences.themeMode) {
        if (appPreferences.themeMode != "DARK") {
            preferencesRepository.setThemeMode("DARK")
        }
    }

    MoviaTheme(
        themeMode = appPreferences.themeMode,
        highContrast = appPreferences.highContrast,
    ) {
        MoviaContent(
            context = context,
            preferencesRepository = preferencesRepository,
            libraryRepository = libraryRepository,
            appPreferences = appPreferences,
        )
    }
}

@Composable
private fun MoviaContent(
    context: Context,
    preferencesRepository: MoviaPreferencesRepository,
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
        libraryRepository.backfillCanonicalContentIds()
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
            contentId = playbackState.mediaId.takeIf { it.isNotBlank() },
            updatedAt = playbackState.lastUpdatedTimestamp,
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
    var profileOpen by rememberSaveable { mutableStateOf(false) }
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
            sourceUri = localSource ?: content?.playbackUrl,
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
            onSelectEpisode = { season, episode ->
                val exact = "$baseTitle · S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')} · Эпизод $episode"
                startPlayback(exact)
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
                onOpenDetails = { relatedTitle -> detailsTitle = relatedTitle },
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
                progressByTitle = effectiveProgressByTitle,
                latestProgress = effectiveProgress,
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

    if (profileOpen) {
        ProfileScreen(
            modifier = Modifier.fillMaxSize(),
            onBack = { profileOpen = false },
            onOpenSettings = { settingsRoute = it },
        )
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
                onOpenProfile = { profileOpen = true },
                onOpenNotifications = { settingsRoute = "notifications" },
            )
            1 -> CatalogScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                launchPreset = catalogLaunchPreset,
                onLaunchPresetConsumed = { catalogLaunchPreset = null },
                history = history,
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
                catalog = DemoCatalogRepository.all(),
                onOpenDetails = openDetails,
                onOpenCatalog = { selectedIndex = 1 },
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
                                selectedIconColor = MoviaOnBrandAmber,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MoviaBrandAmber,
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
            val systemTop = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding()
            val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val navHeight = 68.dp + systemBottom
            val miniPlayerHeight = if (miniVisible) 76.dp else 0.dp
            // Every top-level scroll surface must be able to move its final row fully
            // above the fixed navigation stack. The 16dp breathing room is part of the
            // contract, not an ad-hoc per-screen spacer.
            val scrollContentPadding = PaddingValues(
                top = 0.dp,
                bottom = navHeight + miniPlayerHeight + 16.dp,
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Safe drawing is a fixed viewport boundary, not the first item of a
                        // scroll list. Content therefore cannot slide underneath the status bar.
                        .padding(top = systemTop)
                        .moviaBackdropBlur(
                            barHeight = navHeight + miniPlayerHeight,
                            // Proven Movia 0.2.60 contract: sharp pixels are never left
                            // underneath the fixed navigation zone. They are replaced by
                            // this 40 px RenderNode-blurred snapshot before the glass tint.
                            blurRadiusPx = 40f,
                            overlayColor = MoviaNavGlassSurface,
                        ),
                ) {
                    screenContent(scrollContentPadding)
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // Explicit overlay contract: scrolling content can never paint
                        // above the fixed bottom navigation stack.
                        .zIndex(1000f),
                ) {
                    if (miniVisible) {
                        MiniPlayerBar(
                            session = playbackSession,
                            onOpen = { fullPlayerOpen = true },
                            onClose = closePlayback,
                        )
                    }
                    MoviaBottomNavigation(
                        selectedIndex = selectedIndex,
                        onSelected = { selectedIndex = it },
                    )
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = navHeight + miniPlayerHeight + 12.dp),
                )
            }
        }
    }
}

private fun Modifier.moviaBackdropBlur(
    barHeight: androidx.compose.ui.unit.Dp,
    blurRadiusPx: Float,
    overlayColor: Color,
): Modifier {
    // This is the proven Movia 0.2.60 implementation. RenderNode is isolated from the
    // Android View hierarchy, so it does not re-enter Compose measure/layout.
    val blurNode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        android.graphics.RenderNode("MoviaBottomBackdrop")
    } else {
        null
    }

    return drawWithContent {
        val contentScope = this
        val barHeightPx = barHeight.toPx().coerceAtMost(size.height)
        val top = (size.height - barHeightPx).coerceAtLeast(0f)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurNode != null && top < size.height) {
            val widthPx = size.width.toInt().coerceAtLeast(1)
            val heightPx = size.height.toInt().coerceAtLeast(1)
            val blurPx = blurRadiusPx.coerceAtLeast(1f)

            // Record one complete snapshot of the already-laid-out screen content.
            blurNode.setPosition(0, 0, widthPx, heightPx)
            blurNode.setRenderEffect(
                AndroidRenderEffect.createBlurEffect(
                    blurPx,
                    blurPx,
                    Shader.TileMode.CLAMP,
                ),
            )
            val recordingCanvas = blurNode.beginRecording(widthPx, heightPx)
            val originalCanvas = drawContext.canvas
            try {
                drawContext.canvas = ComposeCanvas(recordingCanvas)
                this.drawContent()
            } finally {
                drawContext.canvas = originalCanvas
                blurNode.endRecording()
            }

            // Critical 0.2.60 behavior: render sharp content only ABOVE the bar. Inside
            // the bar zone, replace it completely with the blurred snapshot. Text, ratings
            // and card contours therefore cannot remain readable through the glass.
            clipRect(top = 0f, bottom = top) {
                contentScope.drawContent()
            }
            clipRect(top = top, bottom = size.height) {
                drawContext.canvas.nativeCanvas.drawRenderNode(blurNode)
            }
        } else {
            // Pre-Android 12 fallback: keep content and rely on the dense matte tint.
            drawContent()
        }

        drawRect(
            color = overlayColor,
            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
            size = androidx.compose.ui.geometry.Size(size.width, size.height - top),
        )
        drawLine(
            color = MoviaNavTopBorder,
            start = androidx.compose.ui.geometry.Offset(0f, top),
            end = androidx.compose.ui.geometry.Offset(size.width, top),
            strokeWidth = 1f,
        )
    }
}

@Composable
private fun MoviaBottomNavigation(
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
) {
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val activeColor = MoviaBrandAmber
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp + systemBottom)
            .zIndex(1000f),
    ) {
        // Extend only the panel/background upward by 4dp. The 64dp button row stays
        // bottom-anchored at exactly the same screen coordinates as before.
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            topLevelDestinations.forEachIndexed { index, destination ->
                val selected = selectedIndex == index
                val isLibrary = destination.moviaIcon == MoviaNavIcon.LIBRARY
                val contentColor = if (selected) activeColor else inactiveColor
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clickable { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Canvas(modifier = Modifier.requiredSize(56.dp)) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colorStops = arrayOf(
                                            0.00f to MoviaGlowLuminescenceOpaque.copy(alpha = 0.48f),
                                            0.20f to MoviaGlowLuminescenceOpaque.copy(alpha = 0.40f),
                                            0.40f to MoviaNavActiveGlow,
                                            0.60f to MoviaGlowLuminescenceOpaque.copy(alpha = 0.15f),
                                            0.80f to MoviaGlowLuminescenceOpaque.copy(alpha = 0.065f),
                                            1.00f to MoviaNavActiveGlowClear,
                                        ),
                                        center = center,
                                        radius = size.minDimension / 2f,
                                    ),
                                )
                            }
                        }
                        MoviaBottomNavIcon(
                            kind = destination.moviaIcon,
                            selected = selected,
                            color = contentColor,
                            modifier = Modifier.size(31.dp),
                        )
                    }
                    Text(
                        text = destination.label,
                        color = contentColor,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (isLibrary) FontWeight.Medium else if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        letterSpacing = if (isLibrary) (-0.1).sp else 0.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(systemBottom))
    }
}

@Composable
private fun MoviaBottomNavIcon(
    kind: MoviaNavIcon,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val iconCutoutColor = MaterialTheme.colorScheme.background
    Canvas(modifier = modifier) {
        val u = size.minDimension / 26f
        val line = (if (selected) 1.95f else 1.75f) * u

        when (kind) {
            MoviaNavIcon.HOME -> {
                val path = Path().apply {
                    moveTo(4.5f * u, 12.5f * u)
                    lineTo(13f * u, 5.2f * u)
                    lineTo(21.5f * u, 12.5f * u)
                    lineTo(19.5f * u, 12.5f * u)
                    lineTo(19.5f * u, 21.2f * u)
                    lineTo(15.5f * u, 21.2f * u)
                    lineTo(15.5f * u, 16.2f * u)
                    lineTo(10.5f * u, 16.2f * u)
                    lineTo(10.5f * u, 21.2f * u)
                    lineTo(6.5f * u, 21.2f * u)
                    lineTo(6.5f * u, 12.5f * u)
                    close()
                }
                if (selected) {
                    drawPath(path, color)
                } else {
                    drawPath(path, color, style = Stroke(width = line, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }

            MoviaNavIcon.CATALOG -> {
                val tile = 7f * u
                val positions = listOf(4.5f to 4.5f, 14.5f to 4.5f, 4.5f to 14.5f, 14.5f to 14.5f)
                positions.forEach { (x, y) ->
                    drawRoundRect(
                        color = color,
                        topLeft = androidx.compose.ui.geometry.Offset(x * u, y * u),
                        size = androidx.compose.ui.geometry.Size(tile, tile),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f * u, 1.5f * u),
                    )
                }
            }

            MoviaNavIcon.SEARCH -> {
                val center = androidx.compose.ui.geometry.Offset(11f * u, 11f * u)
                drawCircle(
                    color = color,
                    radius = 6f * u,
                    center = center,
                    style = Stroke(width = line),
                )
                drawLine(
                    color = color,
                    start = androidx.compose.ui.geometry.Offset(15.3f * u, 15.3f * u),
                    end = androidx.compose.ui.geometry.Offset(21.2f * u, 21.2f * u),
                    strokeWidth = line,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }

            MoviaNavIcon.LIBRARY -> {
                // Minimal Media Library mark: exactly one flat rounded square and one Play glyph.
                // The geometry stays fixed; only the two fills follow the selected state.
                val tileTopLeft = androidx.compose.ui.geometry.Offset(3.9f * u, 3.9f * u)
                val tileSize = androidx.compose.ui.geometry.Size(18.2f * u, 18.2f * u)
                val tileColor = if (selected) MoviaBrandAmber else MoviaLibraryIconTile
                val playColor = if (selected) iconCutoutColor else MoviaLibraryIconPlay
                drawRoundRect(
                    color = tileColor,
                    topLeft = tileTopLeft,
                    size = tileSize,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(5.0f * u, 5.0f * u),
                )

                // Optical compensation is deliberately specified in physical Canvas pixels:
                // +1.2 px on X, with a ~1.75 px softened Play corner radius.
                val cx = 13.0f * u + 1.2f
                val cy = 13.0f * u
                val halfH = 4.7f * u
                val leftX = cx - 3.25f * u
                val rightX = cx + 4.65f * u
                val round = 1.75f
                val play = Path().apply {
                    moveTo(leftX, cy - halfH + round)
                    quadraticTo(leftX, cy - halfH, leftX + round, cy - halfH + round * 0.18f)
                    lineTo(rightX - round, cy - round * 0.52f)
                    quadraticTo(rightX, cy, rightX - round, cy + round * 0.52f)
                    lineTo(leftX + round, cy + halfH - round * 0.18f)
                    quadraticTo(leftX, cy + halfH, leftX, cy + halfH - round)
                    close()
                }
                drawPath(play, playColor)
            }
        }
    }
}
