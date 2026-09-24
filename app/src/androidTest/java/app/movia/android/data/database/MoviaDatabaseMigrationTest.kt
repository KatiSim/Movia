package app.movia.android.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.movia.android.domain.model.MediaRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoviaDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MoviaDatabase::class.java,
    )

    @Test
    fun v1LibraryRowsSurviveEveryMigrationStep() {
        val oldDb = helper.createDatabase(V1_DATABASE, 1)
        oldDb.execSQL("INSERT INTO favorites (title, addedAt) VALUES (?, ?)", arrayOf<Any?>("Old favorite", 1L))
        oldDb.execSQL("INSERT INTO watch_later (title, addedAt) VALUES (?, ?)", arrayOf<Any?>("Old bookmark", 2L))
        oldDb.execSQL("INSERT INTO history (title, openedAt) VALUES (?, ?)", arrayOf<Any?>("Old history", 3L))
        oldDb.execSQL(
            "INSERT INTO recent_searches (query, searchedAt) VALUES (?, ?)",
            arrayOf<Any?>("old query", 4L),
        )
        oldDb.execSQL(
            "INSERT INTO playback_progress (title, positionMs, durationMs, updatedAt) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("Old progress", 500L, 1000L, 5L),
        )
        oldDb.execSQL(
            "INSERT INTO downloads (title, filePath, completedAt) VALUES (?, ?, ?)",
            arrayOf<Any?>("Old download", "/offline/old.mp4", 6L),
        )
        oldDb.close()

        val migratedDb = helper.runMigrationsAndValidate(
            V1_DATABASE,
            4,
            true,
            MoviaDatabase.MIGRATION_1_2,
            MoviaDatabase.MIGRATION_2_3,
            MoviaDatabase.MIGRATION_3_4,
        )
        helper.closeWhenFinished(migratedDb)

        assertLegacyKey(migratedDb, "favorites", "Old favorite")
        assertLegacyKey(migratedDb, "history", "Old history")
        assertLegacyKey(migratedDb, "playback_progress", "Old progress")
        assertLegacyKey(migratedDb, "downloads", "Old download")
        migratedDb.query("SELECT COUNT(*) FROM watch_later WHERE title = 'Old bookmark'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM recent_searches WHERE query = 'old query'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    @Test
    fun v3TitleKeysMigrateToContentAndEpisodeKeysWithoutLosingRows() {
        val oldDb = helper.createDatabase(TEST_DATABASE, 3)
        oldDb.execSQL(
            "INSERT INTO favorites (title, addedAt, contentId) VALUES (?, ?, ?)",
            arrayOf<Any?>("Localized movie title", 10L, "movie:1"),
        )
        oldDb.execSQL(
            "INSERT INTO favorites (title, addedAt, contentId) VALUES (?, ?, ?)",
            arrayOf<Any?>("Unresolved favorite", 9L, null),
        )
        oldDb.execSQL(
            "INSERT INTO favorites (title, addedAt, contentId) VALUES (?, ?, ?)",
            arrayOf<Any?>("Fake identity", 8L, "Fake identity"),
        )
        oldDb.execSQL(
            "INSERT INTO history (title, openedAt, contentId) VALUES (?, ?, ?)",
            arrayOf<Any?>("Сериал · S01E01 · Эпизод 1", 20L, "series:1"),
        )
        oldDb.execSQL(
            "INSERT INTO history (title, openedAt, contentId) VALUES (?, ?, ?)",
            arrayOf<Any?>("Series · S01E02 · Episode 2", 21L, "series:1"),
        )
        oldDb.execSQL(
            "INSERT INTO playback_progress (title, positionMs, durationMs, updatedAt, contentId) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("Сериал · S01E01 · Эпизод 1", 100L, 1000L, 30L, "series:1"),
        )
        oldDb.execSQL(
            "INSERT INTO playback_progress (title, positionMs, durationMs, updatedAt, contentId) VALUES (?, ?, ?, ?, ?)",
            arrayOf<Any?>("Сериал · S01E02 · Эпизод 2", 200L, 1000L, 31L, "series:1"),
        )
        oldDb.execSQL(
            "INSERT INTO downloads (title, filePath, completedAt, contentId) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>("Movie", "/offline/movie.mp4", 40L, "movie:1"),
        )
        oldDb.execSQL(
            "INSERT INTO watch_later (title, addedAt, contentId) VALUES (?, ?, ?)",
            arrayOf<Any?>("Legacy bookmark", 50L, "movie:2"),
        )
        oldDb.close()

        val migratedDb = helper.runMigrationsAndValidate(
            TEST_DATABASE,
            4,
            true,
            MoviaDatabase.MIGRATION_3_4,
        )
        helper.closeWhenFinished(migratedDb)

        migratedDb.query("SELECT mediaKey FROM favorites WHERE title = 'Localized movie title'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(MediaRef.storageKey("movie:1", "Localized movie title"), cursor.getString(0))
        }
        migratedDb.query("SELECT mediaKey FROM favorites WHERE title = 'Unresolved favorite'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(MediaRef.legacyStorageKey("Unresolved favorite"), cursor.getString(0))
        }
        migratedDb.query("SELECT mediaKey, contentId FROM favorites WHERE title = 'Fake identity'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(MediaRef.legacyStorageKey("Fake identity"), cursor.getString(0))
            check(cursor.isNull(1))
        }
        migratedDb.query("SELECT mediaKey FROM history ORDER BY mediaKey").use { cursor ->
            val episodeKeys = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(2, episodeKeys.size)
            assertNotEquals(episodeKeys[0], episodeKeys[1])
            assertEquals(2, episodeKeys.count { it.contains(":s1:e") })
        }
        migratedDb.query("SELECT COUNT(*) FROM playback_progress").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        migratedDb.query("SELECT mediaKey FROM downloads WHERE title = 'Movie'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(MediaRef.storageKey("movie:1", "Movie"), cursor.getString(0))
        }
        migratedDb.query("SELECT COUNT(*) FROM watch_later WHERE title = 'Legacy bookmark'").use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    private companion object {
        const val TEST_DATABASE = "movia-v3-to-v4-migration"
        const val V1_DATABASE = "movia-v1-to-v4-migration"
    }

    private fun assertLegacyKey(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        title: String,
    ) {
        database.query("SELECT mediaKey, contentId FROM $table WHERE title = ?", arrayOf(title)).use { cursor ->
            check(cursor.moveToFirst())
            assertEquals(MediaRef.legacyStorageKey(title), cursor.getString(0))
            check(cursor.isNull(1))
        }
    }
}
