package mihon.domain.dictionary.model

/**
 * Represents metadata for a term (frequency, pitch accent, phonetic transcription).
 */
data class DictionaryTermMeta(
    val id: Long = 0L,
    val dictionaryId: Long,
    val expression: String,
    val mode: TermMetaMode,
    val data: String, // JSON representation of the meta data
)

enum class TermMetaMode {
    FREQUENCY,
    PITCH,
    IPA,
    ;

    companion object {
        fun fromString(value: String): TermMetaMode {
            return when (value.lowercase()) {
                "freq" -> FREQUENCY
                "pitch" -> PITCH
                "ipa" -> IPA
                else -> throw IllegalArgumentException("Unknown term meta mode: $value")
            }
        }
    }

    fun toDbString(): String {
        return when (this) {
            FREQUENCY -> "freq"
            PITCH -> "pitch"
            IPA -> "ipa"
        }
    }
}
