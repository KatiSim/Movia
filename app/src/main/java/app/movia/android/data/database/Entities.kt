package app.movia.android.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "favorites", indices = [Index(value = ["contentId"])])
data class FavoriteEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val addedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val title: String,
    val addedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "waiting_release", indices = [Index(value = ["contentId"])])
data class WaitingReleaseEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val addedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "history", indices = [Index(value = ["contentId"])])
data class HistoryEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val openedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

@Entity(tableName = "playback_progress", indices = [Index(value = ["contentId"])])
data class PlaybackProgressEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "downloads", indices = [Index(value = ["contentId"])])
data class DownloadEntity(
    @PrimaryKey val mediaKey: String,
    val title: String,
    val filePath: String,
    val completedAt: Long,
    val contentId: String? = null,
)

@Entity(tableName = "media_cache", indices = [Index(value = ["updatedAt"])])
data class CachedMediaEntity(
    @PrimaryKey val contentId: String,
    val payloadJson: String,
    val updatedAt: Long,
)
