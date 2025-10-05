package mihon.data.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.KanjiMetaMode
import mihon.domain.dictionary.model.TermMetaMode
import mihon.domain.dictionary.service.DictionaryParseException
import mihon.domain.dictionary.service.DictionaryParser

class DictionaryParserImpl : DictionaryParser {

    private val json = Json { ignoreUnknownKeys = true }

    override fun parseIndex(jsonString: String): DictionaryIndex {
        try {
            val jsonObject = json.parseToJsonElement(jsonString).jsonObject

            val tagMeta = jsonObject["tagMeta"]?.jsonObject?.mapValues { (_, value) ->
                val metaObj = value.jsonObject
                DictionaryIndex.TagMeta(
                    category = metaObj["category"]?.jsonPrimitive?.contentOrNull ?: "",
                    order = metaObj["order"]?.jsonPrimitive?.int ?: 0,
                    notes = metaObj["notes"]?.jsonPrimitive?.contentOrNull ?: "",
                    score = metaObj["score"]?.jsonPrimitive?.int ?: 0,
                )
            }

            return DictionaryIndex(
                title = jsonObject["title"]?.jsonPrimitive?.content
                    ?: throw DictionaryParseException("Missing title in index.json"),
                revision = jsonObject["revision"]?.jsonPrimitive?.content
                    ?: throw DictionaryParseException("Missing revision in index.json"),
                format = jsonObject["format"]?.jsonPrimitive?.int,
                version = jsonObject["version"]?.jsonPrimitive?.int,
                author = jsonObject["author"]?.jsonPrimitive?.contentOrNull,
                url = jsonObject["url"]?.jsonPrimitive?.contentOrNull,
                description = jsonObject["description"]?.jsonPrimitive?.contentOrNull,
                attribution = jsonObject["attribution"]?.jsonPrimitive?.contentOrNull,
                sourceLanguage = jsonObject["sourceLanguage"]?.jsonPrimitive?.contentOrNull,
                targetLanguage = jsonObject["targetLanguage"]?.jsonPrimitive?.contentOrNull,
                sequenced = jsonObject["sequenced"]?.jsonPrimitive?.boolean,
                frequencyMode = jsonObject["frequencyMode"]?.jsonPrimitive?.contentOrNull,
                tagMeta = tagMeta,
            )
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse index.json", e)
        }
    }

    override fun parseTagBank(jsonString: String): List<DictionaryTag> {
        try {
            val jsonArray = json.parseToJsonElement(jsonString).jsonArray
            return jsonArray.map { element ->
                val array = element.jsonArray
                DictionaryTag(
                    dictionaryId = 0L, // Will be set when importing
                    name = array[0].jsonPrimitive.content,
                    category = array[1].jsonPrimitive.content,
                    order = array[2].jsonPrimitive.int,
                    notes = array[3].jsonPrimitive.content,
                    score = array[4].jsonPrimitive.int,
                )
            }
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse tag_bank", e)
        }
    }

    override fun parseTermBank(jsonString: String, version: Int): List<DictionaryTerm> {
        try {
            val jsonArray = json.parseToJsonElement(jsonString).jsonArray
            return jsonArray.map { element ->
                val array = element.jsonArray
                when (version) {
                    1 -> parseTermBankV1(array)
                    3 -> parseTermBankV3(array)
                    else -> parseTermBankV3(array) // Default to V3
                }
            }
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse term_bank", e)
        }
    }

    private fun parseStringOrArray(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonArray -> {
                if (element.isEmpty()) null
                else element.joinToString(" ") { it.jsonPrimitive.content }
            }
            else -> null
        }
    }

    private fun parseTermBankV1(array: JsonArray): DictionaryTerm {
        // V1 format: [expression, reading, definitionTags, rules, score, ...glossary]
        val glossary = array.drop(5).map { it.jsonPrimitive.content }
        return DictionaryTerm(
            dictionaryId = 0L,
            expression = array[0].jsonPrimitive.content,
            reading = array[1].jsonPrimitive.content,
            definitionTags = parseStringOrArray(array[2]),
            rules = parseStringOrArray(array[3]),
            score = array[4].jsonPrimitive.int,
            glossary = glossary,
            sequence = null,
            termTags = null,
        )
    }

    private fun parseTermBankV3(array: JsonArray): DictionaryTerm {
        // V3 format: [expression, reading, definitionTags, rules, score, glossary[], sequence, termTags]
        val glossaryArray = array[5].jsonArray
        val glossary = glossaryArray.map {
            when (it) {
                is JsonPrimitive -> it.content
                is JsonObject -> {
                    // Handle different glossary object types: text, image, structured-content
                    val type = it["type"]?.jsonPrimitive?.contentOrNull
                    when (type) {
                        "text" -> it["text"]?.jsonPrimitive?.contentOrNull ?: it.toString()
                        "structured-content", "image" -> it.toString()
                        else -> it.toString()
                    }
                }
                is JsonArray -> it.toString() // Deinflection array
                else -> it.toString()
            }
        }

        return DictionaryTerm(
            dictionaryId = 0L,
            expression = array[0].jsonPrimitive.content,
            reading = array[1].jsonPrimitive.content,
            definitionTags = parseStringOrArray(array[2]),
            rules = parseStringOrArray(array[3]),
            score = array[4].jsonPrimitive.int,
            glossary = glossary,
            sequence = array.getOrNull(6)?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            termTags = array.getOrNull(7)?.let { parseStringOrArray(it) },
        )
    }

    override fun parseKanjiBank(jsonString: String, version: Int): List<DictionaryKanji> {
        try {
            val jsonArray = json.parseToJsonElement(jsonString).jsonArray
            return jsonArray.map { element ->
                val array = element.jsonArray
                when (version) {
                    1 -> parseKanjiBankV1(array)
                    3 -> parseKanjiBankV3(array)
                    else -> parseKanjiBankV3(array)
                }
            }
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse kanji_bank", e)
        }
    }

    private fun parseKanjiBankV1(array: JsonArray): DictionaryKanji {
        // V1 format: [character, onyomi, kunyomi, tags, ...meanings]
        val meanings = array.drop(4).map { it.jsonPrimitive.content }
        return DictionaryKanji(
            dictionaryId = 0L,
            character = array[0].jsonPrimitive.content,
            onyomi = array[1].jsonPrimitive.content,
            kunyomi = array[2].jsonPrimitive.content,
            tags = parseStringOrArray(array[3]),
            meanings = meanings,
            stats = null,
        )
    }

    private fun parseKanjiBankV3(array: JsonArray): DictionaryKanji {
        // V3 format: [character, onyomi, kunyomi, tags, meanings, stats]
        val meaningsElement = array[4]
        val meanings = when (meaningsElement) {
            is JsonArray -> meaningsElement.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(meaningsElement.content)
            else -> emptyList()
        }

        val stats = array.getOrNull(5)?.jsonObject?.mapValues { (_, value) ->
            value.jsonPrimitive.content
        }

        return DictionaryKanji(
            dictionaryId = 0L,
            character = array[0].jsonPrimitive.content,
            onyomi = array[1].jsonPrimitive.content,
            kunyomi = array[2].jsonPrimitive.content,
            tags = parseStringOrArray(array[3]),
            meanings = meanings,
            stats = stats,
        )
    }

    override fun parseTermMetaBank(jsonString: String): List<DictionaryTermMeta> {
        try {
            val jsonArray = json.parseToJsonElement(jsonString).jsonArray
            return jsonArray.map { element ->
                val array = element.jsonArray
                val dataElement = array[2]
                val dataString = when (dataElement) {
                    is JsonPrimitive -> dataElement.content
                    is JsonObject, is JsonArray -> dataElement.toString()
                    else -> dataElement.toString()
                }

                DictionaryTermMeta(
                    dictionaryId = 0L,
                    expression = array[0].jsonPrimitive.content,
                    mode = TermMetaMode.fromString(array[1].jsonPrimitive.content),
                    data = dataString,
                )
            }
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse term_meta_bank", e)
        }
    }

    override fun parseKanjiMetaBank(jsonString: String): List<DictionaryKanjiMeta> {
        try {
            val jsonArray = json.parseToJsonElement(jsonString).jsonArray
            return jsonArray.map { element ->
                val array = element.jsonArray
                val dataElement = array[2]
                val dataString = when (dataElement) {
                    is JsonPrimitive -> dataElement.content
                    is JsonObject, is JsonArray -> dataElement.toString()
                    else -> dataElement.toString()
                }

                DictionaryKanjiMeta(
                    dictionaryId = 0L,
                    character = array[0].jsonPrimitive.content,
                    mode = KanjiMetaMode.fromString(array[1].jsonPrimitive.content),
                    data = dataString,
                )
            }
        } catch (e: Exception) {
            throw DictionaryParseException("Failed to parse kanji_meta_bank", e)
        }
    }
}
