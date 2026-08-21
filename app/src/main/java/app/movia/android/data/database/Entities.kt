package app.movia.android.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val title: String,
    val addedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val title: String,
    val addedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val title: String,
    val openedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

@Entity(tableName = "playback_progress")
data class PlaybackProgressEntity(
    @PrimaryKey val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val title: String,
    val filePath: String,
    val completedAt: Long,
    val contentId: String? = null,
)
