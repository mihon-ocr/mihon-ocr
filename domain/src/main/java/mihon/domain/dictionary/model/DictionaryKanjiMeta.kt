package mihon.domain.dictionary.model

/**
 * Represents metadata for a kanji (frequency, etc.).
 */
data class DictionaryKanjiMeta(
    val id: Long = 0L,
    val dictionaryId: Long,
    val character: String,
    val mode: KanjiMetaMode,
    val data: String, // JSON representation of the meta data
)

enum class KanjiMetaMode {
    FREQUENCY,
    ;

    companion object {
        fun fromString(value: String): KanjiMetaMode {
            return when (value.lowercase()) {
                "freq" -> FREQUENCY
                else -> throw IllegalArgumentException("Unknown kanji meta mode: $value")
            }
        }
    }

    fun toDbString(): String {
        return when (this) {
            FREQUENCY -> "freq"
        }
    }
}
