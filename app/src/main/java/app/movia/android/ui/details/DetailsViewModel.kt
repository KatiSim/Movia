package app.movia.android.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.MediaDetailsBundle
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailsUiState(
    val mediaId: String = "",
    val fallbackTitle: String = "",
    val cachedContent: MediaContent? = null,
    val bundle: MediaDetailsBundle? = null,
    val directorPerson: Person? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)

class DetailsViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(DetailsUiState())
    val uiState: StateFlow<DetailsUiState> = mutableUiState.asStateFlow()

    private var loadJob: Job? = null
    private var directorJob: Job? = null
    private var currentKey: Pair<String, String>? = null

    fun load(mediaId: String, fallbackTitle: String, force: Boolean = false) {
        val key = mediaId to fallbackTitle
        if (!force && key == currentKey) return
        currentKey = key
        loadJob?.cancel()

        val cachedContent = mediaId.takeIf { it.isNotBlank() }?.let(DemoCatalogRepository::findById)
            ?: DemoCatalogRepository.findByTitle(fallbackTitle)
        val cachedBundle = cachedContent?.let { DemoCatalogRepository.findCachedDetailsBundle(it.id) }
        val cachedResolvedContent = cachedBundle?.movie ?: cachedContent
        mutableUiState.value = DetailsUiState(
            mediaId = mediaId,
            fallbackTitle = fallbackTitle,
            cachedContent = cachedContent,
            bundle = cachedBundle,
            directorPerson = cachedResolvedContent?.director
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { Person(name = it, role = "Режиссёр") },
            isLoading = cachedContent == null && cachedBundle == null,
            isRefreshing = cachedContent != null && cachedBundle == null,
        )
        resolveDirectorPerson(cachedResolvedContent, key)
        if (cachedBundle != null) return

        loadJob = viewModelScope.launch {
            val bundle = withContext(Dispatchers.IO) {
                try {
                    if (mediaId.isNotBlank()) {
                        DemoCatalogRepository.getDetailsBundleById(mediaId)
                    } else {
                        DemoCatalogRepository.getDetailsBundleByTitle(fallbackTitle)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            val resolvedContent = bundle?.movie ?: cachedContent
            mutableUiState.value = mutableUiState.value.copy(
                bundle = bundle,
                cachedContent = resolvedContent,
                isLoading = false,
                isRefreshing = false,
                errorMessage = if (bundle == null) "Не удалось загрузить информацию" else null,
            )
            resolveDirectorPerson(resolvedContent, key)
        }
    }

    private fun resolveDirectorPerson(
        content: MediaContent?,
        key: Pair<String, String>,
    ) {
        val directorName = content?.director?.trim().orEmpty()
        directorJob?.cancel()
        if (directorName.isBlank()) {
            mutableUiState.value = mutableUiState.value.copy(directorPerson = null)
            return
        }

        directorJob = viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                try {
                    DemoCatalogRepository.getPersonProjects(
                        name = directorName,
                        creditScope = "director",
                        limit = 1,
                    ).person
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (currentKey != key) return@launch
            mutableUiState.value = mutableUiState.value.copy(
                directorPerson = resolved?.copy(role = "Режиссёр")
                    ?: Person(name = directorName, role = "Режиссёр"),
            )
        }
    }

    fun retry() {
        val state = mutableUiState.value
        load(state.mediaId, state.fallbackTitle, force = true)
    }
}
