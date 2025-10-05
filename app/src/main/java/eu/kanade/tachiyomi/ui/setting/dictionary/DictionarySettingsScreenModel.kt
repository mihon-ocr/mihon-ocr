package eu.kanade.tachiyomi.ui.setting.dictionary

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.archiveReader
import mihon.domain.dictionary.interactor.DictionaryInteractor
import mihon.domain.dictionary.interactor.ImportDictionary
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryImportException
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.system.toast

class DictionarySettingsScreenModel(
    private val dictionaryInteractor: DictionaryInteractor = Injekt.get(),
    private val importDictionary: ImportDictionary = Injekt.get(),
    private val dictionaryParser: DictionaryParser = Injekt.get(),
) : StateScreenModel<DictionarySettingsScreenModel.State>(State()) {

    init {
        loadDictionaries()
    }

    private fun loadDictionaries() {
        screenModelScope.launch {
            try {
                val dictionaries = dictionaryInteractor.getAllDictionaries()
                mutableState.update {
                    it.copy(
                        dictionaries = dictionaries,
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to load dictionaries" }
                mutableState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load dictionaries",
                    )
                }
            }
        }
    }

    fun importDictionaryFromUri(context: Context, uri: Uri) {
        screenModelScope.launch {
            mutableState.update { it.copy(isImporting = true, importProgress = null, error = null) }

            try {
                val result = withContext(Dispatchers.IO) {
                    val file = UniFile.fromUri(context, uri)
                        ?: throw DictionaryImportException("Failed to open dictionary file")

                    if (!file.exists() || !file.isFile) {
                        throw DictionaryImportException("Invalid dictionary file")
                    }

                    // Extract and parse dictionary
                    file.archiveReader(context).use { reader ->
                        extractAndImportDictionary(reader)
                    }
                }

                mutableState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        successMessage = MR.strings.dictionary_import_success.getString(context),
                    )
                }

                result.second.forEach { warning ->
                    context.toast(warning)
                }

                // Reload dictionaries
                loadDictionaries()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to import dictionary" }
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        importProgress = null,
                        error = e.message ?: "Failed to import dictionary",
                    )
                }
            }
        }
    }

    private suspend fun extractAndImportDictionary(reader: ArchiveReader): Pair<Long, List<String>> {
        // Update progress
        mutableState.update { it.copy(importProgress = "Reading index.json...") }

        // Parse index.json
        val indexJson = reader.getInputStream("index.json")?.bufferedReader()?.use { it.readText() }
            ?: throw DictionaryImportException("index.json not found in dictionary archive")

        val index: DictionaryIndex = try {
            dictionaryParser.parseIndex(indexJson)
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse index.json", e)
        }

        mutableState.update { it.copy(importProgress = "Parsing dictionary files...") }

        // Parse all bank files in a single pass
        val tags = mutableListOf<DictionaryTag>()
        val terms = mutableListOf<DictionaryTerm>()
        val kanji = mutableListOf<DictionaryKanji>()
        val termMeta = mutableListOf<DictionaryTermMeta>()
        val kanjiMeta = mutableListOf<DictionaryKanjiMeta>()

        val tagRegex = Regex("^tag_bank_\\d+\\.json$")
        val termRegex = Regex("^term_bank_\\d+\\.json$")
        val kanjiRegex = Regex("^kanji_bank_\\d+\\.json$")
        val termMetaRegex = Regex("^term_meta_bank_\\d+\\.json$")
        val kanjiMetaRegex = Regex("^kanji_meta_bank_\\d+\\.json$")

        val fileEntries = mutableListOf<String>()
        reader.useEntries { entries ->
            entries
            .filter { it.isFile }
            .forEach { entry ->
                fileEntries.add(entry.name)
            }
        }

        fileEntries.forEach { entryName ->
            val json = reader.getInputStream(entryName)?.bufferedReader()?.use { it.readText() }
                ?: return@forEach

            val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')

            try {
                when {
                    fileName.matches(termMetaRegex) ->
                        termMeta.addAll(dictionaryParser.parseTermMetaBank(json))
                    fileName.matches(kanjiMetaRegex) ->
                        kanjiMeta.addAll(dictionaryParser.parseKanjiMetaBank(json))
                    fileName.matches(termRegex) ->
                        terms.addAll(dictionaryParser.parseTermBank(json, index.effectiveVersion))
                    fileName.matches(kanjiRegex) ->
                        kanji.addAll(dictionaryParser.parseKanjiBank(json, index.effectiveVersion))
                    fileName.matches(tagRegex) ->
                        tags.addAll(dictionaryParser.parseTagBank(json))
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to parse $fileName" }
            }
        }

        mutableState.update {
            it.copy(importProgress = "Importing to database...")
        }

        // Import to database
        return importDictionary.import(
            index = index,
            tags = tags,
            terms = terms,
            kanji = kanji,
            termMeta = termMeta,
            kanjiMeta = kanjiMeta,
        )
    }

    fun updateDictionary(dictionary: Dictionary) {
        screenModelScope.launch {
            try {
                dictionaryInteractor.updateDictionary(dictionary)
                loadDictionaries()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to update dictionary" }
                mutableState.update {
                    it.copy(error = e.message ?: "Failed to update dictionary")
                }
            }
        }
    }

    fun deleteDictionary(dictionaryId: Long) {
        screenModelScope.launch {
            try {
                dictionaryInteractor.deleteDictionary(dictionaryId)
                loadDictionaries()
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to delete dictionary" }
                mutableState.update {
                    it.copy(error = e.message ?: "Failed to delete dictionary")
                }
            }
        }
    }

    fun clearError() {
        mutableState.update { it.copy(error = null) }
    }

    fun clearSuccessMessage() {
        mutableState.update { it.copy(successMessage = null) }
    }

    @Immutable
    data class State(
        val dictionaries: List<Dictionary> = emptyList(),
        val isLoading: Boolean = true,
        val isImporting: Boolean = false,
        val importProgress: String? = null,
        val error: String? = null,
        val successMessage: String? = null,
    )
}
