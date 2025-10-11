package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import dev.esnault.wanakana.core.Wanakana

/**
 * Interactor for searching dictionary terms.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun search(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        var formattedQuery = query.trim()

        if (Wanakana.isRomaji(formattedQuery) || Wanakana.isMixed(formattedQuery)) {
            formattedQuery = Wanakana.toKana(formattedQuery)
        }

        return dictionaryRepository.searchTerms(formattedQuery, dictionaryIds)
    }

    suspend fun getTermMeta(expressions: List<String>, dictionaryIds: List<Long>): Map<String, List<DictionaryTermMeta>> {
        val allMeta = mutableMapOf<String, MutableList<DictionaryTermMeta>>()

        expressions.forEach { expression ->
            val meta = dictionaryRepository.getTermMetaForExpression(expression, dictionaryIds)
            allMeta[expression] = meta.toMutableList()
        }

        return allMeta
    }
}
