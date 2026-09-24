package app.movia.android.domain.usecase

import app.movia.android.domain.repository.SavedMediaRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SetFavoriteUseCaseTest {
    @Test
    fun dispatchesOneIdBasedActionToTheSharedRepositoryContract() = runBlocking {
        val repository = RecordingSavedMediaRepository()
        val action = SetFavoriteUseCase(repository)

        action(contentId = "movia-42", title = "Arrival", enabled = true)

        assertEquals(1, repository.calls)
        assertEquals(SavedMediaAction("movia-42", "Arrival", true), repository.lastAction)
    }

    private class RecordingSavedMediaRepository : SavedMediaRepository {
        var calls: Int = 0
            private set
        var lastAction: SavedMediaAction? = null
            private set

        override suspend fun setFavorite(contentId: String, title: String, enabled: Boolean) {
            calls++
            lastAction = SavedMediaAction(contentId, title, enabled)
        }
    }

    private data class SavedMediaAction(
        val contentId: String,
        val title: String,
        val enabled: Boolean,
    )
}
