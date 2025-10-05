package mihon.domain.dictionary.interactor

import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.repository.DictionaryRepository

class DictionaryInteractor(
    private val dictionaryRepository: DictionaryRepository,
) {
    suspend fun getAllDictionaries(): List<Dictionary> {
        return dictionaryRepository.getAllDictionaries()
    }

    suspend fun getDictionary(dictionaryId: Long): Dictionary? {
        return dictionaryRepository.getDictionary(dictionaryId)
    }

    suspend fun updateDictionary(dictionary: Dictionary) {
        dictionaryRepository.updateDictionary(dictionary)
    }

    suspend fun deleteDictionary(dictionaryId: Long) {
        dictionaryRepository.deleteDictionary(dictionaryId)
    }
}
