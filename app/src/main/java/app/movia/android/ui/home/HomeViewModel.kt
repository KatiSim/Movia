package app.movia.android.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.HomeFeedSnapshot
import app.movia.android.data.catalog.RecommendationEngine
import app.movia.android.data.catalog.RecommendationResult
import app.movia.android.domain.model.ContentType
import app.movia.android.domain.model.MediaContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val feed: HomeFeedSnapshot = HomeFeedSnapshot(),
    val recommendation: RecommendationResult = RecommendationResult("Подбираем для вас", emptyList()),
    val animationSeries: List<MediaContent> = emptyList(),
    val isRecommending: Boolean = false,
)

class HomeViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

    private var recommendationJob: Job? = null
    private var lastRecommendationKey: Pair<List<String>, Set<String>>? = null
    private var animationFallbackJob: Job? = null
    private var animationFallbackKey: List<String>? = null

    init {
        viewModelScope.launch {
            DemoCatalogRepository.homeFeed.collect { feed ->
                mutableUiState.update { state ->
                    val noPreferenceSignals = lastRecommendationKey?.let { (history, favorites) ->
                        history.isEmpty() && favorites.isEmpty()
                    } == true
                    val localRecommendation = if (noPreferenceSignals) {
                        RecommendationResult(
                            "Популярное для старта",
                            (feed.forYou + feed.popular + feed.newReleases).distinctBy { it.id }.take(20),
                        )
                    } else state.recommendation
                    state.copy(feed = feed, recommendation = localRecommendation)
                }
            }
        }
    }

    fun refreshHome() = DemoCatalogRepository.refreshHome()

    fun updateAnimationSeries(feed: HomeFeedSnapshot) {
        val series = feed.animations
            .filter { it.type == ContentType.SERIES || it.type == ContentType.TV }
            .take(12)
        if (series.isNotEmpty()) {
            animationFallbackJob?.cancel()
            animationFallbackKey = null
            mutableUiState.update { it.copy(animationSeries = series) }
            return
        }

        val cachedFallback = feed.catalog
            .filter { it.type == ContentType.SERIES || it.type == ContentType.TV }
            .filter { item ->
                item.genres.any {
                    it.contains("мульт", ignoreCase = true) || it.contains("аниме", ignoreCase = true)
                }
            }
            .distinctBy { it.id }
            .take(12)
        if (cachedFallback.isNotEmpty()) {
            animationFallbackJob?.cancel()
            animationFallbackKey = null
            mutableUiState.update { it.copy(animationSeries = cachedFallback) }
            return
        }

        val requestKey = feed.catalog.map { it.id }
        if (animationFallbackKey == requestKey && animationFallbackJob?.isActive != false) return
        animationFallbackKey = requestKey
        animationFallbackJob?.cancel()
        animationFallbackJob = viewModelScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                try {
                    DemoCatalogRepository.getAnimationSeries(12)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
            mutableUiState.update { state ->
                state.copy(animationSeries = fetched.take(12))
            }
        }
    }

    fun updateRecommendations(history: List<String>, favorites: Set<String>) {
        val key = history to favorites
        if (key == lastRecommendationKey) return
        if (history.isEmpty() && favorites.isEmpty()) {
            recommendationJob?.cancel()
            lastRecommendationKey = key
            val feed = mutableUiState.value.feed
            val local = (feed.forYou + feed.popular + feed.newReleases).distinctBy { it.id }.take(20)
            mutableUiState.update {
                it.copy(
                    recommendation = RecommendationResult("Популярное для старта", local),
                    isRecommending = false,
                )
            }
            return
        }
        lastRecommendationKey = key
        recommendationJob?.cancel()
        recommendationJob = viewModelScope.launch {
            mutableUiState.update { it.copy(isRecommending = true) }
            val result = withContext(Dispatchers.IO) {
                try {
                    RecommendationEngine.recommend(history, favorites = favorites, limit = 20)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    RecommendationResult("Рекомендации временно недоступны", emptyList())
                }
            }
            mutableUiState.update { it.copy(recommendation = result, isRecommending = false) }
        }
    }
}
