package eu.kanade.tachiyomi.ui.dictionary

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.dictionary.DictionarySearchScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.flow.collectLatest
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object DictionaryTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            return TabOptions(
                index = 5u,
                title = stringResource(MR.strings.label_dictionary),
                icon = rememberVectorPainter(Icons.AutoMirrored.Outlined.LibraryBooks),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        // Could add functionality here if needed
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val screenModel = rememberScreenModel { DictionarySearchScreenModel() }
        val state by screenModel.state.collectAsState()

        DictionarySearchScreen(
            state = state,
            snackbarHostState = screenModel.snackbarHostState,
            onQueryChange = screenModel::updateQuery,
            onSearch = screenModel::search,
            onTermClick = { term ->
                // Could navigate to an Anki export in the future
                screenModel.selectTerm(term)
            },
        )

        LaunchedEffect(Unit) {
            screenModel.events.collectLatest { event ->
                when (event) {
                    is DictionarySearchScreenModel.Event.ShowError -> {
                        screenModel.snackbarHostState.showSnackbar(event.message)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}
