package mihon.domain.dictionary.service

import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.DictionaryIndex

/**
 * Service for parsing dictionary files.
 * This handles the JSON structure of dictionary bank files.
 */
interface DictionaryParser {
    /**
     * Parse index.json file.
     */
    fun parseIndex(json: String): DictionaryIndex

    /**
     * Parse tag_bank_*.json files.
     * Format: [[name, category, order, notes, score], ...]
     */
    fun parseTagBank(json: String): List<DictionaryTag>

    /**
     * Parse term_bank_*.json files.
     * V1 format: [[expression, reading, definitionTags, rules, score, ...glossary], ...]
     * V3 format: [[expression, reading, definitionTags, rules, score, glossary[], sequence, termTags], ...]
     */
    fun parseTermBank(json: String, version: Int): List<DictionaryTerm>

    /**
     * Parse kanji_bank_*.json files.
     * V1 format: [[character, onyomi, kunyomi, tags, ...meanings], ...]
     * V3 format: [[character, onyomi, kunyomi, tags, meanings, stats], ...]
     */
    fun parseKanjiBank(json: String, version: Int): List<DictionaryKanji>

    /**
     * Parse term_meta_bank_*.json files.
     * Format: [[expression, mode, data], ...]
     */
    fun parseTermMetaBank(json: String): List<DictionaryTermMeta>

    /**
     * Parse kanji_meta_bank_*.json files.
     * Format: [[character, mode, data], ...]
     */
    fun parseKanjiMetaBank(json: String): List<DictionaryKanjiMeta>
}

/**
 * Exception thrown when parsing the importing dictionary fails.
 */
class DictionaryParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
