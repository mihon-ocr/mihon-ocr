package mihon.domain.dictionary.model

/**
 * Result of importing a dictionary.
 */
data class ImportResult(
    val dictionaryId: Long,
    val title: String,
    val termCount: Int,
    val kanjiCount: Int,
    val tagCount: Int,
)

/**
 * Exception thrown when dictionary import fails.
 */
class DictionaryImportException(message: String, cause: Throwable? = null) : Exception(message, cause)
