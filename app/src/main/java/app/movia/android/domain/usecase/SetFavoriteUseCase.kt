package app.movia.android.domain.usecase

import app.movia.android.domain.repository.SavedMediaRepository

/** One action contract shared by UI and agent commands for the user's saved list. */
class SetFavoriteUseCase(
    private val savedMedia: SavedMediaRepository,
) {
    suspend operator fun invoke(contentId: String, title: String, enabled: Boolean) {
        savedMedia.setFavorite(contentId, title, enabled)
    }
}
