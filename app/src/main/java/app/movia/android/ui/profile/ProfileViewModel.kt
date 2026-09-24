package app.movia.android.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.movia.android.data.preferences.AppPreferences
import app.movia.android.data.preferences.MoviaPreferencesRepository
import app.movia.android.data.preferences.PlaybackPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProfileUiState(
    val appPreferences: AppPreferences = AppPreferences(),
    val playbackPreferences: PlaybackPreferences = PlaybackPreferences(),
)

class ProfileViewModel(
    private val repository: MoviaPreferencesRepository,
) : ViewModel() {
    val uiState: StateFlow<ProfileUiState> = combine(
        repository.appPreferences,
        repository.playbackPreferences,
    ) { app, playback -> ProfileUiState(app, playback) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ProfileUiState())

    fun setAudio(value: String) { viewModelScope.launch { repository.setAudio(value) } }
    fun setQuality(value: String) { viewModelScope.launch { repository.setQuality(value) } }
    fun setSubtitlesEnabled(value: Boolean) { viewModelScope.launch { repository.setSubtitlesEnabled(value) } }
    fun setAutoNextEnabled(value: Boolean) { viewModelScope.launch { repository.setAutoNextEnabled(value) } }
    fun setPersistentSeekButtons(value: Boolean) { viewModelScope.launch { repository.setPersistentSeekButtons(value) } }
    fun setWifiOnlyDownloads(value: Boolean) { viewModelScope.launch { repository.setWifiOnlyDownloads(value) } }
    fun setThemeMode(value: String) { viewModelScope.launch { repository.setThemeMode(value) } }
    fun setHighContrast(value: Boolean) { viewModelScope.launch { repository.setHighContrast(value) } }

    companion object {
        fun factory(repository: MoviaPreferencesRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass.isAssignableFrom(ProfileViewModel::class.java))
                    return ProfileViewModel(repository) as T
                }
            }
    }
}
