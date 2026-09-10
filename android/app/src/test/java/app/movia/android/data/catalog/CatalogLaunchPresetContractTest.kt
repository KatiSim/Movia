package app.movia.android.data.catalog

import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLaunchPresetContractTest {
    @Test
    fun newAndSoonUseDisjointYearWindows() {
        val source = java.io.File("src/main/java/app/movia/android/ui/catalog/CatalogScreen.kt").readText()

        assertTrue(source.contains("CatalogLaunchPreset { ALL, POPULAR, NEW, SOON, RECOMMENDED }"))
        assertTrue(source.contains("yearFrom = currentCatalogYear - 1"))
        assertTrue(source.contains("yearTo = currentCatalogYear"))
        assertTrue(source.contains("CatalogLaunchPreset.SOON"))
        assertTrue(source.contains("yearFrom = currentCatalogYear + 1"))
        assertTrue(source.contains("sortName = CatalogSort.OLDEST.name"))
    }

    @Test
    fun homeExposesComingSoonAsItsOwnShelf() {
        val source = java.io.File("src/main/java/app/movia/android/ui/home/HomeScreen.kt").readText()

        assertTrue(source.contains("title = \"Скоро\""))
        assertTrue(source.contains("onOpenCatalog(CatalogLaunchPreset.SOON)"))
        assertTrue(source.indexOf("title = \"Новинки\"") < source.indexOf("title = \"Скоро\""))
    }
}
