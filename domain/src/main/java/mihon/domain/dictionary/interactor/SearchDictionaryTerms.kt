package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.repository.DictionaryRepository

/**
 * Interactor for searching dictionary terms.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun search(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        var formattedQuery = query.trim()
        return dictionaryRepository.searchTerms(formattedQuery, dictionaryIds)
    }

    suspend fun getByExpression(expression: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        return dictionaryRepository.getTermsByExpression(expression, dictionaryIds)
    }
}
