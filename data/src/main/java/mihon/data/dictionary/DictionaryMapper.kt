package mihon.data.dictionary

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryEntry
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
        glossary = parseGlossary(glossary),
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
        meanings = parseStringList(meanings),
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

private val glossarySerializer = ListSerializer(GlossaryEntry.serializer())
private val legacyStringListSerializer = ListSerializer(String.serializer())

private fun parseGlossary(inputJson: String?): List<GlossaryEntry> {
    if (inputJson.isNullOrBlank() || inputJson == "[]") return emptyList()

    return try {
        jsonParser.decodeFromString(glossarySerializer, inputJson)
    } catch (e: SerializationException) {
        parseLegacyGlossary(inputJson)
    } catch (e: IllegalArgumentException) {
        parseLegacyGlossary(inputJson)
    }
}

private fun parseLegacyGlossary(inputJson: String): List<GlossaryEntry> {
    val legacy = try {
        jsonParser.decodeFromString(legacyStringListSerializer, inputJson)
    } catch (_: SerializationException) {
        parseLegacyStringList(inputJson)
    } catch (_: IllegalArgumentException) {
        parseLegacyStringList(inputJson)
    }

    if (legacy.isEmpty()) return emptyList()

    return legacy.map { GlossaryEntry.TextDefinition(it) }
}

private fun parseStringList(inputJson: String?): List<String> {
    if (inputJson.isNullOrBlank() || inputJson == "[]") return emptyList()

    return try {
        jsonParser.decodeFromString(legacyStringListSerializer, inputJson)
    } catch (_: SerializationException) {
        parseLegacyStringList(inputJson)
    } catch (_: IllegalArgumentException) {
        parseLegacyStringList(inputJson)
    }
}

private fun parseLegacyStringList(inputJson: String): List<String> {
    val trimmed = inputJson.trim().removePrefix("[").removeSuffix("]")
    if (trimmed.isEmpty()) return emptyList()

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
                if (inString) {
                    currentToken.append(char)
                }
            }
            char == '"' -> {
                inString = !inString
                if (depth == 0) {
                    if (!inString && currentToken.isNotEmpty()) {
                        result.add(currentToken.toString())
                        currentToken.clear()
                    } else if (inString) {
                        currentToken.clear()
                    }
                }
            }
            inString -> currentToken.append(char)
            char == '{' || char == '[' -> {
                depth++
                if (depth == 1) {
                    currentToken.clear()
                }
            }
            char == '}' || char == ']' -> {
                depth--
                if (depth < 0) depth = 0
            }
        }
    }

    return result
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
