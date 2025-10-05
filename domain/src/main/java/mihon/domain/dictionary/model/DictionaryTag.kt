package mihon.domain.dictionary.model

/**
 * Represents a tag used in dictionary entries.
 */
data class DictionaryTag(
    val id: Long = 0L,
    val dictionaryId: Long,
    val name: String,
    val category: String,
    val order: Int,
    val notes: String,
    val score: Int,
)
