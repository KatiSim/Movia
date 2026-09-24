package app.movia.android.domain.repository

interface SavedMediaRepository {
    suspend fun setFavorite(contentId: String, title: String, enabled: Boolean)
}
