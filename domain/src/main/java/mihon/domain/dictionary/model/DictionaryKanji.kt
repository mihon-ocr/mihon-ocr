package mihon.domain.dictionary.model

/**
 * Represents a kanji entry in a dictionary.
 */
data class DictionaryKanji(
    val id: Long = 0L,
    val dictionaryId: Long,
    val character: String,
    val onyomi: String, // Comma-separated readings
    val kunyomi: String, // Comma-separated readings
    val tags: String?, // Comma-separated tags
    val meanings: List<String>,
    val stats: Map<String, String>? = null, // JSON object of stats
)
