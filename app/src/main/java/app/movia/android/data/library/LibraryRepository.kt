package app.movia.android.data.library

import android.content.Context
import androidx.room.withTransaction
import app.movia.android.data.database.DownloadEntity
import app.movia.android.data.database.FavoriteEntity
import app.movia.android.data.database.HistoryEntity
import app.movia.android.data.database.PlaybackProgressEntity
import app.movia.android.data.database.RecentSearchEntity
import app.movia.android.data.database.MoviaDatabase
import app.movia.android.data.database.WatchLaterEntity
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.PlaybackProgress
import app.movia.android.domain.model.MediaRef
import app.movia.android.domain.model.LibraryMediaRecord
import app.movia.android.domain.repository.SavedMediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LibraryRepository(context: Context) : SavedMediaRepository {
    private val database = MoviaDatabase.get(context.applicationContext)
    private val dao = database.moviaDao()

    val favorites: Flow<Set<String>> = dao.observeFavorites().map { it.toSet() }
    val favoriteRecords: Flow<List<LibraryMediaRecord>> = dao.observeFavoriteRows().map { rows ->
        rows.map { row -> row.toLibraryRecord(row.addedAt) }
    }
    val watchLater: Flow<Set<String>> = dao.observeWatchLater().map { it.toSet() }
    val history: Flow<List<String>> = dao.observeHistory()
    val historyRecords: Flow<List<LibraryMediaRecord>> = dao.observeHistoryRows().map { rows ->
        rows.map { row -> row.toLibraryRecord(row.openedAt) }
    }
    val recentSearches: Flow<List<String>> = dao.observeRecentSearches()
    val downloads: Flow<Set<String>> = dao.observeDownloads().map { it.toSet() }
    val downloadRecords: Flow<List<LibraryMediaRecord>> = dao.observeDownloadRows().map { rows ->
        rows.map { row -> row.toLibraryRecord(row.completedAt) }
    }
    val lastProgress: Flow<PlaybackProgress> = dao.observeLatestProgress().map { entity ->
        entity?.toPlaybackProgress() ?: PlaybackProgress()
    }

    val progressByTitle: Flow<Map<String, PlaybackProgress>> = dao.observeAllProgress().map { rows ->
        rows.associate { entity ->
            entity.title to entity.toPlaybackProgress()
        }
    }

    val progressByMediaRef: Flow<Map<String, PlaybackProgress>> = dao.observeAllProgress().map { rows ->
        rows.associate { entity ->
            val progress = entity.toPlaybackProgress()
            entity.mediaKey to progress
        }
    }

    suspend fun setFavorite(title: String, enabled: Boolean) {
        setFavorite(canonicalContentId(title).orEmpty(), title, enabled)
    }

    override suspend fun setFavorite(contentId: String, title: String, enabled: Boolean) {
        if (title.isBlank()) return
        database.withTransaction {
            val resolvedContentId = contentIdFor(contentId, title)
            val mediaKey = MediaRef.storageKey(resolvedContentId, title)
            if (enabled) {
                if (resolvedContentId != null) dao.deleteFavoritesByContentId(resolvedContentId)
                dao.upsertFavorite(
                    FavoriteEntity(
                        mediaKey = mediaKey,
                        title = title,
                        addedAt = now(),
                        contentId = resolvedContentId,
                    ),
                )
            } else {
                dao.deleteFavoriteByMediaKey(mediaKey)
                dao.deleteFavorite(title)
                if (resolvedContentId != null) dao.deleteFavoritesByContentId(resolvedContentId)
            }
            // Watch Later was the legacy backing store for the single "Мой список" action.
            dao.deleteWatchLater(title)
            if (resolvedContentId != null) dao.deleteWatchLaterByContentId(resolvedContentId)
        }
    }

    suspend fun setWatchLater(title: String, enabled: Boolean) {
        // Keep older callers and agent clients on the same canonical list action.
        setFavorite(title, enabled)
    }

    suspend fun migrateWatchLaterToFavorites() = database.withTransaction {
        dao.migrateWatchLaterToFavorites()
    }

    suspend fun addHistory(title: String, openedAt: Long = now()) {
        addHistory(canonicalContentId(title).orEmpty(), title, openedAt)
    }

    suspend fun addHistory(contentId: String, title: String, openedAt: Long = now()) {
        if (title.isNotBlank()) {
            val mediaRef = MediaRef.from(contentId, title)
            if (mediaRef != null) {
                addHistory(mediaRef, title, openedAt)
                return
            }
            val resolvedContentId = contentIdFor(contentId, title)
            dao.upsertHistory(
                HistoryEntity(
                    mediaKey = MediaRef.storageKey(resolvedContentId, title),
                    title = title,
                    openedAt = openedAt,
                    contentId = resolvedContentId,
                ),
            )
        }
    }

    suspend fun addHistory(mediaRef: MediaRef, title: String, openedAt: Long = now()) {
        if (title.isBlank()) return
        dao.upsertHistory(
            HistoryEntity(
                mediaKey = mediaRef.storageKey,
                title = title,
                openedAt = openedAt,
                contentId = mediaRef.contentId,
            ),
        )
    }

    suspend fun clearHistory() = dao.clearHistory()

    suspend fun restoreHistory(items: List<String>) {
        dao.clearHistory()
        var timestamp = now()
        items.asReversed().forEach { title ->
            if (title.isNotBlank()) {
                val contentId = canonicalContentId(title)
                dao.upsertHistory(
                    HistoryEntity(
                        mediaKey = MediaRef.storageKey(contentId, title),
                        title = title,
                        openedAt = timestamp++,
                        contentId = contentId,
                    ),
                )
            }
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
        saveProgress(canonicalContentId(title).orEmpty(), title, positionMs, durationMs, updatedAt)
    }

    suspend fun saveProgress(
        contentId: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = now(),
    ) {
        if (title.isBlank() || positionMs < 0L || durationMs <= 0L) return
        val mediaRef = MediaRef.from(contentId, title)
        if (mediaRef != null) {
            saveProgress(mediaRef, title, positionMs, durationMs, updatedAt)
            return
        }
        val resolvedContentId = contentIdFor(contentId, title)
        dao.upsertProgress(
            PlaybackProgressEntity(
                mediaKey = MediaRef.storageKey(resolvedContentId, title),
                title = title,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = updatedAt,
                contentId = resolvedContentId,
            ),
        )
    }

    suspend fun saveProgress(
        mediaRef: MediaRef,
        title: String,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = now(),
    ) {
        if (title.isBlank() || positionMs < 0L || durationMs <= 0L) return
        dao.upsertProgress(
            PlaybackProgressEntity(
                mediaKey = mediaRef.storageKey,
                title = title,
                positionMs = positionMs,
                durationMs = durationMs,
                updatedAt = updatedAt,
                contentId = mediaRef.contentId,
            ),
        )
    }

    suspend fun setDownloaded(
        title: String,
        enabled: Boolean,
        filePath: String = "",
        completedAt: Long = now(),
    ) {
        setDownloaded(canonicalContentId(title).orEmpty(), title, enabled, filePath, completedAt)
    }

    suspend fun setDownloaded(
        contentId: String,
        title: String,
        enabled: Boolean,
        filePath: String = "",
        completedAt: Long = now(),
    ) {
        if (title.isBlank()) return
        MediaRef.from(contentId, title)?.let { mediaRef ->
            setDownloaded(mediaRef, title, enabled, filePath, completedAt)
            return
        }
        val resolvedContentId = contentIdFor(contentId, title)
        val mediaKey = MediaRef.storageKey(resolvedContentId, title)
        if (enabled) {
            dao.deleteDownload(title)
            dao.upsertDownload(
                DownloadEntity(
                    mediaKey = mediaKey,
                    title = title,
                    filePath = filePath,
                    completedAt = completedAt,
                    contentId = resolvedContentId,
                ),
            )
        } else {
            dao.deleteDownloadByMediaKey(mediaKey)
            dao.deleteDownload(title)
        }
    }

    suspend fun setDownloaded(
        mediaRef: MediaRef,
        title: String,
        enabled: Boolean,
        filePath: String = "",
        completedAt: Long = now(),
    ) {
        if (title.isBlank()) return
        val mediaKey = mediaRef.storageKey
        if (enabled) {
            dao.deleteDownload(title)
            dao.upsertDownload(
                DownloadEntity(
                    mediaKey = mediaKey,
                    title = title,
                    filePath = filePath,
                    completedAt = completedAt,
                    contentId = mediaRef.contentId,
                ),
            )
        } else {
            dao.deleteDownloadByMediaKey(mediaKey)
            dao.deleteDownload(title)
        }
    }

    suspend fun clearDownloads() = dao.clearDownloads()

    suspend fun backfillCanonicalContentIds() {
        dao.favoritesMissingContentId().forEach { row ->
            canonicalContentId(row.title)?.let { contentId ->
                dao.rekeyFavorite(
                    row.mediaKey,
                    row.copy(mediaKey = MediaRef.storageKey(contentId, row.title), contentId = contentId),
                )
            }
        }
        dao.watchLaterMissingContentId().forEach { title ->
            canonicalContentId(title)?.let { dao.updateWatchLaterContentId(title, it) }
        }
        dao.historyMissingContentId().forEach { row ->
            canonicalContentId(row.title)?.let { contentId ->
                dao.rekeyHistory(
                    row.mediaKey,
                    row.copy(mediaKey = MediaRef.storageKey(contentId, row.title), contentId = contentId),
                )
            }
        }
        dao.progressMissingContentId().forEach { row ->
            canonicalContentId(row.title)?.let { contentId ->
                dao.rekeyProgress(
                    row.mediaKey,
                    row.copy(mediaKey = MediaRef.storageKey(contentId, row.title), contentId = contentId),
                )
            }
        }
        dao.downloadsMissingContentId().forEach { row ->
            canonicalContentId(row.title)?.let { contentId ->
                dao.rekeyDownload(
                    row.mediaKey,
                    row.copy(mediaKey = MediaRef.storageKey(contentId, row.title), contentId = contentId),
                )
            }
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

    private fun contentIdFor(contentId: String, storedTitle: String): String? {
        val baseTitle = storedTitle.substringBefore(" · S").substringBefore(" · E")
        val explicitId = contentId.takeIf {
            it.isNotBlank() && it != storedTitle && it != baseTitle
        }
        return explicitId ?: canonicalContentId(storedTitle)
    }

    private fun canonicalContentId(storedTitle: String): String? {
        val base = storedTitle.substringBefore(" · S").substringBefore(" · E")
        return DemoCatalogRepository.findByTitle(base)?.id
            ?: DemoCatalogRepository.findById(storedTitle)?.id
    }

    private fun FavoriteEntity.toLibraryRecord(updatedAt: Long): LibraryMediaRecord =
        libraryRecord(mediaKey, contentId, title, updatedAt)

    private fun HistoryEntity.toLibraryRecord(updatedAt: Long): LibraryMediaRecord =
        libraryRecord(mediaKey, contentId, title, updatedAt)

    private fun DownloadEntity.toLibraryRecord(updatedAt: Long): LibraryMediaRecord =
        libraryRecord(mediaKey, contentId, title, updatedAt)

    private fun libraryRecord(
        storedKey: String,
        storedContentId: String?,
        title: String,
        updatedAt: Long,
    ): LibraryMediaRecord {
        val keyRef = MediaRef.fromStorageKey(storedKey)
        val contentId = keyRef?.contentId
            ?: storedContentId?.takeIf { MediaRef.from(it, title) != null }
            ?: canonicalContentId(title)
        return LibraryMediaRecord(
            mediaKey = keyRef?.storageKey
                ?: MediaRef.storageKey(contentId, title).takeIf { contentId != null }
                ?: storedKey,
            contentId = contentId,
            title = title,
            updatedAt = updatedAt,
        )
    }

    private fun PlaybackProgressEntity.toPlaybackProgress(): PlaybackProgress {
        val keyRef = MediaRef.fromStorageKey(mediaKey)
        val validContentId = contentId?.takeIf { id ->
            MediaRef.from(id, title) != null || keyRef?.contentId == id
        } ?: keyRef?.contentId ?: canonicalContentId(title)
        val mediaRef = keyRef?.takeIf { validContentId == it.contentId }
            ?: validContentId?.let { MediaRef.from(it, title) }
        return PlaybackProgress(
            title = title,
            positionMs = positionMs,
            durationMs = durationMs,
            contentId = validContentId,
            updatedAt = updatedAt,
            seasonNumber = mediaRef?.season,
            episodeNumber = mediaRef?.episode,
        )
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
