package app.movia.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.SearchStatus
import app.movia.android.domain.model.MediaContent
import app.movia.android.domain.model.Person
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val results: List<MediaContent> = emptyList(),
    val people: List<Person> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class SearchViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = mutableUiState.asStateFlow()
    val popularItems: StateFlow<List<MediaContent>> = DemoCatalogRepository.homeFeed
        .map { it.popular.take(8) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private var searchJob: Job? = null

    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        if (query.isBlank()) {
            mutableUiState.value = SearchUiState()
            return
        }

        mutableUiState.value = SearchUiState(query = query, isLoading = true)
        searchJob = viewModelScope.launch {
            delay(300L)
            try {
                val response = withContext(Dispatchers.IO) {
                    DemoCatalogRepository.searchDetailed(query, limit = 20, discover = true)
                }
                val error = if (response.status in setOf(
                        SearchStatus.OK,
                        SearchStatus.NO_RESULTS,
                        SearchStatus.EMPTY_QUERY,
                    )
                ) null else response.errorMessage ?: "Не удалось выполнить поиск. Проверьте подключение и повторите запрос."
                mutableUiState.value = SearchUiState(
                    query = query,
                    results = response.items,
                    people = response.people,
                    errorMessage = error,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.value = SearchUiState(
                    query = query,
                    errorMessage = "Не удалось выполнить поиск. Проверьте подключение и повторите запрос.",
                )
            }
        }
    }
}
