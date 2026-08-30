package app.movia.android.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        WatchLaterEntity::class,
        HistoryEntity::class,
        RecentSearchEntity::class,
        PlaybackProgressEntity::class,
        DownloadEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MoviaDatabase : RoomDatabase() {
    abstract fun moviaDao(): MoviaDao

    companion object {
        @Volatile
        private var instance: MoviaDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE watch_later ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE history ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN contentId TEXT")
            }
        }

        fun get(context: Context): MoviaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MoviaDatabase::class.java,
                "movia.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
