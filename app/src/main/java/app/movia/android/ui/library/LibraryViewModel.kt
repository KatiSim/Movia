package app.movia.android.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.movia.android.data.library.LibraryRepository
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.library.LegacyLibrarySnapshot
import app.movia.android.data.preferences.MoviaPreferencesRepository
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.domain.model.LibraryMediaRecord
import app.movia.android.domain.model.MediaContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val favorites: Set<String> = emptySet(),
    val favoriteRecords: List<LibraryMediaRecord> = emptyList(),
    val downloads: Set<String> = emptySet(),
    val downloadRecords: List<LibraryMediaRecord> = emptyList(),
    val history: List<String> = emptyList(),
    val historyRecords: List<LibraryMediaRecord> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val lastProgress: PlaybackProgress = PlaybackProgress(),
    val progressByTitle: Map<String, PlaybackProgress> = emptyMap(),
    val progressByMediaRef: Map<String, PlaybackProgress> = emptyMap(),
)

private data class LibraryCoreState(
    val favorites: Set<String>,
    val downloads: Set<String>,
    val history: List<String>,
)

private data class LibraryRecordState(
    val favorites: List<LibraryMediaRecord>,
    val downloads: List<LibraryMediaRecord>,
    val history: List<LibraryMediaRecord>,
)

private data class LibraryProgressState(
    val latest: PlaybackProgress,
    val byTitle: Map<String, PlaybackProgress>,
    val byMediaRef: Map<String, PlaybackProgress>,
)

sealed interface ResumeHeroState {
    data object None : ResumeHeroState
    data class Fallback(val progress: PlaybackProgress) : ResumeHeroState
    data class Ready(val progress: PlaybackProgress, val item: MediaContent) : ResumeHeroState
}

class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    val repository = LibraryRepository(application.applicationContext)

    private val coreState = combine(
        repository.favorites,
        repository.downloads,
        repository.history,
    ) { favorites, downloads, history ->
        LibraryCoreState(favorites, downloads, history)
    }

    private val recordState = combine(
        repository.favoriteRecords,
        repository.downloadRecords,
        repository.historyRecords,
    ) { favorites, downloads, history ->
        LibraryRecordState(favorites, downloads, history)
    }

    private val progressState = combine(
        repository.lastProgress,
        repository.progressByTitle,
        repository.progressByMediaRef,
    ) { latest, byTitle, byMediaRef ->
        LibraryProgressState(latest, byTitle, byMediaRef)
    }

    private val mutableResumeHeroState = MutableStateFlow<ResumeHeroState>(ResumeHeroState.None)
    val resumeHeroState: StateFlow<ResumeHeroState> = mutableResumeHeroState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(progressState, DemoCatalogRepository.homeFeed) { progress, feed ->
                progress to feed.lastUpdatedMs
            }.collectLatest { (progressState, _) ->
                val progress = latestUnfinishedProgress(progressState)
                if (progress == null) {
                    mutableResumeHeroState.value = ResumeHeroState.None
                    return@collectLatest
                }

                val baseTitle = progress.title
                    .substringBefore(" · S")
                    .substringBefore(" · E")
                    .trim()
                val cached = progress.contentId?.takeIf { it.isNotBlank() }
                    ?.let(DemoCatalogRepository::findById)
                    ?: DemoCatalogRepository.findByTitle(baseTitle)
                if (cached != null) {
                    mutableResumeHeroState.value = ResumeHeroState.Ready(progress, cached)
                    return@collectLatest
                }

                // Persistent progress owns Hero existence. Metadata may resolve later.
                mutableResumeHeroState.value = ResumeHeroState.Fallback(progress)
                val resolved = withContext(Dispatchers.IO) {
                    progress.contentId?.takeIf { it.isNotBlank() }
                        ?.let { DemoCatalogRepository.findFullById(it) }
                        ?: DemoCatalogRepository.findFullByTitle(baseTitle)
                }
                mutableResumeHeroState.value = if (resolved != null) {
                    ResumeHeroState.Ready(progress, resolved)
                } else {
                    ResumeHeroState.Fallback(progress)
                }
            }
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        coreState,
        recordState,
        repository.recentSearches,
        progressState,
    ) { core, records, recentSearches, progress ->
        LibraryUiState(
            favorites = core.favorites,
            favoriteRecords = records.favorites,
            downloads = core.downloads,
            downloadRecords = records.downloads,
            history = core.history,
            historyRecords = records.history,
            recentSearches = recentSearches,
            lastProgress = progress.latest,
            progressByTitle = progress.byTitle,
            progressByMediaRef = progress.byMediaRef,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), LibraryUiState())

    private var migrationStarted = false

    fun initialize(preferencesRepository: MoviaPreferencesRepository) {
        if (migrationStarted) return
        migrationStarted = true
        viewModelScope.launch {
            if (preferencesRepository.needsRoomLibraryMigration()) {
                val legacy: LegacyLibrarySnapshot = preferencesRepository.readLegacyLibrarySnapshot()
                repository.importLegacy(legacy)
                preferencesRepository.finishRoomLibraryMigration()
            }
            repository.migrateWatchLaterToFavorites()
            // Canonical-ID backfill must see the persisted media cache, not depend on a visited tab.
            DemoCatalogRepository.awaitLocalCacheReady()
            repository.backfillCanonicalContentIds()
        }
    }
    private fun latestUnfinishedProgress(state: LibraryProgressState): PlaybackProgress? =
        (state.byMediaRef.ifEmpty { state.byTitle }).values
            .asSequence()
            .filter { progress ->
                progress.title.isNotBlank() &&
                    progress.positionMs > 0L &&
                    progress.durationMs > 0L &&
                    progress.positionMs < progress.durationMs
            }
            .maxByOrNull { it.updatedAt }

}
