package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.dictionary.DictionaryTermCard
import eu.kanade.presentation.dictionary.SearchBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ResizableSheet
import tachiyomi.presentation.core.components.SheetValue
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

@Composable
fun OcrResultBottomSheet(
    onDismissRequest: () -> Unit,
    text: String,
    onCopyText: () -> Unit,
    searchScreenModel: DictionarySearchScreenModel,
) {
    val searchState by searchScreenModel.state.collectAsState()

    // Automatically search dictionary for the OCR text
    LaunchedEffect(text) {
        if (text.isNotBlank()) {
            searchScreenModel.updateQuery(text)
            searchScreenModel.search()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ResizableSheet(
            onDismissRequest = onDismissRequest,
            initialValue = SheetValue.PartiallyExpanded,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.LibraryBooks,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        text = stringResource(MR.strings.label_dictionary),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                // Dictionary search bar (editable, initialized with OCR text)
                SearchBar(
                    query = searchState.query,
                    onQueryChange = { query ->
                        searchScreenModel.updateQuery(query)
                    },
                    onSearch = {
                        searchScreenModel.search()
                    },
                    modifier = Modifier,
                )

                FilledTonalButton(
                    onClick = {
                        onCopyText()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                    Text(
                        text = stringResource(MR.strings.action_copy),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                // Dictionary search results section
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Dictionary results
                when {
                    searchState.isLoading -> {
                        // Initial state - loading dictionaries
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    searchState.dictionaries.isEmpty() || searchState.enabledDictionaryIds.isEmpty() -> {
                        // No dictionaries enabled
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(MR.strings.no_dictionaries_enabled),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    searchState.isSearching -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    searchState.searchResults.isEmpty() && searchState.hasSearched -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                        ) {
                            EmptyScreen(
                                stringRes = MR.strings.no_results_found,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    searchState.searchResults.isNotEmpty() -> {
                        LazyColumn(
                            contentPadding = PaddingValues(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(400.dp),
                        ) {
                            items(
                                items = searchState.searchResults.take(10), // Limit to first 10 results
                                key = { it.id },
                            ) { term ->
                                DictionaryTermCard(
                                    term = term,
                                    dictionaryName = searchState.dictionaries.find { it.id == term.dictionaryId }?.title ?: "",
                                    termMeta = searchState.termMetaMap[term.expression] ?: emptyList(),
                                    dictionaries = searchState.dictionaries,
                                    onClick = { /* TODO: Anki */ },
                                    onQueryChange = { query ->
                                        searchScreenModel.updateQuery(query)
                                    },
                                    onSearch = {
                                        searchScreenModel.search()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
