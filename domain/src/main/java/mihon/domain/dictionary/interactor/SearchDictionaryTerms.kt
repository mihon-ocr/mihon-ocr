package mihon.domain.dictionary.interactor

import dev.esnault.wanakana.core.Wanakana
import java.util.LinkedHashMap
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.repository.DictionaryRepository
import mihon.domain.dictionary.service.JapaneseDeinflector

/**
 * Interactor for searching dictionary terms.
 */
class SearchDictionaryTerms(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun search(query: String, dictionaryIds: List<Long>): List<DictionaryTerm> {
        if (dictionaryIds.isEmpty()) return emptyList()

        var formattedQuery = query.trim()
        if (formattedQuery.isEmpty()) return emptyList()

        if (Wanakana.isRomaji(formattedQuery) || Wanakana.isMixed(formattedQuery)) {
            formattedQuery = Wanakana.toKana(formattedQuery)
        }

        val candidateQueries = JapaneseDeinflector.deinflect(formattedQuery)
        if (candidateQueries.isEmpty()) return emptyList()

        val results = LinkedHashMap<Long, DictionaryTerm>(candidateQueries.size * 4)
        for (candidate in candidateQueries) {
            if (candidate.isBlank()) continue

            val matches = dictionaryRepository.searchTerms(candidate, dictionaryIds)
            for (term in matches) {
                if (results.putIfAbsent(term.id, term) == null && results.size >= MAX_RESULTS) {
                    break
                }
            }

            if (results.size >= MAX_RESULTS) {
                break
            }
        }

        return results.values.toList()
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

private const val MAX_RESULTS = 100
