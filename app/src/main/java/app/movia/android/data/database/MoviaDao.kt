package app.movia.android.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.movia.android.domain.model.MediaRef
import kotlinx.coroutines.flow.Flow

@Dao
interface MoviaDao {
    @Query("SELECT * FROM media_cache WHERE updatedAt >= :minimumUpdatedAt ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun cachedMedia(minimumUpdatedAt: Long, limit: Int = 500): List<CachedMediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCachedMedia(entities: List<CachedMediaEntity>)

    @Query("DELETE FROM media_cache WHERE updatedAt < :minimumUpdatedAt OR contentId NOT IN (SELECT contentId FROM media_cache ORDER BY updatedAt DESC LIMIT 500)")
    suspend fun pruneCachedMedia(minimumUpdatedAt: Long)

    @Query("SELECT title FROM favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<String>>

    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeFavoriteRows(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mediaKey = :mediaKey")
    suspend fun deleteFavoriteByMediaKey(mediaKey: String)

    @Query("DELETE FROM favorites WHERE title = :title AND contentId IS NULL")
    suspend fun deleteFavorite(title: String)

    @Query("DELETE FROM favorites WHERE contentId = :contentId")
    suspend fun deleteFavoritesByContentId(contentId: String)

    @Query("SELECT title FROM watch_later ORDER BY addedAt DESC")
    fun observeWatchLater(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchLater(entity: WatchLaterEntity)

    @Query("SELECT * FROM watch_later")
    suspend fun watchLaterRows(): List<WatchLaterEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importWatchLaterAsFavorites(entities: List<FavoriteEntity>)

    @Query("DELETE FROM watch_later")
    suspend fun clearWatchLater()

    @Transaction
    suspend fun migrateWatchLaterToFavorites() {
        val existing = watchLaterRows()
        if (existing.isNotEmpty()) {
            importWatchLaterAsFavorites(
                existing.map {
                    FavoriteEntity(
                        mediaKey = MediaRef.storageKey(it.contentId, it.title),
                        title = it.title,
                        addedAt = it.addedAt,
                        contentId = it.contentId,
                    )
                },
            )
        }
        clearWatchLater()
    }

    @Query("DELETE FROM watch_later WHERE title = :title")
    suspend fun deleteWatchLater(title: String)

    @Query("DELETE FROM watch_later WHERE contentId = :contentId")
    suspend fun deleteWatchLaterByContentId(contentId: String)

    @Query("SELECT title FROM history ORDER BY openedAt DESC LIMIT 30")
    fun observeHistory(): Flow<List<String>>

    @Query("SELECT * FROM history ORDER BY openedAt DESC LIMIT 30")
    fun observeHistoryRows(): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: HistoryEntity)

    @Query("DELETE FROM history WHERE mediaKey = :mediaKey")
    suspend fun deleteHistoryByMediaKey(mediaKey: String)

    @Query("SELECT * FROM history WHERE contentId IS NULL ORDER BY openedAt ASC")
    suspend fun historyMissingContentId(): List<HistoryEntity>

    @Transaction
    suspend fun rekeyHistory(oldMediaKey: String, entity: HistoryEntity) {
        deleteHistoryByMediaKey(oldMediaKey)
        upsertHistory(entity)
    }

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("SELECT query FROM recent_searches ORDER BY searchedAt DESC LIMIT 8")
    fun observeRecentSearches(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRecentSearch(entity: RecentSearchEntity)

    @Query("DELETE FROM recent_searches WHERE query NOT IN (SELECT query FROM recent_searches ORDER BY searchedAt DESC LIMIT 8)")
    suspend fun trimRecentSearches()

    @Query("DELETE FROM recent_searches")
    suspend fun clearRecentSearches()

    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestProgress(): Flow<PlaybackProgressEntity?>

    @Query("SELECT * FROM playback_progress ORDER BY updatedAt DESC")
    fun observeAllProgress(): Flow<List<PlaybackProgressEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entity: PlaybackProgressEntity)

    @Query("DELETE FROM playback_progress WHERE mediaKey = :mediaKey")
    suspend fun deleteProgressByMediaKey(mediaKey: String)

    @Query("SELECT * FROM playback_progress WHERE contentId IS NULL ORDER BY updatedAt ASC")
    suspend fun progressMissingContentId(): List<PlaybackProgressEntity>

    @Transaction
    suspend fun rekeyProgress(oldMediaKey: String, entity: PlaybackProgressEntity) {
        deleteProgressByMediaKey(oldMediaKey)
        upsertProgress(entity)
    }

    @Query("SELECT title FROM downloads ORDER BY completedAt DESC")
    fun observeDownloads(): Flow<List<String>>

    @Query("SELECT * FROM downloads ORDER BY completedAt DESC")
    fun observeDownloadRows(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE mediaKey = :mediaKey")
    suspend fun deleteDownloadByMediaKey(mediaKey: String)

    @Query("DELETE FROM downloads WHERE title = :title AND contentId IS NULL")
    suspend fun deleteDownload(title: String)

    @Query("DELETE FROM downloads WHERE contentId = :contentId")
    suspend fun deleteDownloadsByContentId(contentId: String)

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()

    @Query("SELECT * FROM favorites WHERE contentId IS NULL ORDER BY addedAt ASC")
    suspend fun favoritesMissingContentId(): List<FavoriteEntity>

    @Transaction
    suspend fun rekeyFavorite(oldMediaKey: String, entity: FavoriteEntity) {
        deleteFavoriteByMediaKey(oldMediaKey)
        upsertFavorite(entity)
    }

    @Query("SELECT title FROM watch_later WHERE contentId IS NULL")
    suspend fun watchLaterMissingContentId(): List<String>

    @Query("UPDATE watch_later SET contentId = :contentId WHERE title = :title")
    suspend fun updateWatchLaterContentId(title: String, contentId: String)

    @Query("SELECT * FROM downloads WHERE contentId IS NULL ORDER BY completedAt ASC")
    suspend fun downloadsMissingContentId(): List<DownloadEntity>

    @Transaction
    suspend fun rekeyDownload(oldMediaKey: String, entity: DownloadEntity) {
        deleteDownloadByMediaKey(oldMediaKey)
        upsertDownload(entity)
    }
}
