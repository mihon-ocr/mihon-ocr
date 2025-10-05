package mihon.data.dictionary

import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.KanjiMetaMode
import mihon.domain.dictionary.model.TermMetaMode
import mihon.domain.dictionary.model.Dictionary
import tachiyomi.data.Dictionary_kanji
import tachiyomi.data.Dictionary_kanji_meta
import tachiyomi.data.Dictionary_tags
import tachiyomi.data.Dictionary_term_meta
import tachiyomi.data.Dictionary_terms
import tachiyomi.data.Dictionaries

fun mapDictionary(
    id: Long,
    title: String,
    revision: String,
    version: Long,
    author: String?,
    url: String?,
    description: String?,
    attribution: String?,
    isEnabled: Boolean,
    priority: Long,
    dateAdded: Long,
): Dictionary {
    return Dictionary(
        id = id,
        title = title,
        revision = revision,
        version = version.toInt(),
        author = author,
        url = url,
        description = description,
        attribution = attribution,
        isEnabled = isEnabled,
        priority = priority.toInt(),
        dateAdded = dateAdded,
    )
}

fun Dictionaries.toDomain(): Dictionary {
    return mapDictionary(
        id = _id,
        title = title,
        revision = revision,
        version = version,
        author = author,
        url = url,
        description = description,
        attribution = attribution,
        // SQL stores is_enabled as INTEGER (0/1). Convert to Boolean here.
        isEnabled = (is_enabled == 1L),
        priority = priority,
        dateAdded = date_added,
    )
}

fun Dictionary_tags.toDomain(): DictionaryTag {
    return DictionaryTag(
        id = _id,
        dictionaryId = dictionary_id,
        name = name,
        category = category,
        order = tag_order.toInt(),
        notes = notes,
        score = score.toInt(),
    )
}

fun Dictionary_terms.toDomain(): DictionaryTerm {
    return DictionaryTerm(
        id = _id,
        dictionaryId = dictionary_id,
        expression = expression,
        reading = reading,
        definitionTags = definition_tags,
        rules = rules,
        score = score.toInt(),
        glossary = parseJsonArray(glossary),
        sequence = sequence,
        termTags = term_tags,
    )
}

fun Dictionary_kanji.toDomain(): DictionaryKanji {
    return DictionaryKanji(
        id = _id,
        dictionaryId = dictionary_id,
        character = character,
        onyomi = onyomi,
        kunyomi = kunyomi,
        tags = tags,
        meanings = parseJsonArray(meanings),
        stats = stats?.let { parseJsonObject(it) },
    )
}

fun Dictionary_term_meta.toDomain(): DictionaryTermMeta {
    return DictionaryTermMeta(
        id = _id,
        dictionaryId = dictionary_id,
        expression = expression,
        mode = TermMetaMode.fromString(mode),
        data = data_,
    )
}

fun Dictionary_kanji_meta.toDomain(): DictionaryKanjiMeta {
    return DictionaryKanjiMeta(
        id = _id,
        dictionaryId = dictionary_id,
        character = character,
        mode = KanjiMetaMode.fromString(mode),
        data = data_,
    )
}

// Helper functions for JSON parsing
private fun parseJsonArray(inputJson: String?): List<String> {
    // Simple JSON array parser - assumes array of strings
    if (inputJson == null || inputJson.isEmpty() || inputJson == "[]") return emptyList()

    val trimmed = inputJson.trim().removePrefix("[").removeSuffix("]")
    if (trimmed.isEmpty()) return emptyList()

    return trimmed.split(",").map { it.trim().removeSurrounding("\"") }
}

private fun parseJsonObject(json: String): Map<String, String> {
    // Simple JSON object parser
    if (json.isEmpty() || json == "{}") return emptyMap()

    val trimmed = json.trim().removePrefix("{").removeSuffix("}")
    if (trimmed.isEmpty()) return emptyMap()

    return trimmed.split(",").mapNotNull { pair ->
        val parts = pair.split(":", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim().removeSurrounding("\"")
            val value = parts[1].trim().removeSurrounding("\"")
            key to value
        } else {
            null
        }
    }.toMap()
}
