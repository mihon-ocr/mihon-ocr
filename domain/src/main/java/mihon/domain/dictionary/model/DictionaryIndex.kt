package mihon.domain.dictionary.model

/**
 * Represents the index.json file structure of an importing dictionary.
 * This is used during import to parse the dictionary metadata.
 */
data class DictionaryIndex(
    val title: String,
    val revision: String,
    val format: Int? = null,
    val version: Int? = null,
    val author: String? = null,
    val url: String? = null,
    val description: String? = null,
    val attribution: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val sequenced: Boolean? = null,
    val frequencyMode: String? = null,
    val tagMeta: Map<String, TagMeta>? = null,
) {
    /**
     * Gets the effective version number (format takes precedence over version).
     */
    val effectiveVersion: Int
        get() = format ?: version ?: 1

    /**
     * Tag metadata from the index file.
     */
    data class TagMeta(
        val category: String,
        val order: Int,
        val notes: String,
        val score: Int,
    )
}
