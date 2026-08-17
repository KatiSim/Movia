package app.viora.android.data.catalog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun historyProducesExplainableRecommendationsWithoutWatchedTitle() {
        val result = RecommendationEngine.recommend(history = listOf("Нулевая орбита"))
        assertFalse(result.reason == "Популярное для старта")
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.none { it.title == "Нулевая орбита" })
    }

    @Test
    fun emptyHistoryFallsBackToPopularContent() {
        val result = RecommendationEngine.recommend(history = emptyList())
        assertTrue(result.reason == "Популярное для старта")
        assertTrue(result.items.isNotEmpty())
    }
}
