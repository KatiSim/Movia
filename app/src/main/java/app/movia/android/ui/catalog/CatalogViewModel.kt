package app.movia.android.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.movia.android.data.catalog.CatalogFilter
import app.movia.android.data.catalog.CatalogLoadQuery
import app.movia.android.data.catalog.CatalogPagingSnapshot
import app.movia.android.data.catalog.CatalogPagingSource
import app.movia.android.data.catalog.CatalogSort
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.HomeFeedSnapshot
import app.movia.android.data.catalog.RecommendationEngine
import app.movia.android.data.catalog.SearchStatus
import app.movia.android.domain.model.CatalogCategory
import app.movia.android.domain.model.MediaContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogUiState(
    val requestKey: String = "",
    val searchResults: List<MediaContent> = emptyList(),
    val totalCount: Int = 0,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val nextOffset: Int = 0,
    val errorMessage: String? = null,
)

data class CatalogLoadRequest(
    val requestKey: String,
    val sort: CatalogSort,
    val category: CatalogCategory?,
    val filter: CatalogFilter,
    val query: String?,
    val recommendedIds: Set<String>,
)

private data class ActivePagingRequest(
    val request: CatalogLoadRequest,
    val restored: CatalogPagingSnapshot? = null,
    val generation: Long,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(CatalogUiState())
    val uiState: StateFlow<CatalogUiState> = mutableUiState.asStateFlow()
    val homeFeed: StateFlow<HomeFeedSnapshot> = DemoCatalogRepository.homeFeed
    private val mutableRecommendationIds = MutableStateFlow<Set<String>?>(emptySet())
    val recommendationIds: StateFlow<Set<String>?> = mutableRecommendationIds.asStateFlow()

    private data class RecommendationRequest(
        val history: List<String>,
        val favorites: Set<String>,
    )

    private var recommendationRequest: RecommendationRequest? = null
    private var recommendationJob: Job? = null

    fun updateRecommendations(history: List<String>, favorites: Set<String>, enabled: Boolean) {
        val request = if (enabled) RecommendationRequest(history.toList(), favorites.toSet()) else null
        if (request == recommendationRequest) return
        recommendationRequest = request
        recommendationJob?.cancel()
        if (request == null) {
            mutableRecommendationIds.value = emptySet()
            return
        }

        mutableRecommendationIds.value = null
        recommendationJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    RecommendationEngine.recommend(
                        history = request.history,
                        favorites = request.favorites,
                    )
                }
                if (recommendationRequest == request) {
                    mutableRecommendationIds.value = result.items.mapTo(linkedSetOf()) { it.id }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (recommendationRequest == request) mutableRecommendationIds.value = emptySet()
            }
        }
    }

    suspend fun findCachedItems(ids: List<String>): List<MediaContent> = withContext(Dispatchers.IO) {
        ids.mapNotNull(DemoCatalogRepository::findById)
    }

    private val pagingRequest = MutableStateFlow<ActivePagingRequest?>(null)
    val pagedCatalog: Flow<PagingData<MediaContent>> = pagingRequest
        .flatMapLatest { active ->
            if (active == null) {
                flowOf(PagingData.empty())
            } else {
                val request = active.request
                Pager(
                    config = PagingConfig(
                        pageSize = CatalogPagingSource.PAGE_SIZE,
                        initialLoadSize = CatalogPagingSource.PAGE_SIZE,
                        prefetchDistance = 6,
                        enablePlaceholders = false,
                    ),
                    pagingSourceFactory = {
                        CatalogPagingSource(
                            repository = DemoCatalogRepository,
                            query = CatalogLoadQuery(
                                sort = request.sort,
                                category = request.category,
                                filter = request.filter,
                                recommendedIds = request.recommendedIds,
                                query = request.query,
                            ),
                            restored = active.restored,
                            onProgress = { progress ->
                                mutableUiState.update { current ->
                                    if (current.requestKey != request.requestKey ||
                                        pagingRequest.value?.generation != active.generation
                                    ) current else current.copy(
                                        totalCount = progress.totalCount,
                                        nextOffset = progress.nextOffset,
                                        hasMore = progress.hasMore,
                                        isLoading = false,
                                        errorMessage = progress.warning,
                                    )
                                }
                            },
                        )
                    },
                ).flow
            }
        }
        .cachedIn(viewModelScope)

    private var searchJob: Job? = null
    private var currentRequest: CatalogLoadRequest? = null
    private var loadedRequestKey: String? = null
    private var pagingGeneration: Long = 0L

    suspend fun catalogResultCount(
        category: CatalogCategory?,
        filter: CatalogFilter,
        query: String?,
    ): Int = withContext(Dispatchers.IO) {
        DemoCatalogRepository.getCatalogPage(
            limit = 1,
            category = category,
            filter = filter,
            query = query,
        ).total
    }

    fun restore(
        request: CatalogLoadRequest,
        items: List<MediaContent>,
        totalCount: Int,
        hasMore: Boolean,
        nextOffset: Int = items.size,
    ) {
        searchJob?.cancel()
        currentRequest = request
        loadedRequestKey = request.requestKey
        mutableUiState.value = CatalogUiState(
            requestKey = request.requestKey,
            totalCount = totalCount,
            hasMore = hasMore,
            nextOffset = nextOffset,
        )
        pagingRequest.value = ActivePagingRequest(
            request = request,
            restored = CatalogPagingSnapshot(items, totalCount, nextOffset, hasMore),
            generation = ++pagingGeneration,
        )
    }

    fun loadFirstPage(request: CatalogLoadRequest, force: Boolean = false) {
        currentRequest = request
        if (!force && loadedRequestKey == request.requestKey && mutableUiState.value.requestKey == request.requestKey) {
            return
        }
        searchJob?.cancel()
        loadedRequestKey = request.requestKey

        mutableUiState.value = CatalogUiState(requestKey = request.requestKey, isLoading = true)
        if (!request.query.isNullOrBlank()) {
            pagingRequest.value = null
            searchJob = viewModelScope.launch {
                delay(300L)
                if (currentRequest?.requestKey == request.requestKey) {
                    pagingRequest.value = ActivePagingRequest(request, generation = ++pagingGeneration)
                }
            }
        } else {
            pagingRequest.value = ActivePagingRequest(request, generation = ++pagingGeneration)
        }
    }

    fun refreshCatalog() {
        currentRequest?.let { request ->
            mutableUiState.value = CatalogUiState(requestKey = request.requestKey)
            pagingRequest.value = ActivePagingRequest(request, generation = ++pagingGeneration)
        }
    }

    fun retry() {
        currentRequest?.let { loadFirstPage(it, force = true) }
    }
}
