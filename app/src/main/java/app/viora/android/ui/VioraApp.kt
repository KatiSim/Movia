package app.viora.android.ui

import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import app.viora.android.data.preferences.PlaybackPreferences
import app.viora.android.data.preferences.VioraPreferencesRepository
import app.viora.android.ui.catalog.CatalogScreen
import app.viora.android.ui.details.DetailsScreen
import app.viora.android.ui.home.HomeScreen
import app.viora.android.ui.library.LibraryScreen
import app.viora.android.ui.player.PlayerScreen
import app.viora.android.ui.profile.ProfileScreen
import app.viora.android.ui.search.SearchScreen
import app.viora.android.ui.settings.PlaybackSettingsScreen
import app.viora.android.ui.theme.VioraTheme
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

@Composable
fun VioraApp() {
    VioraTheme {
        val context = LocalContext.current
        val preferencesRepository = remember(context) {
            VioraPreferencesRepository(context.applicationContext)
        }
        val scope = rememberCoroutineScope()
        val playbackPreferences by preferencesRepository.playbackPreferences.collectAsState(
            initial = PlaybackPreferences(),
        )
        val favorites by preferencesRepository.favorites.collectAsState(initial = emptySet())

        var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
        var detailsTitle by rememberSaveable { mutableStateOf<String?>(null) }
        var playTitle by rememberSaveable { mutableStateOf<String?>(null) }
        var playbackSettingsOpen by rememberSaveable { mutableStateOf(false) }

        if (playTitle != null) {
            PlayerScreen(
                title = playTitle.orEmpty(),
                onBack = { playTitle = null },
                modifier = Modifier.fillMaxSize(),
            )
            return@VioraTheme
        }

        if (detailsTitle != null) {
            val title = detailsTitle.orEmpty()
            DetailsScreen(
                title = title,
                onBack = { detailsTitle = null },
                onPlay = { playTitle = it },
                favorite = title in favorites,
                selectedAudio = playbackPreferences.audio,
                selectedQuality = playbackPreferences.quality,
                onFavoriteChange = { favorite ->
                    scope.launch { preferencesRepository.setFavorite(title, favorite) }
                },
                onAudioSelected = { audio ->
                    scope.launch { preferencesRepository.setAudio(audio) }
                },
                onQualitySelected = { quality ->
                    scope.launch { preferencesRepository.setQuality(quality) }
                },
                modifier = Modifier.fillMaxSize(),
            )
            return@VioraTheme
        }

        if (playbackSettingsOpen) {
            PlaybackSettingsScreen(
                preferences = playbackPreferences,
                onBack = { playbackSettingsOpen = false },
                onAudioSelected = { value -> scope.launch { preferencesRepository.setAudio(value) } },
                onQualitySelected = { value -> scope.launch { preferencesRepository.setQuality(value) } },
                onSubtitlesChanged = { value -> scope.launch { preferencesRepository.setSubtitlesEnabled(value) } },
                onAutoNextChanged = { value -> scope.launch { preferencesRepository.setAutoNextEnabled(value) } },
                onWifiOnlyDownloadsChanged = { value -> scope.launch { preferencesRepository.setWifiOnlyDownloads(value) } },
                modifier = Modifier.fillMaxSize(),
            )
            return@VioraTheme
        }

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
            when (selectedIndex) {
                0 -> HomeScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                    onOpenDetails = { detailsTitle = it },
                )
                1 -> CatalogScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                    onOpenDetails = { detailsTitle = it },
                )
                2 -> SearchScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                    onOpenDetails = { detailsTitle = it },
                )
                3 -> LibraryScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                    favorites = favorites,
                )
                4 -> ProfileScreen(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = innerPadding,
                    onOpenPlaybackSettings = { playbackSettingsOpen = true },
                )
            }
        }
    }
}
