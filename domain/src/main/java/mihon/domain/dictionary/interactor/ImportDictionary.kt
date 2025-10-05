package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.repository.DictionaryRepository

/**
 * Interactor for importing compatible dictionaries.
 */
class ImportDictionary(
    private val dictionaryRepository: DictionaryRepository,
) {
    /**
     * Imports a complete dictionary.
     *
     * @param index The parsed index.json metadata
     * @param tags The list of tags from tag_bank files
     * @param terms The list of terms from term_bank files
     * @param kanji The list of kanji from kanji_bank files
     * @param termMeta The list of term metadata from term_meta_bank files
     * @param kanjiMeta The list of kanji metadata from kanji_meta_bank files
     * @return Pair where first is the ID of the imported dictionary and second is a list of warnings
     */
    suspend fun import(
        index: DictionaryIndex,
        tags: List<DictionaryTag>,
        terms: List<DictionaryTerm>,
        kanji: List<DictionaryKanji>,
        termMeta: List<DictionaryTermMeta>,
        kanjiMeta: List<DictionaryKanjiMeta>,
    ): Pair<Long, List<String>> {
        // Create the dictionary record
        val dictionary = Dictionary(
            title = index.title,
            revision = index.revision,
            version = index.effectiveVersion,
            author = index.author,
            url = index.url,
            description = index.description,
            attribution = index.attribution,
        )

        val dictionaryId = dictionaryRepository.insertDictionary(dictionary)
        val warnings = mutableListOf<String>()

        // Import tags from tag_bank files
        if (tags.isNotEmpty()) {
            val tagsWithDictId = tags.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTags(tagsWithDictId)
        }

        // Import tags from index.json tagMeta
        index.tagMeta?.let { tagMeta ->
            val indexTags = tagMeta.map { (name, meta) ->
                DictionaryTag(
                    dictionaryId = dictionaryId,
                    name = name,
                    category = meta.category,
                    order = meta.order,
                    notes = meta.notes,
                    score = meta.score,
                )
            }
            dictionaryRepository.insertTags(indexTags)
        }

        // Import terms
        if (terms.isNotEmpty()) {
            val termsWithDictId = terms.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTerms(termsWithDictId)
        } else {
            warnings.add("Dictionary contains no terms.")
        }

        // Import kanji
        if (kanji.isNotEmpty()) {
            val kanjiWithDictId = kanji.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertKanji(kanjiWithDictId)
        } else {
            warnings.add("Dictionary contains no kanji.")
        }

        // Import term meta
        if (termMeta.isNotEmpty()) {
            val termMetaWithDictId = termMeta.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertTermMeta(termMetaWithDictId)
        } else {
            warnings.add("Dictionary contains no term metadata.")
        }

        // Import kanji meta
        if (kanjiMeta.isNotEmpty()) {
            val kanjiMetaWithDictId = kanjiMeta.map { it.copy(dictionaryId = dictionaryId) }
            dictionaryRepository.insertKanjiMeta(kanjiMetaWithDictId)
        } else {
            warnings.add("Dictionary contains no kanji metadata.")
        }

        // return dictionaryId and warnings
        return dictionaryId to warnings.toList()
    }
}
