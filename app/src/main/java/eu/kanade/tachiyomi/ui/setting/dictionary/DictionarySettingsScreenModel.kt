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
        mutableState.update { it.copy(importProgress = "Reading index.json...") }

        // Parse index.json
        val indexJson = reader.getInputStream("index.json")?.bufferedReader()?.use { it.readText() }
            ?: throw DictionaryImportException("index.json not found in dictionary archive")

        val index: DictionaryIndex = try {
            dictionaryParser.parseIndex(indexJson)
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse index.json", e)
        }

        mutableState.update { it.copy(importProgress = "Importing dictionary info...") }

        val dictionaryId = importDictionary.createDictionary(index)
        val warnings = mutableListOf<String>()

        importDictionary.importIndexTags(index, dictionaryId)

        mutableState.update { it.copy(importProgress = "Parsing and importing dictionary files...") }

        val tagRegex = Regex("^tag_bank_\\d+\\.json$")
        val termRegex = Regex("^term_bank_\\d+\\.json$")
        val kanjiRegex = Regex("^kanji_bank_\\d+\\.json$")
        val termMetaRegex = Regex("^term_meta_bank_\\d+\\.json$")
        val kanjiMetaRegex = Regex("^kanji_meta_bank_\\d+\\.json$")

        var hasTerms = false
        var hasKanji = false
        var hasTermMeta = false
        var hasKanjiMeta = false

        reader.useEntriesAndStreams { entry, stream ->
            if (!entry.isFile) return@useEntriesAndStreams

            val entryName = entry.name
            val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')

            // Skip index.json as it's already processed
            if (fileName == "index.json") return@useEntriesAndStreams

            val dataJson = stream.bufferedReader().readText()

            try {
                when {
                    fileName.matches(termMetaRegex) -> {
                        val termMeta = dictionaryParser.parseTermMetaBank(dataJson)
                        if (termMeta.isNotEmpty()) hasTermMeta = true
                        importDictionary.importTermMeta(termMeta, dictionaryId)
                    }
                    fileName.matches(kanjiMetaRegex) -> {
                        val kanjiMeta = dictionaryParser.parseKanjiMetaBank(dataJson)
                        if (kanjiMeta.isNotEmpty()) hasKanjiMeta = true
                        importDictionary.importKanjiMeta(kanjiMeta, dictionaryId)
                    }
                    fileName.matches(termRegex) -> {
                        val terms = dictionaryParser.parseTermBank(dataJson, index.effectiveVersion)
                        if (terms.isNotEmpty()) hasTerms = true
                        importDictionary.importTerms(terms, dictionaryId)
                    }
                    fileName.matches(kanjiRegex) -> {
                        val kanji = dictionaryParser.parseKanjiBank(dataJson, index.effectiveVersion)
                        if (kanji.isNotEmpty()) hasKanji = true
                        importDictionary.importKanji(kanji, dictionaryId)
                    }
                    fileName.matches(tagRegex) -> {
                        val tags = dictionaryParser.parseTagBank(dataJson)
                        importDictionary.importTags(tags, dictionaryId)
                    }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to parse or import $fileName" }
            }

            logcat(LogPriority.INFO) { "Successfully imported $fileName" }
        }

        if (!hasTerms) warnings.add("Dictionary contains no terms.")
        if (!hasKanji) warnings.add("Dictionary contains no kanji.")
        if (!hasTermMeta) warnings.add("Dictionary contains no term metadata.")
        if (!hasKanjiMeta) warnings.add("Dictionary contains no kanji metadata.")

        return dictionaryId to warnings
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
