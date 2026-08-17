package app.viora.android.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.vioraDataStore by preferencesDataStore(name = "viora_preferences")

data class PlaybackPreferences(
    val audio: String = "Auto",
    val quality: String = "Auto",
    val subtitlesEnabled: Boolean = false,
    val autoNextEnabled: Boolean = true,
    val wifiOnlyDownloads: Boolean = true,
)

class VioraPreferencesRepository(
    private val context: Context,
) {
    private object Keys {
        val audio = stringPreferencesKey("playback_audio")
        val quality = stringPreferencesKey("playback_quality")
        val subtitles = booleanPreferencesKey("playback_subtitles")
        val autoNext = booleanPreferencesKey("playback_auto_next")
        val wifiOnlyDownloads = booleanPreferencesKey("downloads_wifi_only")
        val favorites = stringSetPreferencesKey("library_favorites")
    }

    private val safeData: Flow<Preferences> = context.vioraDataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
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
        context.vioraDataStore.edit { prefs ->
            val next = prefs[Keys.favorites].orEmpty().toMutableSet()
            if (favorite) next += title else next -= title
            prefs[Keys.favorites] = next
        }
    }
}
