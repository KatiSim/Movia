package app.movia.android.ui

import android.content.Context
import android.content.ContextWrapper
import android.app.Activity
import android.os.Build
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.Shader
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import org.json.JSONObject
import app.movia.android.data.download.DownloadScheduler
import app.movia.android.agent.AgentControlRuntime
import app.movia.android.data.library.LibraryRepository
import app.movia.android.data.preferences.AppPreferences
import app.movia.android.data.preferences.PlaybackPreferences
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.nextEpisode
import app.movia.android.domain.model.previousEpisode
import app.movia.android.domain.usecase.SetFavoriteUseCase
import app.movia.android.data.preferences.TitlePlaybackPreferences
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.preferences.MoviaPreferencesRepository
import app.movia.android.ui.catalog.CatalogLaunchPreset
import app.movia.android.ui.catalog.CatalogRetentionState
import app.movia.android.ui.catalog.CatalogScreen
import app.movia.android.ui.details.DetailsScreen
import app.movia.android.ui.home.HomeScreen
import app.movia.android.ui.library.LibraryScreen
import app.movia.android.ui.library.LibraryUiState
import app.movia.android.ui.library.LibraryViewModel
import app.movia.android.ui.navigation.MoviaNavigationState
import app.movia.android.ui.navigation.MoviaRoute
import app.movia.android.ui.navigation.MoviaSettingsPage
import app.movia.android.ui.navigation.MoviaTopLevel
import app.movia.android.ui.player.MiniPlayerBar
import app.movia.android.ui.player.PlaybackSession
import app.movia.android.ui.player.MoviaPlaybackRegistry
import app.movia.android.ui.player.MoviaPiPState
import app.movia.android.ui.player.PlayerScreen
import app.movia.android.ui.player.buildMoviaPictureInPictureParams
import app.movia.android.ui.profile.ProfileScreen
import app.movia.android.ui.settings.DownloadsSettingsScreen
import app.movia.android.ui.settings.HelpSettingsScreen
import app.movia.android.ui.theme.MoviaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.mapTestTagsToResourceIds(): Modifier = semantics {
    testTagsAsResourceId = true
}

private enum class MoviaNavIcon {
    HOME,
    CATALOG,
    SEARCH,
    LIBRARY,
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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

private fun encodeMoviaRoute(route: MoviaRoute): String = when (route) {
    is MoviaRoute.Details -> JSONObject()
        .put("type", "details")
        .put("mediaId", route.mediaId)
        .put("title", route.title)
        .toString()
    MoviaRoute.Profile -> "@profile"
    is MoviaRoute.Settings -> "@settings:${route.page.name.lowercase()}"
    MoviaRoute.Player -> "@player"
}

private fun decodeMoviaRoute(value: String): MoviaRoute? {
    if (value == "@profile") return MoviaRoute.Profile
    if (value == "@player") return MoviaRoute.Player
    if (value.startsWith("@settings:")) {
        val page = when (value.substringAfter(':')) {
            "downloads" -> MoviaSettingsPage.DOWNLOADS
            "help" -> MoviaSettingsPage.HELP
            else -> return null
        }
        return MoviaRoute.Settings(page)
    }
    val json = runCatching { JSONObject(value) }.getOrNull()
    return if (json != null && json.has("title")) {
        MoviaRoute.Details(json.optString("mediaId"), json.optString("title"))
    } else {
        // Existing saved detail stacks contained either this JSON shape or a plain title.
        value.takeIf(String::isNotBlank)?.let { MoviaRoute.Details(mediaId = "", title = it) }
    }
}

private val moviaNavigationStateSaver = listSaver<MoviaNavigationState, String>(
    save = { state ->
        listOf("tab:${state.selectedTab.name}") + state.backStack.map(::encodeMoviaRoute)
    },
    restore = { saved ->
        val tab = saved.firstOrNull()
            ?.removePrefix("tab:")
            ?.let { raw -> MoviaTopLevel.entries.firstOrNull { it.name == raw } }
            ?: MoviaTopLevel.HOME
        MoviaNavigationState(
            selectedTab = tab,
            backStack = saved.drop(1).mapNotNull(::decodeMoviaRoute),
        )
    },
)

private val topLevelDestinations = listOf(
    TopLevelDestination("Главная", Icons.Filled.Home, Icons.Outlined.Home, MoviaNavIcon.HOME),
    TopLevelDestination("Каталог", Icons.Filled.ViewModule, Icons.Outlined.ViewModule, MoviaNavIcon.CATALOG),
    TopLevelDestination("Моё", Icons.Filled.VideoLibrary, Icons.Outlined.VideoLibrary, MoviaNavIcon.LIBRARY),
)

private fun topLevelControlTag(index: Int): String = when (index) {
    0 -> "navigation.home"
    1 -> "navigation.catalog"
    2 -> "navigation.library"
    else -> "navigation.unknown.$index"
}

internal fun playbackBaseTitle(current: String): String =
    current.substringBefore(" · S").substringBefore(" · E")

internal fun episodeDisplayTitle(baseTitle: String, mediaRef: MediaRef): String =
    if (mediaRef.season != null && mediaRef.episode != null) {
        "$baseTitle · S${mediaRef.season.toString().padStart(2, '0')}E${mediaRef.episode.toString().padStart(2, '0')} · Эпизод ${mediaRef.episode}"
    } else {
        baseTitle
    }

@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun MoviaApp() {
    val context = LocalContext.current
    val preferencesRepository = remember(context) {
        MoviaPreferencesRepository(context.applicationContext)
    }
    val libraryViewModel: LibraryViewModel = viewModel()
    val libraryRepository = libraryViewModel.repository
    val libraryUiState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val resumeHeroState by libraryViewModel.resumeHeroState.collectAsStateWithLifecycle()
    LaunchedEffect(libraryViewModel, preferencesRepository) {
        libraryViewModel.initialize(preferencesRepository)
    }
    val appPreferences by preferencesRepository.appPreferences.collectAsStateWithLifecycle(initialValue = AppPreferences())

    MoviaTheme(
        themeMode = appPreferences.themeMode,
        highContrast = appPreferences.highContrast,
    ) {
        MoviaContent(
            context = context,
            preferencesRepository = preferencesRepository,
            libraryRepository = libraryRepository,
            libraryUiState = libraryUiState,
            resumeHeroState = resumeHeroState,
            appPreferences = appPreferences,
        )
    }
}

@Composable
private fun MoviaContent(
    context: Context,
    preferencesRepository: MoviaPreferencesRepository,
    libraryRepository: LibraryRepository,
    libraryUiState: LibraryUiState,
    resumeHeroState: app.movia.android.ui.library.ResumeHeroState,
    appPreferences: AppPreferences,
) {
    val scope = rememberCoroutineScope()
    val setFavorite = remember(libraryRepository) { SetFavoriteUseCase(libraryRepository) }
    var pendingPlaybackJob by remember { mutableStateOf<Job?>(null) }
    val playbackSession = remember(context) { MoviaPlaybackRegistry.obtain(context.applicationContext) }
    DisposableEffect(playbackSession) {
        onDispose { playbackSession.release() }
    }

    val playbackPreferences by preferencesRepository.playbackPreferences.collectAsStateWithLifecycle(initialValue = PlaybackPreferences())
    val favorites = libraryUiState.favorites
    val favoriteRecords = libraryUiState.favoriteRecords
    val favoriteContentIds = favoriteRecords.mapNotNull { it.contentId }.toSet()
    val downloads = libraryUiState.downloads
    val downloadRecords = libraryUiState.downloadRecords
    val history = libraryUiState.history
    val historyRecords = libraryUiState.historyRecords
    val recentSearches = libraryUiState.recentSearches
    val lastProgress = libraryUiState.lastProgress
    val progressByTitle = libraryUiState.progressByTitle
    val progressByMediaRef = libraryUiState.progressByMediaRef
    val playbackState by playbackSession.state.collectAsStateWithLifecycle()
    val homeFeed by DemoCatalogRepository.homeFeed.collectAsStateWithLifecycle()
    val libraryCatalog = remember(homeFeed.catalog) {
        (homeFeed.catalog + DemoCatalogRepository.all()).distinctBy { it.id }
    }
    val currentPlaybackContent = remember(playbackState.mediaId) {
        playbackState.mediaId.takeIf { it.isNotBlank() }?.let(DemoCatalogRepository::findById)
    }
    val effectiveProgress = if (playbackState.hasMedia && playbackState.totalDurationMs > 0L) {
        PlaybackProgress(
            title = playbackState.displayTitle,
            positionMs = playbackState.currentPositionMs,
            durationMs = playbackState.totalDurationMs,
            contentId = playbackState.mediaId.takeIf { it.isNotBlank() },
            updatedAt = playbackState.lastUpdatedTimestamp,
            seasonNumber = playbackState.seasonNumber,
            episodeNumber = playbackState.episodeNumber,
        )
    } else {
        lastProgress
    }
    val effectiveProgressByTitle = if (playbackState.hasMedia && playbackState.totalDurationMs > 0L) {
        progressByTitle + (playbackState.displayTitle to effectiveProgress)
    } else {
        progressByTitle
    }
    val effectiveProgressByMediaRef = if (playbackState.hasMedia && playbackState.totalDurationMs > 0L) {
        progressByMediaRef + (
            MediaRef(
                playbackState.mediaId,
                playbackState.seasonNumber,
                playbackState.episodeNumber,
            ).storageKey to effectiveProgress
        )
    } else {
        progressByMediaRef
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var clearHistorySnackbarJob by remember { mutableStateOf<Job?>(null) }
    var navigationState by rememberSaveable(stateSaver = moviaNavigationStateSaver) {
        mutableStateOf(MoviaNavigationState())
    }
    val selectedIndex = navigationState.selectedTab.index
    val saveableStateHolder = rememberSaveableStateHolder()
    var catalogLaunchPreset by remember { mutableStateOf<CatalogLaunchPreset?>(null) }
    // Survives the details route so CatalogScreen can restore its pages and grid offset.
    val catalogRetention = remember { CatalogRetentionState() }
    val activeRoute = navigationState.currentRoute
    val activeDetailsRoute = activeRoute as? MoviaRoute.Details
    val activeDetailsTitle = activeDetailsRoute?.title
    val settingsRoute = (activeRoute as? MoviaRoute.Settings)?.page
    val profileOpen = activeRoute == MoviaRoute.Profile
    val fullPlayerOpen = activeRoute == MoviaRoute.Player
    var playerSettingsOpenRequest by remember { mutableIntStateOf(0) }
    var playerSettingsCloseRequest by remember { mutableIntStateOf(0) }
    var agentRuntimeAttached by remember { mutableStateOf(false) }

    fun pushRoute(route: MoviaRoute) {
        navigationState = navigationState.push(route)
    }

    fun popRoute() {
        navigationState = navigationState.pop()
    }

    fun openPlayer() {
        if (playbackState.hasMedia) pushRoute(MoviaRoute.Player)
    }

    fun closePlayer() {
        if (navigationState.currentRoute == MoviaRoute.Player) popRoute()
    }

    val latestAgentNavigation = rememberUpdatedState<(String) -> Unit> { destination ->
        when (destination.lowercase()) {
            "home" -> navigationState = navigationState.navigateToTab(MoviaTopLevel.HOME)
            "catalog" -> navigationState = navigationState.navigateToTab(MoviaTopLevel.CATALOG)
            "library" -> navigationState = navigationState.navigateToTab(MoviaTopLevel.LIBRARY)
            "profile" -> pushRoute(MoviaRoute.Profile)
            "downloads" -> pushRoute(MoviaRoute.Settings(MoviaSettingsPage.DOWNLOADS))
            "catalog:new" -> {
                catalogLaunchPreset = CatalogLaunchPreset.NEW
                navigationState = navigationState.selectTab(MoviaTopLevel.CATALOG)
            }
            "catalog:popular" -> {
                catalogLaunchPreset = CatalogLaunchPreset.POPULAR
                navigationState = navigationState.selectTab(MoviaTopLevel.CATALOG)
            }
            "catalog:all" -> {
                catalogLaunchPreset = CatalogLaunchPreset.ALL
                navigationState = navigationState.selectTab(MoviaTopLevel.CATALOG)
            }
            "back" -> popRoute()
        }
    }

    LaunchedEffect(context.applicationContext) {
        withFrameNanos { }
        DemoCatalogRepository.prewarm()
        AgentControlRuntime.start(context.applicationContext)
        AgentControlRuntime.registerUiHandlers(
            navigate = { destination -> latestAgentNavigation.value(destination) },
        )
        AgentControlRuntime.registerPlayerSettingsHandlers(
            open = {
                openPlayer()
                playerSettingsOpenRequest++
            },
            close = { playerSettingsCloseRequest++ },
            enterFullscreen = { openPlayer() },
            exitFullscreen = { closePlayer() },
            enterPip = {
                val activity = context.findActivity()
                if (activity != null && playbackState.hasMedia) {
                    runCatching {
                        activity.enterPictureInPictureMode(
                            buildMoviaPictureInPictureParams(
                                context = context.applicationContext,
                                sourceRectHint = null,
                                isPlaying = playbackState.isPlaying,
                                title = playbackState.displayTitle,
                                autoEnter = false,
                            ),
                        )
                    }
                }
            },
        )
        AgentControlRuntime.updateForeground(true)
        agentRuntimeAttached = true
    }
    DisposableEffect(Unit) {
        onDispose { AgentControlRuntime.clearUiHandlers() }
    }
    LaunchedEffect(
        agentRuntimeAttached,
        navigationState,
        playbackState.hasMedia,
        MoviaPiPState.isInPictureInPicture,
    ) {
        if (!agentRuntimeAttached) return@LaunchedEffect
        val playerOpen = fullPlayerOpen && playbackState.hasMedia
        val screen = when {
            playerOpen -> "PLAYER"
            activeDetailsTitle != null -> "DETAILS"
            settingsRoute != null -> "SETTINGS"
            profileOpen -> "PROFILE"
            selectedIndex == 1 -> "CATALOG"
            selectedIndex == 2 -> "LIBRARY"
            else -> "HOME"
        }
        AgentControlRuntime.updateNavigation(screen, playerOpen, settingsRoute != null)
        AgentControlRuntime.updatePresentation(
            fullscreen = playerOpen && !MoviaPiPState.isInPictureInPicture,
            pip = MoviaPiPState.isInPictureInPicture,
        )
    }

    val persistActiveProgress: () -> Unit = {
        val state = playbackSession.state.value
        if (state.hasMedia && state.currentPositionMs >= 0L && state.totalDurationMs > 0L) {
            scope.launch {
                libraryRepository.saveProgress(
                    MediaRef(state.mediaId, state.seasonNumber, state.episodeNumber),
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
        closePlayer()
    }

    val startPlaybackForMedia: (MediaRef?, String) -> Unit = { requestedMediaRef, title ->
        if (title.isNotBlank()) {
            pendingPlaybackJob?.cancel()
            pendingPlaybackJob = scope.launch {
                val baseTitle = playbackBaseTitle(title)
                val content = withContext(Dispatchers.IO) {
                    val cached = requestedMediaRef?.contentId
                        ?.let(DemoCatalogRepository::findById)
                        ?: DemoCatalogRepository.findByTitle(baseTitle)
                    if (cached != null && (cached.streams.isNotEmpty() || !cached.playbackUrl.isNullOrBlank())) {
                        cached
                    } else if (requestedMediaRef != null) {
                        DemoCatalogRepository.findFullById(requestedMediaRef.contentId) ?: cached
                    } else {
                        DemoCatalogRepository.findFullByTitle(baseTitle) ?: cached
                    }
                }
                val mediaRef = requestedMediaRef
                    ?: content?.let { resolved -> MediaRef.from(resolved.id, title) ?: MediaRef(resolved.id) }
                    ?: return@launch
                libraryRepository.addHistory(mediaRef, title)
                val isExplicitlyDownloaded = downloads.contains(title) || downloads.contains(baseTitle)
                val localSource = if (isExplicitlyDownloaded) {
                    (
                        DownloadScheduler.localFile(
                            context.applicationContext,
                            mediaRef,
                            title,
                        )
                            ?: DownloadScheduler.localFile(
                                context.applicationContext,
                                MediaRef(mediaRef.contentId),
                                baseTitle,
                            )
                    )?.toURI()?.toString()
                } else null
                val progressKey = mediaRef.storageKey
                val saved = progressByMediaRef[progressKey]
                    ?: progressByTitle[title]
                    ?: lastProgress.takeIf { it.mediaRef == mediaRef || it.title == title }
                val sortedStreams = content?.streams.orEmpty().sortedWith(
                    compareBy<app.movia.android.domain.model.StreamOption> { option ->
                        val v = option.voice.lowercase()
                        when {
                            v.contains("дубляж") || v.contains("дублированный") -> 0
                            v.contains("lostfilm") -> 1
                            v.contains("red head sound") || v.contains("rhs") -> 2
                            v.contains("hdrezka") || v.contains("rezka") -> 3
                            v.contains("кубик") -> 4
                            v.contains("кураж") -> 5
                            v.contains("newstudio") -> 6
                            v.contains("профессиональн") -> 7
                            v.contains("русск") -> 8
                            v.contains("original") || v.contains("english") -> 20
                            else -> 10
                        }
                    }.thenByDescending { it.seeders }
                )
                val streamCandidates = sortedStreams.mapNotNull { it.url.takeIf { u -> u.isNotBlank() } }
                val preferredSource = streamCandidates.firstOrNull() ?: content?.playbackUrl
                playbackSession.start(
                    mediaId = mediaRef.contentId,
                    title = title,
                    seasonNumber = mediaRef.season,
                    episodeNumber = mediaRef.episode,
                    sourceUri = localSource ?: preferredSource,
                    startPositionMs = saved?.positionMs ?: 0L,
                    audioTrackId = playbackPreferences.audio,
                    subtitleTrackId = if (playbackPreferences.subtitlesEnabled) "Auto" else null,
                    candidateStreams = streamCandidates,
                )
                openPlayer()
            }
        }
    }
    LaunchedEffect(playbackSession, libraryRepository) {
        var lastPersistedPositionMs = -1L
        while (true) {
            delay(10_000L)
            val state = playbackSession.state.value
            val advancedEnough = lastPersistedPositionMs < 0L ||
                kotlin.math.abs(state.currentPositionMs - lastPersistedPositionMs) >= 5_000L
            if (state.hasMedia && state.isPlaying && advancedEnough &&
                state.currentPositionMs >= 0L && state.totalDurationMs > 0L
            ) {
                libraryRepository.saveProgress(
                    MediaRef(state.mediaId, state.seasonNumber, state.episodeNumber),
                    state.displayTitle,
                    state.currentPositionMs,
                    state.totalDurationMs,
                    state.lastUpdatedTimestamp,
                )
                lastPersistedPositionMs = state.currentPositionMs
            }
        }
    }
    LaunchedEffect(playbackState.hasMedia, playbackState.isPlaying) {
        if (playbackState.hasMedia && !playbackState.isPlaying && playbackState.totalDurationMs > 0L) {
            persistActiveProgress()
        }
    }

    if (fullPlayerOpen && playbackState.hasMedia) {
        val title = playbackState.displayTitle
        val baseTitle = playbackBaseTitle(title)
        val playbackMediaRef = MediaRef(
            playbackState.mediaId,
            playbackState.seasonNumber,
            playbackState.episodeNumber,
        )
        val seasonEpisodeCounts = currentPlaybackContent?.seasonEpisodeCounts.orEmpty()
        val previousEpisodeRef = playbackMediaRef.previousEpisode(seasonEpisodeCounts)
        val nextEpisodeRef = playbackMediaRef.nextEpisode(seasonEpisodeCounts)
        val titlePreferencesFlow = remember(baseTitle, preferencesRepository) {
            preferencesRepository.titlePlaybackPreferences(baseTitle)
        }
        val titlePreferences by titlePreferencesFlow.collectAsStateWithLifecycle(initialValue = TitlePlaybackPreferences())
        val resolvedAudio = titlePreferences.audio ?: playbackPreferences.audio
        val resolvedQuality = titlePreferences.quality ?: playbackPreferences.quality

        PlayerScreen(
            session = playbackSession,
            title = title,
            mediaContent = currentPlaybackContent,
            onMinimize = { persistActiveProgress(); closePlayer() },
            onBack = { persistActiveProgress(); closePlayer() },
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
            openSettingsRequest = playerSettingsOpenRequest,
            closeSettingsRequest = playerSettingsCloseRequest,
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
            hasPreviousEpisode = previousEpisodeRef != null,
            hasNextEpisode = nextEpisodeRef != null,
            onPreviousEpisode = {
                previousEpisodeRef?.let { ref ->
                    startPlaybackForMedia(ref, episodeDisplayTitle(baseTitle, ref))
                }
            },
            onNextEpisode = {
                nextEpisodeRef?.let { ref ->
                    startPlaybackForMedia(ref, episodeDisplayTitle(baseTitle, ref))
                }
            },
            onSelectEpisode = { season, episode ->
                val exact = playbackMediaRef.copy(season = season, episode = episode)
                startPlaybackForMedia(exact, episodeDisplayTitle(baseTitle, exact))
            },
            onAutoNextChanged = { value ->
                scope.launch { preferencesRepository.setAutoNextEnabled(value) }
            },
            onPersistentSeekButtonsChanged = { value ->
                scope.launch { preferencesRepository.setPersistentSeekButtons(value) }
            },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    val miniVisible = playbackState.hasMedia
    val contentBottomPadding = if (miniVisible) 76.dp else 0.dp

    if (activeDetailsTitle != null) {
        val title = activeDetailsTitle
        val titlePreferencesFlow = remember(title, preferencesRepository) {
            preferencesRepository.titlePlaybackPreferences(title)
        }
        val titlePreferences by titlePreferencesFlow.collectAsStateWithLifecycle(initialValue = TitlePlaybackPreferences())
        val resolvedAudio = titlePreferences.audio ?: playbackPreferences.audio
        val resolvedQuality = titlePreferences.quality ?: playbackPreferences.quality
        val mediaId = activeDetailsRoute?.mediaId.orEmpty()
        val inMyList = mediaId in favoriteContentIds || title in favorites

        val toggleDownload: (MediaRef?, String) -> Unit = { requestedRef, target ->
            val mediaRef = requestedRef
                ?: mediaId.takeIf { it.isNotBlank() }?.let { MediaRef(it) }
            val isDownloaded = mediaRef?.let { ref ->
                if (downloadRecords.isEmpty()) target in downloads
                else downloadRecords.any { it.mediaRef == ref }
            } ?: (target in downloads)
            if (isDownloaded) {
                val deleted = mediaRef?.let { ref ->
                    DownloadScheduler.delete(context.applicationContext, ref, target)
                } ?: DownloadScheduler.delete(context.applicationContext, target, mediaId)
                if (deleted) {
                    scope.launch {
                        if (mediaRef != null) libraryRepository.setDownloaded(mediaRef, target, false)
                        else libraryRepository.setDownloaded(mediaId, target, false)
                    }
                }
            } else if (mediaRef != null) {
                DownloadScheduler.enqueue(
                    context = context.applicationContext,
                    mediaRef = mediaRef,
                    title = target,
                    wifiOnly = playbackPreferences.wifiOnlyDownloads,
                )
            } else {
                DownloadScheduler.enqueue(
                    context = context.applicationContext,
                    title = target,
                    wifiOnly = playbackPreferences.wifiOnlyDownloads,
                    contentId = activeDetailsRoute?.mediaId,
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            DetailsScreen(
                mediaId = activeDetailsRoute?.mediaId.orEmpty(),
                fallbackTitle = title,
                onBack = { popRoute() },
                onPlay = { mediaRef, playTitle -> startPlaybackForMedia(mediaRef, playTitle) },
                onOpenDetails = { relatedMediaId, relatedTitle ->
                    pushRoute(MoviaRoute.Details(relatedMediaId, relatedTitle))
                },
                inMyList = inMyList,
                onMyListChange = { enabled ->
                    scope.launch {
                        setFavorite(activeDetailsRoute?.mediaId.orEmpty(), title, enabled)
                    }
                },
                downloads = downloads,
                downloadRecords = downloadRecords,
                onDownloadTitle = toggleDownload,
                progressByTitle = effectiveProgressByTitle,
                progressByMediaRef = effectiveProgressByMediaRef,
                latestProgress = effectiveProgress,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = contentBottomPadding),
            )
            if (miniVisible) {
                MiniPlayerBar(
                    session = playbackSession,
                    mediaContent = currentPlaybackContent,
                    onOpen = { openPlayer() },
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
        val closeSettings = { popRoute() }
        val settingsModifier = Modifier
            .fillMaxSize()
            .padding(bottom = contentBottomPadding)
        Box(modifier = Modifier.fillMaxSize()) {
            when (route) {
                MoviaSettingsPage.DOWNLOADS -> DownloadsSettingsScreen(
                    preferences = playbackPreferences,
                    downloads = downloadRecords,
                    onBack = closeSettings,
                    onWifiOnlyChanged = { value ->
                        scope.launch { preferencesRepository.setWifiOnlyDownloads(value) }
                    },
                    onDeleteTitle = { mediaRef, title ->
                        val deleted = mediaRef?.let { ref ->
                            DownloadScheduler.delete(context.applicationContext, ref, title)
                        } ?: DownloadScheduler.delete(context.applicationContext, title)
                        if (deleted) {
                            scope.launch {
                                if (mediaRef != null) libraryRepository.setDownloaded(mediaRef, title, false)
                                else libraryRepository.setDownloaded(title, false)
                            }
                        }
                    },
                    onDeleteAll = {
                        if (DownloadScheduler.deleteAll(context.applicationContext)) {
                            scope.launch { libraryRepository.clearDownloads() }
                        }
                    },
                    modifier = settingsModifier,
                )
                MoviaSettingsPage.HELP -> HelpSettingsScreen(
                    onBack = closeSettings,
                    modifier = settingsModifier,
                )
            }
            if (miniVisible) {
                MiniPlayerBar(
                    session = playbackSession,
                    mediaContent = currentPlaybackContent,
                    onOpen = { openPlayer() },
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
            preferencesRepository = preferencesRepository,
            downloadedCount = downloadRecords.size,
            modifier = Modifier.fillMaxSize(),
            onBack = { popRoute() },
            onOpenSettings = { page -> pushRoute(MoviaRoute.Settings(page)) },
        )
        return
    }

    val openDetails: (String, String) -> Unit = { mediaId, title ->
        pushRoute(MoviaRoute.Details(mediaId, title))
    }

    val screenContent: @Composable (PaddingValues) -> Unit = { innerPadding ->
        when (selectedIndex) {
            0 -> HomeScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                progress = effectiveProgress,
                history = history,
                favorites = favorites,
                favoriteContentIds = favoriteContentIds,
                onOpenDetails = openDetails,
                onContinue = { mediaId, title ->
                    startPlaybackForMedia(mediaId.takeIf { it.isNotBlank() }?.let { MediaRef(it) }, title)
                },
                onToggleFavorite = { mediaId, title, enabled ->
                    scope.launch { setFavorite(mediaId, title, enabled) }
                },
                onOpenCatalog = { preset ->
                    catalogLaunchPreset = preset
                    navigationState = navigationState.selectTab(MoviaTopLevel.CATALOG)
                },
            )
            1 -> CatalogScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                launchPreset = catalogLaunchPreset,
                onLaunchPresetConsumed = { catalogLaunchPreset = null },
                retention = catalogRetention,
                history = history,
                favorites = favorites,
                recentQueries = recentSearches,
                onSearchCommitted = { query -> scope.launch { libraryRepository.addSearchQuery(query) } },
                onClearRecent = { scope.launch { libraryRepository.clearSearchHistory() } },
                onOpenDetails = openDetails,
                onOpenProfile = { pushRoute(MoviaRoute.Profile) },
            )
            2 -> LibraryScreen(
                modifier = Modifier.fillMaxSize(),
                contentPadding = innerPadding,
                favorites = favorites,
                favoriteRecords = favoriteRecords,
                history = history,
                historyRecords = historyRecords,
                downloads = downloads,
                downloadRecords = downloadRecords,
                catalog = libraryCatalog,
                progressByTitle = effectiveProgressByTitle,
                progressByMediaRef = effectiveProgressByMediaRef,
                resumeHeroState = resumeHeroState,
                onContinue = { mediaRef, title -> startPlaybackForMedia(mediaRef, title) },
                onOpenDetails = openDetails,
                onOpenCatalog = {
                    navigationState = navigationState.selectTab(MoviaTopLevel.CATALOG)
                },
                onOpenSettings = { pushRoute(MoviaRoute.Profile) },
                onOpenDownloads = { pushRoute(MoviaRoute.Settings(MoviaSettingsPage.DOWNLOADS)) },
                onClearHistory = { snapshot ->
                    clearHistorySnackbarJob?.cancel()
                    snackbarHostState.currentSnackbarData?.dismiss()
                    clearHistorySnackbarJob = scope.launch {
                        libraryRepository.clearHistory()
                        val result = withTimeoutOrNull(2_000L) {
                            snackbarHostState.showSnackbar(
                                message = "История очищена",
                                actionLabel = "Отменить",
                                withDismissAction = true,
                                duration = SnackbarDuration.Indefinite,
                            )
                        }
                        snackbarHostState.currentSnackbarData?.dismiss()
                        if (result == SnackbarResult.ActionPerformed) {
                            libraryRepository.restoreHistory(snapshot)
                        }
                        clearHistorySnackbarJob = null
                    }
                },
            )
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .mapTestTagsToResourceIds(),
    ) {
        if (maxWidth >= 600.dp) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    topLevelDestinations.forEachIndexed { index, destination ->
                        val selected = selectedIndex == index
                        NavigationRailItem(
                            modifier = Modifier.testTag(topLevelControlTag(index)),
                            selected = selected,
                            onClick = {
                                navigationState = navigationState.selectTab(MoviaTopLevel.fromIndex(index))
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = destination.label,
                                )
                            },
                            label = { Text(destination.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MoviaBrandAmber,
                                selectedTextColor = MoviaBrandAmber,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        saveableStateHolder.SaveableStateProvider("top-level-$selectedIndex") {
                            screenContent(WindowInsets.safeDrawing.asPaddingValues())
                        }
                    }
                    if (miniVisible) {
                        MiniPlayerBar(
                            session = playbackSession,
                            mediaContent = currentPlaybackContent,
                            onOpen = { openPlayer() },
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
            val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomNavHazeState = remember { HazeState() }
            val appNavHeight = 64.dp
            val appNavSystemGap = 8.dp
            val navHeight = appNavHeight + appNavSystemGap + systemBottom
            val miniPlayerHeight = if (miniVisible) 76.dp else 0.dp
            // Every top-level scroll surface must be able to move its final row fully
            // above the fixed navigation stack. The 16dp breathing room is part of the
            // contract, not an ad-hoc per-screen spacer.
            val scrollContentPadding = PaddingValues(
                top = 0.dp,
                bottom = 16.dp,
            )
            // All compact top-level destinations share the approved Home glass-over-scroll
            // navigation treatment. Geometry of the navigation itself is unchanged.
            val topLevelContentPadding = PaddingValues(
                top = statusBarTop,
                bottom = navHeight + 16.dp,
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Keep each destination's existing top inset contract, but let every
                        // top-level screen paint behind the same floating glass navigation as Home.
                        .padding(bottom = miniPlayerHeight)
                        .hazeSource(state = bottomNavHazeState),
                ) {
                    saveableStateHolder.SaveableStateProvider("top-level-$selectedIndex") {
                        screenContent(topLevelContentPadding)
                    }
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
                            mediaContent = currentPlaybackContent,
                            onOpen = { openPlayer() },
                            onClose = closePlayback,
                        )
                    }
                    MoviaBottomNavigation(
                        selectedIndex = selectedIndex,
                        hazeState = bottomNavHazeState,
                        onSelected = {
                            navigationState = navigationState.selectTab(MoviaTopLevel.fromIndex(it))
                        },
                    )
                    // Android navigation buttons live on their own surface below the Movia glass.
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(appNavSystemGap + systemBottom)
                            .background(MaterialTheme.colorScheme.background),
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

@Composable
private fun MoviaBottomNavigation(
    selectedIndex: Int,
    hazeState: HazeState,
    onSelected: (Int) -> Unit,
) {
    val activeColor = MoviaBrandAmber
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .padding(horizontal = 14.dp)
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(28.dp))
            .hazeEffect(
                state = hazeState,
                style = HazeStyle(
                    backgroundColor = MaterialTheme.colorScheme.surface,
                    tint = HazeTint(MoviaNavGlassSurface),
                    blurRadius = 40.dp,
                    noiseFactor = 0f,
                ),
            ) {
                blurEnabled = true
            }
            .drawBehind {
                drawRoundRect(
                    color = MoviaNavTopBorder,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(28.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .zIndex(1000f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            topLevelDestinations.forEachIndexed { index, destination ->
                val selected = selectedIndex == index
                val isLibrary = destination.moviaIcon == MoviaNavIcon.LIBRARY
                val iconColor = if (selected) activeColor else inactiveColor
                val labelColor = if (selected) activeColor else inactiveColor
                val navInteractionSource = remember { MutableInteractionSource() }
                val glowAlpha by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(durationMillis = 200),
                    label = "bottomNavGlowAlpha",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .testTag(topLevelControlTag(index))
                        .clickable(
                            interactionSource = navInteractionSource,
                            indication = null,
                        ) { onSelected(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(
                            modifier = Modifier
                                .requiredSize(56.dp)
                                .blur(
                                    radius = 18.dp,
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                ),
                        ) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colorStops = arrayOf(
                                        0.00f to MoviaBrandAmber.copy(alpha = 0.30f * glowAlpha),
                                        0.45f to MoviaBrandAmber.copy(alpha = 0.14f * glowAlpha),
                                        1.00f to MoviaBrandAmber.copy(alpha = 0f),
                                    ),
                                    center = center,
                                    radius = size.minDimension / 2f,
                                ),
                            )
                        }
                        MoviaBottomNavIcon(
                            kind = destination.moviaIcon,
                            selected = selected,
                            color = iconColor,
                            modifier = Modifier.size(29.dp),
                        )
                    }
                    Text(
                        modifier = Modifier.offset(y = (-3).dp),
                        text = destination.label,
                        color = labelColor,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        letterSpacing = 0.sp,
                        maxLines = 1,
                    )
                }
            }
        }
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
                drawPath(path, color)
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
                // Solid profile silhouette, matching the reference bottom navigation.
                val cx = 13f * u
                drawCircle(
                    color = color,
                    radius = 4.25f * u,
                    center = androidx.compose.ui.geometry.Offset(cx, 8.0f * u),
                )
                val body = Path().apply {
                    moveTo(5.0f * u, 21.3f * u)
                    cubicTo(5.2f * u, 15.9f * u, 8.4f * u, 13.0f * u, 13f * u, 13.0f * u)
                    cubicTo(17.6f * u, 13.0f * u, 20.8f * u, 15.9f * u, 21.0f * u, 21.3f * u)
                    close()
                }
                drawPath(body, color)
            }
        }
    }
}
