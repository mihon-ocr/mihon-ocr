package mihon.data.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.KanjiMetaMode
import mihon.domain.dictionary.model.TermMetaMode
import tachiyomi.data.Dictionary_kanji
import tachiyomi.data.Dictionary_kanji_meta
import tachiyomi.data.Dictionary_tags
import tachiyomi.data.Dictionary_term_meta
import tachiyomi.data.Dictionary_terms

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
private val jsonParser = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private fun parseJsonArray(inputJson: String?): List<String> {
    if (inputJson == null || inputJson.isEmpty() || inputJson == "[]") return emptyList()

    return try {
        val jsonArray = jsonParser.parseToJsonElement(inputJson).jsonArray
        jsonArray.map { element ->
            element.jsonPrimitive.content
        }
    } catch (e: Exception) {
        // Fallback to simple parsing if JSON parsing fails
        val trimmed = inputJson.trim().removePrefix("[").removeSuffix("]")
        if (trimmed.isEmpty()) {
            emptyList()
        } else {
            // For complex nested JSON, try to extract quoted strings properly
            val result = mutableListOf<String>()
            var inString = false
            var escaped = false
            var depth = 0
            val currentToken = StringBuilder()

            for (char in trimmed) {
                when {
                    escaped -> {
                        currentToken.append(char)
                        escaped = false
                    }
                    char == '\\' -> {
                        escaped = true
                        currentToken.append(char)
                    }
                    char == '"' -> {
                        inString = !inString
                        if (depth == 0 && !inString && currentToken.isNotEmpty()) {
                            result.add(currentToken.toString())
                            currentToken.clear()
                        }
                    }
                    inString -> {
                        currentToken.append(char)
                    }
                    char == '{' || char == '[' -> {
                        depth++
                        if (depth == 1) currentToken.clear()
                    }
                    char == '}' || char == ']' -> {
                        depth--
                    }
                }
            }

            result
        }
    }
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
