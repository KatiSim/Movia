package app.movia.android.data.library

import android.content.Context
import app.movia.android.data.database.DownloadEntity
import app.movia.android.data.database.FavoriteEntity
import app.movia.android.data.database.HistoryEntity
import app.movia.android.data.database.PlaybackProgressEntity
import app.movia.android.data.database.RecentSearchEntity
import app.movia.android.data.database.MoviaDatabase
import app.movia.android.data.database.WatchLaterEntity
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.PlaybackProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(context: Context) {
    private val dao = MoviaDatabase.get(context.applicationContext).moviaDao()

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
                contentId = canonicalContentId(it.title),
                updatedAt = it.updatedAt,
            )
        } ?: PlaybackProgress()
    }

    val progressByTitle: Flow<Map<String, PlaybackProgress>> = dao.observeAllProgress().map { rows ->
        rows.associate { entity ->
            entity.title to PlaybackProgress(
                title = entity.title,
                positionMs = entity.positionMs,
                durationMs = entity.durationMs,
                contentId = canonicalContentId(entity.title),
                updatedAt = entity.updatedAt,
            )
        }
    }

    suspend fun setFavorite(title: String, enabled: Boolean) {
        if (enabled) dao.upsertFavorite(FavoriteEntity(title, now(), canonicalContentId(title))) else dao.deleteFavorite(title)
    }

    suspend fun setWatchLater(title: String, enabled: Boolean) {
        if (enabled) dao.upsertWatchLater(WatchLaterEntity(title, now(), canonicalContentId(title))) else dao.deleteWatchLater(title)
    }

    suspend fun addHistory(title: String, openedAt: Long = now()) {
        if (title.isNotBlank()) dao.upsertHistory(HistoryEntity(title, openedAt, canonicalContentId(title)))
    }

    suspend fun clearHistory() = dao.clearHistory()

    suspend fun restoreHistory(items: List<String>) {
        dao.clearHistory()
        var timestamp = now()
        items.asReversed().forEach { title ->
            if (title.isNotBlank()) dao.upsertHistory(HistoryEntity(title, timestamp++, canonicalContentId(title)))
        }
    }

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
                contentId = canonicalContentId(title),
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
            dao.upsertDownload(DownloadEntity(title, filePath, completedAt, canonicalContentId(title)))
        } else {
            dao.deleteDownload(title)
        }
    }

    suspend fun clearDownloads() = dao.clearDownloads()

    suspend fun backfillCanonicalContentIds() {
        dao.favoritesMissingContentId().forEach { title ->
            canonicalContentId(title)?.let { dao.updateFavoriteContentId(title, it) }
        }
        dao.watchLaterMissingContentId().forEach { title ->
            canonicalContentId(title)?.let { dao.updateWatchLaterContentId(title, it) }
        }
        dao.historyMissingContentId().forEach { title ->
            canonicalContentId(title)?.let { dao.updateHistoryContentId(title, it) }
        }
        dao.progressMissingContentId().forEach { title ->
            canonicalContentId(title)?.let { dao.updateProgressContentId(title, it) }
        }
        dao.downloadsMissingContentId().forEach { title ->
            canonicalContentId(title)?.let { dao.updateDownloadContentId(title, it) }
        }
    }

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

    private fun canonicalContentId(storedTitle: String): String? {
        val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
        return DemoCatalogRepository.findByTitle(base)?.id
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
