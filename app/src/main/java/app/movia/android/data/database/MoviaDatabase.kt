package app.movia.android.data.database

import android.content.Context
import android.database.Cursor
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.movia.android.domain.model.MediaRef

@Database(
    entities = [
        FavoriteEntity::class,
        WatchLaterEntity::class,
        HistoryEntity::class,
        RecentSearchEntity::class,
        PlaybackProgressEntity::class,
        DownloadEntity::class,
        CachedMediaEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MoviaDatabase : RoomDatabase() {
    abstract fun moviaDao(): MoviaDao

    companion object {
        @Volatile
        private var instance: MoviaDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE favorites ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE watch_later ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE history ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE playback_progress ADD COLUMN contentId TEXT")
                db.execSQL("ALTER TABLE downloads ADD COLUMN contentId TEXT")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS media_cache (contentId TEXT NOT NULL, payloadJson TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(contentId))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_cache_updatedAt ON media_cache(updatedAt)")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateIdentityTable(
                    db = db,
                    table = "favorites",
                    orderColumn = "addedAt",
                    createTableSql = "CREATE TABLE favorites_new (mediaKey TEXT NOT NULL, title TEXT NOT NULL, addedAt INTEGER NOT NULL, contentId TEXT, PRIMARY KEY(mediaKey))",
                    selectColumns = "title, addedAt, contentId",
                    insertSql = "INSERT OR REPLACE INTO favorites_new (mediaKey, title, addedAt, contentId) VALUES (?, ?, ?, ?)",
                ) { cursor ->
                    val title = cursor.getString(0)
                    val contentId = cursor.validContentId(2, title)
                    arrayOf(MediaRef.storageKey(contentId, title), title, cursor.getLong(1), contentId)
                }
                migrateIdentityTable(
                    db = db,
                    table = "history",
                    orderColumn = "openedAt",
                    createTableSql = "CREATE TABLE history_new (mediaKey TEXT NOT NULL, title TEXT NOT NULL, openedAt INTEGER NOT NULL, contentId TEXT, PRIMARY KEY(mediaKey))",
                    selectColumns = "title, openedAt, contentId",
                    insertSql = "INSERT OR REPLACE INTO history_new (mediaKey, title, openedAt, contentId) VALUES (?, ?, ?, ?)",
                ) { cursor ->
                    val title = cursor.getString(0)
                    val contentId = cursor.validContentId(2, title)
                    arrayOf(MediaRef.storageKey(contentId, title), title, cursor.getLong(1), contentId)
                }
                migrateIdentityTable(
                    db = db,
                    table = "playback_progress",
                    orderColumn = "updatedAt",
                    createTableSql = "CREATE TABLE playback_progress_new (mediaKey TEXT NOT NULL, title TEXT NOT NULL, positionMs INTEGER NOT NULL, durationMs INTEGER NOT NULL, updatedAt INTEGER NOT NULL, contentId TEXT, PRIMARY KEY(mediaKey))",
                    selectColumns = "title, positionMs, durationMs, updatedAt, contentId",
                    insertSql = "INSERT OR REPLACE INTO playback_progress_new (mediaKey, title, positionMs, durationMs, updatedAt, contentId) VALUES (?, ?, ?, ?, ?, ?)",
                ) { cursor ->
                    val title = cursor.getString(0)
                    val contentId = cursor.validContentId(4, title)
                    arrayOf(
                        MediaRef.storageKey(contentId, title),
                        title,
                        cursor.getLong(1),
                        cursor.getLong(2),
                        cursor.getLong(3),
                        contentId,
                    )
                }
                migrateIdentityTable(
                    db = db,
                    table = "downloads",
                    orderColumn = "completedAt",
                    createTableSql = "CREATE TABLE downloads_new (mediaKey TEXT NOT NULL, title TEXT NOT NULL, filePath TEXT NOT NULL, completedAt INTEGER NOT NULL, contentId TEXT, PRIMARY KEY(mediaKey))",
                    selectColumns = "title, filePath, completedAt, contentId",
                    insertSql = "INSERT OR REPLACE INTO downloads_new (mediaKey, title, filePath, completedAt, contentId) VALUES (?, ?, ?, ?, ?)",
                ) { cursor ->
                    val title = cursor.getString(0)
                    val contentId = cursor.validContentId(3, title)
                    arrayOf(
                        MediaRef.storageKey(contentId, title),
                        title,
                        cursor.getString(1),
                        cursor.getLong(2),
                        contentId,
                    )
                }

                db.execSQL("CREATE INDEX IF NOT EXISTS index_favorites_contentId ON favorites(contentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_contentId ON history(contentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playback_progress_contentId ON playback_progress(contentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_contentId ON downloads(contentId)")
            }
        }

        private fun migrateIdentityTable(
            db: SupportSQLiteDatabase,
            table: String,
            orderColumn: String,
            createTableSql: String,
            selectColumns: String,
            insertSql: String,
            mapRow: (Cursor) -> Array<Any?>,
        ) {
            val temporaryTable = "${table}_new"
            db.execSQL("DROP TABLE IF EXISTS $temporaryTable")
            db.execSQL(createTableSql)
            db.query("SELECT $selectColumns FROM $table ORDER BY $orderColumn ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    db.execSQL(insertSql, mapRow(cursor))
                }
            }
            db.execSQL("DROP TABLE $table")
            db.execSQL("ALTER TABLE $temporaryTable RENAME TO $table")
        }

        private fun Cursor.nullableString(index: Int): String? =
            if (isNull(index)) null else getString(index)

        private fun Cursor.validContentId(index: Int, title: String): String? =
            nullableString(index)?.takeIf { MediaRef.from(it, title) != null }

        fun get(context: Context): MoviaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MoviaDatabase::class.java,
                "movia.db",
            )
                .addMigrations(MIGRATION_1_2)
                .addMigrations(MIGRATION_2_3)
                .addMigrations(MIGRATION_3_4)
                .build()
                .also { instance = it }
        }
    }
}
