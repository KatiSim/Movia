package app.movia.android.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MoviaDao {
    @Query("SELECT title FROM favorites ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE title = :title")
    suspend fun deleteFavorite(title: String)

    @Query("SELECT title FROM watch_later ORDER BY addedAt DESC")
    fun observeWatchLater(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWatchLater(entity: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE title = :title")
    suspend fun deleteWatchLater(title: String)

    @Query("SELECT title FROM history ORDER BY openedAt DESC LIMIT 30")
    fun observeHistory(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entity: HistoryEntity)

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

    @Query("SELECT title FROM downloads ORDER BY completedAt DESC")
    fun observeDownloads(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE title = :title")
    suspend fun deleteDownload(title: String)

    @Query("DELETE FROM downloads")
    suspend fun clearDownloads()

    @Query("SELECT title FROM favorites WHERE contentId IS NULL")
    suspend fun favoritesMissingContentId(): List<String>

    @Query("UPDATE favorites SET contentId = :contentId WHERE title = :title")
    suspend fun updateFavoriteContentId(title: String, contentId: String)

    @Query("SELECT title FROM watch_later WHERE contentId IS NULL")
    suspend fun watchLaterMissingContentId(): List<String>

    @Query("UPDATE watch_later SET contentId = :contentId WHERE title = :title")
    suspend fun updateWatchLaterContentId(title: String, contentId: String)

    @Query("SELECT title FROM history WHERE contentId IS NULL")
    suspend fun historyMissingContentId(): List<String>

    @Query("UPDATE history SET contentId = :contentId WHERE title = :title")
    suspend fun updateHistoryContentId(title: String, contentId: String)

    @Query("SELECT title FROM playback_progress WHERE contentId IS NULL")
    suspend fun progressMissingContentId(): List<String>

    @Query("UPDATE playback_progress SET contentId = :contentId WHERE title = :title")
    suspend fun updateProgressContentId(title: String, contentId: String)

    @Query("SELECT title FROM downloads WHERE contentId IS NULL")
    suspend fun downloadsMissingContentId(): List<String>

    @Query("UPDATE downloads SET contentId = :contentId WHERE title = :title")
    suspend fun updateDownloadContentId(title: String, contentId: String)
}
