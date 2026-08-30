package app.movia.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.movia.android.data.library.LegacyLibrarySnapshot
import app.movia.android.domain.model.PlaybackProgress
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.moviaDataStore by preferencesDataStore(name = "movia_preferences")
private const val ENTRY_SEPARATOR = "\u001F"
private const val LIST_SEPARATOR = "\u001E"

data class AppPreferences(
    val themeMode: String = "DARK",
    val highContrast: Boolean = false,
    val persistentSeekButtons: Boolean = false,
    val notificationsEnabled: Boolean = true,
)

data class PlaybackPreferences(
    val audio: String = "Auto",
    val quality: String = "Auto",
    val subtitlesEnabled: Boolean = false,
    val autoNextEnabled: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
)

data class TitlePlaybackPreferences(
    val audio: String? = null,
    val quality: String? = null,
)

class MoviaPreferencesRepository(
    private val context: Context,
) {
    private object Keys {
        val audio = stringPreferencesKey("playback_audio")
        val quality = stringPreferencesKey("playback_quality")
        val subtitles = booleanPreferencesKey("playback_subtitles")
        val autoNext = booleanPreferencesKey("playback_auto_next")
        val wifiOnlyDownloads = booleanPreferencesKey("downloads_wifi_only")
        val themeMode = stringPreferencesKey("appearance_theme_mode")
        val highContrast = booleanPreferencesKey("accessibility_high_contrast")
        val persistentSeekButtons = booleanPreferencesKey("accessibility_persistent_seek_buttons")
        val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
        val titleAudioOverrides = stringSetPreferencesKey("title_audio_overrides")
        val titleQualityOverrides = stringSetPreferencesKey("title_quality_overrides")

        // Legacy keys: read only during the one-time Room migration.
        val legacyFavorites = stringSetPreferencesKey("library_favorites")
        val legacyWatchLater = stringSetPreferencesKey("library_watch_later")
        val legacyDownloads = stringSetPreferencesKey("library_downloads")
        val legacyHistory = stringPreferencesKey("library_history")
        val legacySearchHistory = stringPreferencesKey("search_history")
        val legacyLastTitle = stringPreferencesKey("last_playback_title")
        val legacyLastPosition = longPreferencesKey("last_playback_position_ms")
        val legacyLastDuration = longPreferencesKey("last_playback_duration_ms")
        val roomLibraryMigrated = booleanPreferencesKey("room_library_migrated_v1")
    }

    private val safeData: Flow<Preferences> = context.moviaDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val appPreferences: Flow<AppPreferences> = safeData.map { prefs ->
        AppPreferences(
            themeMode = prefs[Keys.themeMode] ?: "DARK",
            highContrast = prefs[Keys.highContrast] ?: false,
            persistentSeekButtons = prefs[Keys.persistentSeekButtons] ?: false,
            notificationsEnabled = prefs[Keys.notificationsEnabled] ?: true,
        )
    }

    val playbackPreferences: Flow<PlaybackPreferences> = safeData.map { prefs ->
        PlaybackPreferences(
            audio = prefs[Keys.audio] ?: "Auto",
            quality = prefs[Keys.quality] ?: "Auto",
            subtitlesEnabled = prefs[Keys.subtitles] ?: false,
            autoNextEnabled = prefs[Keys.autoNext] ?: true,
            wifiOnlyDownloads = prefs[Keys.wifiOnlyDownloads] ?: true,
        )
    }

    fun titlePlaybackPreferences(title: String): Flow<TitlePlaybackPreferences> = safeData.map { prefs ->
        TitlePlaybackPreferences(
            audio = findOverride(prefs[Keys.titleAudioOverrides].orEmpty(), title),
            quality = findOverride(prefs[Keys.titleQualityOverrides].orEmpty(), title),
        )
    }

    suspend fun setThemeMode(value: String) {
        context.moviaDataStore.edit { it[Keys.themeMode] = value }
    }

    suspend fun setHighContrast(value: Boolean) {
        context.moviaDataStore.edit { it[Keys.highContrast] = value }
    }

    suspend fun setPersistentSeekButtons(value: Boolean) {
        context.moviaDataStore.edit { it[Keys.persistentSeekButtons] = value }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.moviaDataStore.edit { it[Keys.notificationsEnabled] = value }
    }

    suspend fun setAudio(value: String) {
        context.moviaDataStore.edit { it[Keys.audio] = value }
    }

    suspend fun setQuality(value: String) {
        context.moviaDataStore.edit { it[Keys.quality] = value }
    }

    suspend fun setSubtitlesEnabled(value: Boolean) {
        context.moviaDataStore.edit { it[Keys.subtitles] = value }
    }

    suspend fun setAutoNextEnabled(value: Boolean) {
        context.moviaDataStore.edit { it[Keys.autoNext] = value }
    }

    suspend fun setWifiOnlyDownloads(value: Boolean) {
        context.moviaDataStore.edit { it[Keys.wifiOnlyDownloads] = value }
    }

    suspend fun setTitleAudio(title: String, value: String?) {
        updateOverride(Keys.titleAudioOverrides, title, value)
    }

    suspend fun setTitleQuality(title: String, value: String?) {
        updateOverride(Keys.titleQualityOverrides, title, value)
    }

    suspend fun needsRoomLibraryMigration(): Boolean =
        !(safeData.first()[Keys.roomLibraryMigrated] ?: false)

    suspend fun readLegacyLibrarySnapshot(): LegacyLibrarySnapshot {
        val prefs = safeData.first()
        return LegacyLibrarySnapshot(
            favorites = prefs[Keys.legacyFavorites].orEmpty(),
            watchLater = prefs[Keys.legacyWatchLater].orEmpty(),
            downloads = prefs[Keys.legacyDownloads].orEmpty(),
            history = prefs[Keys.legacyHistory]
                ?.split(LIST_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            recentSearches = prefs[Keys.legacySearchHistory]
                ?.split(LIST_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            lastProgress = PlaybackProgress(
                title = prefs[Keys.legacyLastTitle].orEmpty(),
                positionMs = prefs[Keys.legacyLastPosition] ?: 0L,
                durationMs = prefs[Keys.legacyLastDuration] ?: 0L,
            ),
        )
    }

    suspend fun finishRoomLibraryMigration() {
        context.moviaDataStore.edit { prefs ->
            prefs[Keys.roomLibraryMigrated] = true
            prefs.remove(Keys.legacyFavorites)
            prefs.remove(Keys.legacyWatchLater)
            prefs.remove(Keys.legacyDownloads)
            prefs.remove(Keys.legacyHistory)
            prefs.remove(Keys.legacySearchHistory)
            prefs.remove(Keys.legacyLastTitle)
            prefs.remove(Keys.legacyLastPosition)
            prefs.remove(Keys.legacyLastDuration)
        }
    }

    private suspend fun updateOverride(
        key: Preferences.Key<Set<String>>,
        title: String,
        value: String?,
    ) {
        context.moviaDataStore.edit { prefs ->
            val entries = prefs[key].orEmpty().toMutableSet()
            entries.removeAll { entry -> entry.substringBefore(ENTRY_SEPARATOR) == title }
            if (value != null) entries += "$title$ENTRY_SEPARATOR$value"
            prefs[key] = entries
        }
    }

    private fun findOverride(entries: Set<String>, title: String): String? = entries
        .firstOrNull { entry -> entry.substringBefore(ENTRY_SEPARATOR) == title }
        ?.substringAfter(ENTRY_SEPARATOR, missingDelimiterValue = "")
        ?.takeIf { it.isNotEmpty() }
}
