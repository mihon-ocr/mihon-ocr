package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.repository.DictionaryRepository

/**
 * Interactor for looking up dictionary kanji.
 */
class GetDictionaryKanji(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun getByCharacter(character: String, dictionaryIds: List<Long>): List<DictionaryKanji> {
        return dictionaryRepository.getKanjiByCharacter(character, dictionaryIds)
    }
}
