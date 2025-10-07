package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.interactor.SearchDictionaryTerms
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DictionarySearchScreenModel(
    private val searchDictionaryTerms: SearchDictionaryTerms = Injekt.get(),
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
) : StateScreenModel<DictionarySearchScreenModel.State>(State()) {

    val snackbarHostState = SnackbarHostState()

    private val _events = Channel<Event>()
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        screenModelScope.launch {
            loadDictionaries()
        }
    }

    private suspend fun loadDictionaries() {
        try {
            val dictionaries = dictionaryInteractor.getAllDictionaries()
            mutableState.update { state ->
                state.copy(
                    dictionaries = dictionaries,
                    enabledDictionaryIds = dictionaries.filter { it.isEnabled }.map { it.id },
                    isLoading = false,
                )
            }
        } catch (e: Exception) {
            mutableState.update { it.copy(isLoading = false) }
            _events.send(Event.ShowError(e.message ?: "Failed to load dictionaries"))
        }
    }

    fun updateQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun search() {
        val query = state.value.query
        if (query.isBlank()) {
            mutableState.update { it.copy(searchResults = emptyList()) }
            return
        }

        mutableState.update { it.copy(isSearching = true) }

        screenModelScope.launch {
            try {
                val enabledDictionaryIds = state.value.enabledDictionaryIds
                if (enabledDictionaryIds.isEmpty()) {
                    _events.send(Event.ShowError("No dictionaries enabled"))
                    mutableState.update { it.copy(isSearching = false, searchResults = emptyList()) }
                    return@launch
                }

                val results = searchDictionaryTerms.search(query, enabledDictionaryIds)
                mutableState.update {
                    it.copy(
                        searchResults = results,
                        isSearching = false,
                        hasSearched = true,
                    )
                }
            } catch (e: Exception) {
                mutableState.update { it.copy(isSearching = false) }
                _events.send(Event.ShowError(e.message ?: "Search failed"))
            }
        }
    }

    fun selectTerm(term: DictionaryTerm) {
        mutableState.update { it.copy(selectedTerm = term) }
    }

    @Immutable
    data class State(
        val query: String = "",
        val searchResults: List<DictionaryTerm> = emptyList(),
        val dictionaries: List<Dictionary> = emptyList(),
        val enabledDictionaryIds: List<Long> = emptyList(),
        val selectedTerm: DictionaryTerm? = null,
        val isLoading: Boolean = true,
        val isSearching: Boolean = false,
        val hasSearched: Boolean = false,
    )

    sealed interface Event {
        data class ShowError(val message: String) : Event
    }
}
