package app.movia.android.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleSearchTest {
    @Test
    fun mediaSearchMatchesOriginalTitleDirectorAndCast() {
        assertEquals(
            listOf("Нулевая орбита"),
            DemoCatalogRepository.search("Zero Orbit").map { it.title },
        )
        assertTrue(
            DemoCatalogRepository.search("Lucía Vega")
                .map { it.title }
                .containsAll(listOf("Граница миров", "Город после дождя")),
        )
        assertTrue(
            DemoCatalogRepository.search("Diego Ríos")
                .map { it.title }
                .containsAll(listOf("Граница миров", "Последний рейс")),
        )
    }

    @Test
    fun peopleSearchReturnsKnownForTitles() {
        val person = DemoCatalogRepository.searchPeople("Marta Soler").single()
        assertEquals("Marta Soler", person.name)
        assertTrue(person.knownFor.contains("Граница миров"))
        assertTrue(person.knownFor.contains("Город после дождя"))
    }
}
