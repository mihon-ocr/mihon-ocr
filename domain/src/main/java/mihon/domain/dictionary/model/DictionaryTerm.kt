package mihon.domain.dictionary.model

/**
 * Represents a term entry in a dictionary.
 */
data class DictionaryTerm(
    val id: Long = 0L,
    val dictionaryId: Long,
    val expression: String,
    val reading: String,
    val definitionTags: String?, // Comma-separated tags
    val rules: String?, // Comma-separated rules
    val score: Int,
    val glossary: List<GlossaryEntry>, // Serialized tree per definition entry
    val sequence: Long? = null,
    val termTags: String? = null, // Comma-separated tags
)
