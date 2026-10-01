package app.movia.android.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.movia.android.data.catalog.DemoCatalogRepository
import app.movia.android.domain.model.MediaContent
import app.movia.android.ui.navigation.MoviaPersonCredit
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PersonFilmographyUiState(
    val name: String = "",
    val photoUrl: String? = null,
    val credit: MoviaPersonCredit = MoviaPersonCredit.ACTOR,
    val items: List<MediaContent> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

class PersonFilmographyViewModel : ViewModel() {
    private val mutableUiState = MutableStateFlow(PersonFilmographyUiState())
    val uiState: StateFlow<PersonFilmographyUiState> = mutableUiState.asStateFlow()

    private var loadJob: Job? = null
    private var currentKey: Triple<String, String?, MoviaPersonCredit>? = null

    fun load(
        name: String,
        photoUrl: String?,
        credit: MoviaPersonCredit,
        force: Boolean = false,
    ) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val key = Triple(cleanName, photoUrl, credit)
        if (!force && key == currentKey) return
        currentKey = key
        loadJob?.cancel()

        val cached = relevantItems(DemoCatalogRepository.all(), cleanName, credit)
        mutableUiState.value = PersonFilmographyUiState(
            name = cleanName,
            photoUrl = photoUrl?.takeIf { it.isNotBlank() },
            credit = credit,
            items = cached,
            isLoading = cached.isEmpty(),
        )

        loadJob = viewModelScope.launch {
            val scope = when (credit) {
                MoviaPersonCredit.ACTOR -> "actor"
                MoviaPersonCredit.DIRECTOR -> "director"
            }
            val result = withContext(Dispatchers.IO) {
                try {
                    DemoCatalogRepository.getPersonProjects(
                        name = cleanName,
                        creditScope = scope,
                        limit = 200,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }

            if (currentKey != key) return@launch
            val combined = (result?.projects.orEmpty() + cached)
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<MediaContent> { it.year }
                        .thenByDescending { it.premiereDate.orEmpty() }
                        .thenBy { it.title.lowercase(Locale.ROOT) },
                )
            mutableUiState.value = mutableUiState.value.copy(
                photoUrl = mutableUiState.value.photoUrl
                    ?: result?.person?.photoUrl?.takeIf { it.isNotBlank() },
                items = combined,
                isLoading = false,
                errorMessage = if (combined.isEmpty()) "Фильмография пока недоступна" else null,
            )
        }
    }

    private fun relevantItems(
        items: List<MediaContent>,
        name: String,
        credit: MoviaPersonCredit,
    ): List<MediaContent> = items
        .asSequence()
        .filter { item ->
            when (credit) {
                MoviaPersonCredit.ACTOR -> item.cast.any { samePersonName(it.name, name) }
                MoviaPersonCredit.DIRECTOR -> samePersonName(item.director, name)
            }
        }
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<MediaContent> { it.year }
                .thenByDescending { it.premiereDate.orEmpty() }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        )
        .toList()

    private fun samePersonName(left: String?, right: String?): Boolean =
        normalizePersonName(left) == normalizePersonName(right)

    private fun normalizePersonName(value: String?): String = value.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
}
