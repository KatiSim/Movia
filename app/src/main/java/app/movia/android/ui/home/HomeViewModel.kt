package app.movia.android.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.data.catalog.HomeFeedSnapshot
import app.movia.android.data.catalog.RecommendationEngine
import app.movia.android.data.catalog.RecommendationResult
import app.movia.android.data.preferences.HomeRecommendationSnapshot
import app.movia.android.data.preferences.MoviaPreferencesRepository
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

private const val RECOMMENDATION_REFRESH_MS = 24L * 60L * 60L * 1000L
private const val RECOMMENDATION_LOG_TAG = "HomeRecommendations"

data class HomeUiState(
    val feed: HomeFeedSnapshot = HomeFeedSnapshot(),
    val recommendation: RecommendationResult = RecommendationResult("Подбираем для вас", emptyList()),
    val recommendationHistory: List<String> = emptyList(),
    val recommendationGeneratedAtMs: Long = 0L,
    val animationSeries: List<MediaContent> = emptyList(),
    val isRecommending: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()

    private val preferencesRepository = MoviaPreferencesRepository(application.applicationContext)
    private var recommendationJob: Job? = null
    private var animationFallbackJob: Job? = null
    private var animationFallbackKey: List<String>? = null

    private var cadenceLoaded = false
    private var signalsReceived = false
    private var latestHistory: List<String> = emptyList()
    private var latestFavorites: Set<String> = emptySet()
    private var activeRecommendationSnapshot: HomeRecommendationSnapshot? = null
    private var appliedRecommendationIds: List<String> = emptyList()

    init {
        viewModelScope.launch {
            DemoCatalogRepository.homeFeed.collect { feed ->
                mutableUiState.update { state -> state.copy(feed = feed) }
                if (cadenceLoaded) {
                    restoreFreshSnapshot()
                    maybeUpdateRecommendations()
                }
            }
        }
        viewModelScope.launch {
            activeRecommendationSnapshot = preferencesRepository.readHomeRecommendationSnapshot()
            cadenceLoaded = true
            Log.d(RECOMMENDATION_LOG_TAG, "cadence loaded snapshot=" + (activeRecommendationSnapshot != null))
            restoreFreshSnapshot()
            maybeUpdateRecommendations()
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
        latestHistory = history.take(5)
        latestFavorites = favorites.take(20).toSet()
        signalsReceived = true
        Log.d(RECOMMENDATION_LOG_TAG, "signals history=" + latestHistory.size + " favorites=" + latestFavorites.size + " cadenceLoaded=" + cadenceLoaded)
        maybeUpdateRecommendations()
    }

    private fun restoreFreshSnapshot() {
        val snapshot = activeRecommendationSnapshot ?: return
        if (!isFresh(snapshot, System.currentTimeMillis())) return
        val restored = snapshot.recommendationIds
            .mapNotNull(DemoCatalogRepository::findById)
            .distinctBy { it.id }
        if (restored.isEmpty()) return
        appliedRecommendationIds = restored.map { it.id }
        mutableUiState.update {
            it.copy(
                recommendation = RecommendationResult(snapshot.reason, restored),
                recommendationHistory = snapshot.history,
                recommendationGeneratedAtMs = snapshot.generatedAtMs,
                isRecommending = false,
            )
        }
    }

    private fun maybeUpdateRecommendations() {
        if (!cadenceLoaded || !signalsReceived) {
            Log.d(RECOMMENDATION_LOG_TAG, "skip cadenceLoaded=" + cadenceLoaded + " signalsReceived=" + signalsReceived)
            return
        }
        val now = System.currentTimeMillis()
        val stored = activeRecommendationSnapshot
        if (stored != null && isFresh(stored, now)) {
            Log.d(RECOMMENDATION_LOG_TAG, "reuse fresh snapshot ageMs=" + (now - stored.generatedAtMs) + " ids=" + stored.recommendationIds.size)
            if (appliedRecommendationIds.isEmpty()) {
                val restored = stored.recommendationIds
                    .mapNotNull(DemoCatalogRepository::findById)
                    .distinctBy { it.id }
                if (restored.isNotEmpty()) {
                    appliedRecommendationIds = restored.map { it.id }
                    mutableUiState.update {
                        it.copy(
                            recommendation = RecommendationResult(stored.reason, restored),
                            recommendationHistory = stored.history,
                            recommendationGeneratedAtMs = stored.generatedAtMs,
                            isRecommending = false,
                        )
                    }
                    return
                }
                computeRecommendation(
                    basisHistory = stored.history,
                    basisFavorites = stored.favorites,
                    generatedAtMs = stored.generatedAtMs,
                )
            }
            return
        }

        Log.d(RECOMMENDATION_LOG_TAG, "refresh recommendation window")
        computeRecommendation(
            basisHistory = latestHistory,
            basisFavorites = latestFavorites,
            generatedAtMs = now,
        )
    }

    private fun computeRecommendation(
        basisHistory: List<String>,
        basisFavorites: Set<String>,
        generatedAtMs: Long,
    ) {
        if (recommendationJob?.isActive == true) return
        recommendationJob = viewModelScope.launch {
            Log.d(RECOMMENDATION_LOG_TAG, "compute start history=" + basisHistory.size + " favorites=" + basisFavorites.size)
            mutableUiState.update { it.copy(isRecommending = true) }
            val remoteResult = withContext(Dispatchers.IO) {
                try {
                    RecommendationEngine.recommend(
                        history = basisHistory,
                        favorites = basisFavorites,
                        limit = 20,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    RecommendationResult("Рекомендации временно недоступны", emptyList())
                }
            }
            val result = if (remoteResult.items.isNotEmpty()) {
                remoteResult
            } else {
                val feed = mutableUiState.value.feed
                val local = (feed.forYou + feed.popular + feed.newReleases)
                    .distinctBy { it.id }
                    .take(20)
                if (local.isNotEmpty()) RecommendationResult("Для вас", local) else remoteResult
            }

            if (result.items.isEmpty()) {
                Log.w(RECOMMENDATION_LOG_TAG, "compute produced no items")
                mutableUiState.update { it.copy(isRecommending = false) }
                return@launch
            }

            val snapshot = HomeRecommendationSnapshot(
                generatedAtMs = generatedAtMs,
                history = basisHistory,
                favorites = basisFavorites,
                recommendationIds = result.items.map { it.id },
                reason = result.reason,
            )
            Log.d(RECOMMENDATION_LOG_TAG, "persist snapshot ids=" + snapshot.recommendationIds.size + " generatedAt=" + snapshot.generatedAtMs)
            preferencesRepository.saveHomeRecommendationSnapshot(snapshot)
            Log.d(RECOMMENDATION_LOG_TAG, "persist complete")
            activeRecommendationSnapshot = snapshot
            appliedRecommendationIds = snapshot.recommendationIds
            mutableUiState.update {
                it.copy(
                    recommendation = result,
                    recommendationHistory = basisHistory,
                    recommendationGeneratedAtMs = generatedAtMs,
                    isRecommending = false,
                )
            }
        }
    }

    private fun isFresh(snapshot: HomeRecommendationSnapshot, nowMs: Long): Boolean {
        val ageMs = nowMs - snapshot.generatedAtMs
        return ageMs >= 0L && ageMs < RECOMMENDATION_REFRESH_MS
    }
}
