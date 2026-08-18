package app.viora.android.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VioraDao {
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
}
