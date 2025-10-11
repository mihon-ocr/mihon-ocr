package eu.kanade.presentation.dictionary

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

@Composable
fun DictionarySearchScreen(
    state: DictionarySearchScreenModel.State,
    snackbarHostState: SnackbarHostState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onTermClick: (DictionaryTerm) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_dictionary),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // Search bar
            SearchBar(
                query = state.query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                isSearching = state.isSearching,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            HorizontalDivider()

            // Results
            when {
                state.isLoading -> {
                    LoadingScreen(Modifier.fillMaxSize())
                }
                state.isSearching -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.searchResults.isEmpty() && state.hasSearched -> {
                    EmptyScreen(
                        stringRes = MR.strings.no_results_found,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                state.searchResults.isNotEmpty() -> {
                    SearchResultsList(
                        results = state.searchResults,
                        dictionaries = state.dictionaries,
                        termMetaMap = state.termMetaMap,
                        onTermClick = onTermClick,
                    )
                }
                else -> {
                    EmptyScreen(
                        stringRes = MR.strings.information_empty_dictionary_search,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(MR.strings.action_search)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (isSearching) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

@Composable
private fun SearchResultsList(
    results: List<DictionaryTerm>,
    dictionaries: List<Dictionary>,
    termMetaMap: Map<String, List<DictionaryTermMeta>>,
    onTermClick: (DictionaryTerm) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = results,
            key = { it.id },
        ) { term ->
            DictionaryTermCard(
                term = term,
                dictionaryName = dictionaries.find { it.id == term.dictionaryId }?.title ?: "",
                termMeta = termMetaMap[term.expression] ?: emptyList(),
                dictionaries = dictionaries,
                onClick = { onTermClick(term) },
            )
        }
    }
}

@Composable
private fun DictionaryTermCard(
    term: DictionaryTerm,
    dictionaryName: String,
    termMeta: List<DictionaryTermMeta>,
    dictionaries: List<Dictionary>,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Expression and reading
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = term.expression,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (term.reading.isNotBlank() && term.reading != term.expression) {
                        Text(
                            text = term.reading,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Display frequency indicator if available
                val frequencyData = remember(termMeta) {
                    FrequencyFormatter.parseFrequencies(termMeta)
                }

                Column(
                    horizontalAlignment = Alignment.End,
                ) {
                    frequencyData.take(3).forEach { freqInfo ->
                        // Find the dictionary name for the frequency entry
                        val sourceDictName = dictionaries.find { it.id == freqInfo.dictionaryId }?.title
                            ?: dictionaryName

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = freqInfo.frequency,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )

                            Spacer(modifier = Modifier.size(6.dp))

                            Text(
                                text = sourceDictName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Check if this is a "forms" entry - if so, show it differently
            val isFormsEntry = term.definitionTags?.contains("forms") == true

            // Parse and display glossary content
            val glossaryData = remember(term.glossary, isFormsEntry) {
                GlossaryFormatter.parseGlossary(term.glossary, isFormsEntry)
            }

            val formattedItems = remember(glossaryData) {
                GlossaryFormatter.formatForDisplay(glossaryData)
            }

            // Display definition tags if present and not forms
            val definitionTags = term.definitionTags
            if (!isFormsEntry && !definitionTags.isNullOrBlank()) {
                Text(
                    text = definitionTags.replace(",", " · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            // Display formatted glossary items
            formattedItems.take(5).forEach { item ->
                when (item) {
                    is FormattedGlossaryItem.PartOfSpeech -> {
                        Text(
                            text = item.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontStyle = FontStyle.Italic,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    is FormattedGlossaryItem.Definition -> {
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                text = "◦ ",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    is FormattedGlossaryItem.MultipleDefinitions -> {
                        item.definitions.forEach { def ->
                            Row(
                                modifier = Modifier.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Text(
                                    text = "◦ ",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = def,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    is FormattedGlossaryItem.AlternativeForms -> {
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Forms: ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = item.forms.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }
                    is FormattedGlossaryItem.Info -> {
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).padding(end = 4.dp),
                                tint = MaterialTheme.colorScheme.tertiary,
                            )
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontStyle = FontStyle.Italic,
                            )
                        }
                    }
                    is FormattedGlossaryItem.Reference -> {
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp).padding(end = 4.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                            val annotatedString = buildAnnotatedString {
                                // Find the link text in the full text
                                val startIndex = item.fullText.indexOf(item.linkText)
                                if (startIndex != -1) {
                                    val beforeLink = item.fullText.substring(0, startIndex)
                                    val afterLink = item.fullText.substring(startIndex + item.linkText.length)

                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                        append(beforeLink)
                                    }

                                    pushStringAnnotation(tag = "LINK", annotation = item.linkQuery)
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    ) {
                                        append(item.linkText)
                                    }
                                    pop()

                                    withStyle(SpanStyle(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 11.sp,
                                    )) {
                                        append(afterLink)
                                    }
                                } else {
                                    // Fallback if parsing fails
                                    append(item.fullText)
                                }
                            }

                            ClickableText(
                                text = annotatedString,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                onClick = { offset ->
                                    annotatedString.getStringAnnotations(
                                        tag = "LINK",
                                        start = offset,
                                        end = offset,
                                    ).firstOrNull()?.let { annotation ->
                                        // TODO: Navigate to the referenced term
                                        // onQueryChange(annotation.item)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (formattedItems.size > 5) {
                Text(
                    text = stringResource(MR.strings.more_definitions, formattedItems.size - 5),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // Dictionary source
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = dictionaryName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
