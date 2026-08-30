package app.movia.android.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationEngineTest {
    @Test
    fun historyProducesPersonalizedRecommendationsWithoutPreferringWatched() {
        val result = RecommendationEngine.recommend(history = listOf("Нулевая орбита"))
        assertEquals("На основе истории просмотра", result.reason)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items.none { it.title == "Нулевая орбита" })
    }

    @Test
    fun directorAndActorAffinityInfluenceRanking() {
        val result = RecommendationEngine.recommend(history = listOf("Граница миров"), limit = 8)
        assertTrue(result.items.isNotEmpty())
        // "Город после дождя" shares director Lucía Vega and actor Marta Soler,
        // plus genre/country affinity, so it should outrank generic popularity matches.
        assertEquals("Город после дождя", result.items.first().title)
    }

    @Test
    fun emptyHistoryUsesProbabilityColdStartWithDiversity() {
        val result = RecommendationEngine.recommend(history = emptyList(), limit = 8)
        assertEquals("Подборка для знакомства", result.reason)
        assertEquals(8, result.items.size)
        assertTrue(result.items.map { it.type }.distinct().size >= 2)
        assertTrue(result.items.flatMap { it.genres }.distinct().size >= 4)
    }

    @Test
    fun fullHistoryStillReturnsForYouItems() {
        val allTitles = DemoCatalogRepository.all().map { it.title }
        val result = RecommendationEngine.recommend(history = allTitles, limit = 8)
        assertEquals("На основе истории просмотра", result.reason)
        assertEquals(8, result.items.size)
        assertFalse(result.items.isEmpty())
    }
}
