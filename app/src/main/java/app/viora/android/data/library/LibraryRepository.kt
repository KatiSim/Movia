package app.viora.android.data.library

import android.content.Context
import app.viora.android.data.database.DownloadEntity
import app.viora.android.data.database.FavoriteEntity
import app.viora.android.data.database.HistoryEntity
import app.viora.android.data.database.PlaybackProgressEntity
import app.viora.android.data.database.RecentSearchEntity
import app.viora.android.data.database.VioraDatabase
import app.viora.android.data.database.WatchLaterEntity
import app.viora.android.domain.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(context: Context) {
    private val dao = VioraDatabase.get(context.applicationContext).vioraDao()

    val favorites: Flow<Set<String>> = dao.observeFavorites().map { it.toSet() }
    val watchLater: Flow<Set<String>> = dao.observeWatchLater().map { it.toSet() }
    val history: Flow<List<String>> = dao.observeHistory()
    val recentSearches: Flow<List<String>> = dao.observeRecentSearches()
    val downloads: Flow<Set<String>> = dao.observeDownloads().map { it.toSet() }
    val lastProgress: Flow<PlaybackProgress> = dao.observeLatestProgress().map { entity ->
        entity?.let {
            PlaybackProgress(
                title = it.title,
                positionMs = it.positionMs,
                durationMs = it.durationMs,
            )
        } ?: PlaybackProgress()
    }

    suspend fun setFavorite(title: String, enabled: Boolean) {
        if (enabled) dao.upsertFavorite(FavoriteEntity(title, now())) else dao.deleteFavorite(title)
    }

    suspend fun setWatchLater(title: String, enabled: Boolean) {
        if (enabled) dao.upsertWatchLater(WatchLaterEntity(title, now())) else dao.deleteWatchLater(title)
    }

    suspend fun addHistory(title: String, openedAt: Long = now()) {
        if (title.isNotBlank()) dao.upsertHistory(HistoryEntity(title, openedAt))
    }

    suspend fun clearHistory() = dao.clearHistory()

    suspend fun addSearchQuery(query: String, searchedAt: Long = now()) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        dao.upsertRecentSearch(RecentSearchEntity(normalized, searchedAt))
        dao.trimRecentSearches()
    }

    suspend fun clearSearchHistory() = dao.clearRecentSearches()

    suspend fun saveProgress(
        title: String,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = now(),
    ) {
        if (title.isBlank() || positionMs < 0L || durationMs <= 0L) return
        dao.upsertProgress(
            PlaybackProgressEntity(
                title = title,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = updatedAt,
            ),
        )
    }

    suspend fun setDownloaded(
        title: String,
        enabled: Boolean,
        filePath: String = "",
        completedAt: Long = now(),
    ) {
        if (enabled) {
            dao.upsertDownload(DownloadEntity(title, filePath, completedAt))
        } else {
            dao.deleteDownload(title)
        }
    }

    suspend fun clearDownloads() = dao.clearDownloads()

    suspend fun importLegacy(snapshot: LegacyLibrarySnapshot) {
        var timestamp = now()
        snapshot.favorites.forEach { setFavorite(it, true); timestamp += 1L }
        snapshot.watchLater.forEach { setWatchLater(it, true); timestamp += 1L }
        snapshot.history.asReversed().forEach { addHistory(it, timestamp++) }
        snapshot.recentSearches.asReversed().forEach { addSearchQuery(it, timestamp++) }
        snapshot.downloads.forEach { setDownloaded(it, true, completedAt = timestamp++) }
        snapshot.lastProgress.takeIf { it.title.isNotBlank() && it.durationMs > 0L }?.let {
            saveProgress(it.title, it.positionMs, it.durationMs, timestamp)
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}

data class LegacyLibrarySnapshot(
    val favorites: Set<String> = emptySet(),
    val watchLater: Set<String> = emptySet(),
    val history: List<String> = emptyList(),
    val recentSearches: List<String> = emptyList(),
    val downloads: Set<String> = emptySet(),
    val lastProgress: PlaybackProgress = PlaybackProgress(),
)
