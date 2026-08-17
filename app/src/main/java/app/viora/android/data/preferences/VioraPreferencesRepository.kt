package app.viora.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.vioraDataStore by preferencesDataStore(name = "viora_preferences")
private const val ENTRY_SEPARATOR = "\u001F"
private const val LIST_SEPARATOR = "\u001E"


data class AppPreferences(
    val themeMode: String = "DARK",
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
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

data class PlaybackProgress(
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    val fraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
        } else {
            0f
        }
}

class VioraPreferencesRepository(
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
        val reducedMotion = booleanPreferencesKey("accessibility_reduced_motion")
        val notificationsEnabled = booleanPreferencesKey("notifications_enabled")
        val favorites = stringSetPreferencesKey("library_favorites")
        val watchLater = stringSetPreferencesKey("library_watch_later")
        val downloads = stringSetPreferencesKey("library_downloads")
        val history = stringPreferencesKey("library_history")
        val titleAudioOverrides = stringSetPreferencesKey("title_audio_overrides")
        val titleQualityOverrides = stringSetPreferencesKey("title_quality_overrides")
        val lastTitle = stringPreferencesKey("last_playback_title")
        val lastPosition = longPreferencesKey("last_playback_position_ms")
        val lastDuration = longPreferencesKey("last_playback_duration_ms")
    }

    private val safeData: Flow<Preferences> = context.vioraDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val appPreferences: Flow<AppPreferences> = safeData.map { prefs ->
        AppPreferences(
            themeMode = prefs[Keys.themeMode] ?: "DARK",
            highContrast = prefs[Keys.highContrast] ?: false,
            reducedMotion = prefs[Keys.reducedMotion] ?: false,
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

    val favorites: Flow<Set<String>> = safeData.map { prefs ->
        prefs[Keys.favorites].orEmpty()
    }

    val watchLater: Flow<Set<String>> = safeData.map { prefs ->
        prefs[Keys.watchLater].orEmpty()
    }

    val downloads: Flow<Set<String>> = safeData.map { prefs ->
        prefs[Keys.downloads].orEmpty()
    }

    val history: Flow<List<String>> = safeData.map { prefs ->
        prefs[Keys.history]
            ?.split(LIST_SEPARATOR)
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    val lastProgress: Flow<PlaybackProgress> = safeData.map { prefs ->
        PlaybackProgress(
            title = prefs[Keys.lastTitle].orEmpty(),
            positionMs = prefs[Keys.lastPosition] ?: 0L,
            durationMs = prefs[Keys.lastDuration] ?: 0L,
        )
    }

    fun titlePlaybackPreferences(title: String): Flow<TitlePlaybackPreferences> = safeData.map { prefs ->
        TitlePlaybackPreferences(
            audio = findOverride(prefs[Keys.titleAudioOverrides].orEmpty(), title),
            quality = findOverride(prefs[Keys.titleQualityOverrides].orEmpty(), title),
        )
    }

    suspend fun setThemeMode(value: String) {
        context.vioraDataStore.edit { it[Keys.themeMode] = value }
    }

    suspend fun setHighContrast(value: Boolean) {
        context.vioraDataStore.edit { it[Keys.highContrast] = value }
    }

    suspend fun setReducedMotion(value: Boolean) {
        context.vioraDataStore.edit { it[Keys.reducedMotion] = value }
    }

    suspend fun setNotificationsEnabled(value: Boolean) {
        context.vioraDataStore.edit { it[Keys.notificationsEnabled] = value }
    }

    suspend fun setAudio(value: String) {
        context.vioraDataStore.edit { it[Keys.audio] = value }
    }

    suspend fun setQuality(value: String) {
        context.vioraDataStore.edit { it[Keys.quality] = value }
    }

    suspend fun setSubtitlesEnabled(value: Boolean) {
        context.vioraDataStore.edit { it[Keys.subtitles] = value }
    }

    suspend fun setAutoNextEnabled(value: Boolean) {
        context.vioraDataStore.edit { it[Keys.autoNext] = value }
    }

    suspend fun setWifiOnlyDownloads(value: Boolean) {
        context.vioraDataStore.edit { it[Keys.wifiOnlyDownloads] = value }
    }

    suspend fun setFavorite(title: String, favorite: Boolean) {
        updateSet(Keys.favorites, title, favorite)
    }

    suspend fun setWatchLater(title: String, enabled: Boolean) {
        updateSet(Keys.watchLater, title, enabled)
    }

    suspend fun setDownloaded(title: String, enabled: Boolean) {
        updateSet(Keys.downloads, title, enabled)
    }

    suspend fun clearDownloaded() {
        context.vioraDataStore.edit { it.remove(Keys.downloads) }
    }

    suspend fun addHistory(title: String) {
        if (title.isBlank()) return
        context.vioraDataStore.edit { prefs ->
            val current = prefs[Keys.history]
                ?.split(LIST_SEPARATOR)
                ?.filter { it.isNotBlank() }
                .orEmpty()
                .toMutableList()
            current.remove(title)
            current.add(0, title)
            prefs[Keys.history] = current.take(30).joinToString(LIST_SEPARATOR)
        }
    }

    suspend fun clearHistory() {
        context.vioraDataStore.edit { it.remove(Keys.history) }
    }

    suspend fun setTitleAudio(title: String, value: String?) {
        updateOverride(Keys.titleAudioOverrides, title, value)
    }

    suspend fun setTitleQuality(title: String, value: String?) {
        updateOverride(Keys.titleQualityOverrides, title, value)
    }

    suspend fun saveProgress(title: String, positionMs: Long, durationMs: Long) {
        if (title.isBlank() || positionMs < 0L) return
        context.vioraDataStore.edit { prefs ->
            prefs[Keys.lastTitle] = title
            prefs[Keys.lastPosition] = positionMs
            if (durationMs > 0L) prefs[Keys.lastDuration] = durationMs
        }
    }


    private suspend fun updateSet(
        key: Preferences.Key<Set<String>>,
        title: String,
        enabled: Boolean,
    ) {
        context.vioraDataStore.edit { prefs ->
            val next = prefs[key].orEmpty().toMutableSet()
            if (enabled) next += title else next -= title
            prefs[key] = next
        }
    }

    private suspend fun updateOverride(
        key: Preferences.Key<Set<String>>,
        title: String,
        value: String?,
    ) {
        context.vioraDataStore.edit { prefs ->
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
