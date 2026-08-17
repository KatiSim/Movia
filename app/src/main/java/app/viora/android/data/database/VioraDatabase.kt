package app.viora.android.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        WatchLaterEntity::class,
        HistoryEntity::class,
        RecentSearchEntity::class,
        PlaybackProgressEntity::class,
        DownloadEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VioraDatabase : RoomDatabase() {
    abstract fun vioraDao(): VioraDao

    companion object {
        @Volatile
        private var instance: VioraDatabase? = null

        fun get(context: Context): VioraDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                VioraDatabase::class.java,
                "viora.db",
            ).build().also { instance = it }
        }
    }
}
