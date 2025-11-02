package mihon.data.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mihon.domain.dictionary.model.DictionaryIndex
import mihon.domain.dictionary.model.DictionaryKanji
import mihon.domain.dictionary.model.DictionaryKanjiMeta
import mihon.domain.dictionary.model.DictionaryTag
import mihon.domain.dictionary.model.DictionaryTerm
import mihon.domain.dictionary.model.DictionaryTermMeta
import mihon.domain.dictionary.model.GlossaryElementAttributes
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
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
                if (element.isEmpty()) {
                    null
                } else {
                    element.joinToString(" ") { it.jsonPrimitive.content }
                }
            }
            else -> null
        }
    }

    private fun parseTermBankV1(array: JsonArray): DictionaryTerm {
        // V1 format: [expression, reading, definitionTags, rules, score, ...glossary]
        val glossary = array.drop(5).map { GlossaryEntry.TextDefinition(it.jsonPrimitive.content) }
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
        val glossary = glossaryArray.map { parseGlossaryEntry(it) }

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

    private fun parseGlossaryEntry(element: JsonElement): GlossaryEntry {
        return when (element) {
            is JsonPrimitive -> GlossaryEntry.TextDefinition(element.content)
            is JsonArray -> parseDeinflectionEntry(element)
            is JsonObject -> parseGlossaryObject(element)
            else -> GlossaryEntry.Unknown(element.toString())
        }
    }

    private fun parseDeinflectionEntry(array: JsonArray): GlossaryEntry {
        if (array.size < 2) {
            return GlossaryEntry.Unknown(array.toString())
        }

        val baseForm = array[0].jsonPrimitive.contentOrNull
        val rulesJson = array[1]
        val rules = (rulesJson as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }

        return if (baseForm != null && rules != null) {
            GlossaryEntry.Deinflection(baseForm, rules)
        } else {
            GlossaryEntry.Unknown(array.toString())
        }
    }

    private fun parseGlossaryObject(obj: JsonObject): GlossaryEntry {
        val type = obj["type"]?.jsonPrimitive?.contentOrNull
        return when (type) {
            "text" -> GlossaryEntry.TextDefinition(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
            "image" -> parseImageEntry(obj) ?: GlossaryEntry.Unknown(obj.toString())
            "structured-content" -> {
                val content = obj["content"] ?: return GlossaryEntry.StructuredContent(emptyList())
                val nodes = parseStructuredNodes(content)
                GlossaryEntry.StructuredContent(nodes)
            }
            else -> GlossaryEntry.Unknown(obj.toString())
        }
    }

    private fun parseImageEntry(obj: JsonObject): GlossaryEntry.ImageDefinition? {
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return null
        val attributes = GlossaryImageAttributes(
            path = path,
            width = obj["width"]?.jsonPrimitive?.intOrNull,
            height = obj["height"]?.jsonPrimitive?.intOrNull,
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            alt = obj["alt"]?.jsonPrimitive?.contentOrNull,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            pixelated = obj["pixelated"]?.jsonPrimitive?.booleanOrNull,
            imageRendering = obj["imageRendering"]?.jsonPrimitive?.contentOrNull,
            appearance = obj["appearance"]?.jsonPrimitive?.contentOrNull,
            background = obj["background"]?.jsonPrimitive?.booleanOrNull,
            collapsed = obj["collapsed"]?.jsonPrimitive?.booleanOrNull,
            collapsible = obj["collapsible"]?.jsonPrimitive?.booleanOrNull,
            verticalAlign = obj["verticalAlign"]?.jsonPrimitive?.contentOrNull,
            border = obj["border"]?.jsonPrimitive?.contentOrNull,
            borderRadius = obj["borderRadius"]?.jsonPrimitive?.contentOrNull,
            sizeUnits = obj["sizeUnits"]?.jsonPrimitive?.contentOrNull,
            dataAttributes = parseStringMap(obj["data"]?.jsonObject),
        )
        return GlossaryEntry.ImageDefinition(attributes)
    }

    private fun parseStructuredNodes(element: JsonElement): List<GlossaryNode> {
        return when (element) {
            is JsonPrimitive -> listOf(GlossaryNode.Text(element.content))
            is JsonArray -> element.flatMap { parseStructuredNodes(it) }
            is JsonObject -> parseStructuredObject(element)?.let { listOf(it) } ?: emptyList()
            else -> emptyList()
        }
    }

    private fun parseStructuredObject(obj: JsonObject): GlossaryNode? {
        val rawTag = obj["tag"]?.jsonPrimitive?.contentOrNull
        if (rawTag == "br") {
            return GlossaryNode.LineBreak
        }

        val tag = GlossaryTag.fromRaw(rawTag)
        val children = obj["content"]?.let { parseStructuredNodes(it) } ?: emptyList()
        val attributes = parseElementAttributes(obj)

        return GlossaryNode.Element(
            tag = tag,
            children = children,
            attributes = attributes,
        )
    }

    private fun parseElementAttributes(obj: JsonObject): GlossaryElementAttributes {
        val dataAttributes = parseStringMap(obj["data"]?.jsonObject)
        val styleAttributes = parseStringMap(obj["style"]?.jsonObject)
        val properties = parseProperties(obj)

        return GlossaryElementAttributes(
            properties = properties,
            dataAttributes = dataAttributes,
            style = styleAttributes,
        )
    }

    private fun parseStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val result = mutableMapOf<String, String>()
        obj.forEach { (key, value) ->
            val stringValue = value.stringValueOrNull()
            if (stringValue != null) {
                result[key] = stringValue
            }
        }
        return result
    }

    private fun parseProperties(obj: JsonObject): Map<String, String> {
        val excludedKeys = setOf("tag", "content", "data", "style")
        val result = mutableMapOf<String, String>()
        obj.forEach { (key, value) ->
            if (key in excludedKeys) return@forEach
            val stringValue = value.stringValueOrNull()
            if (stringValue != null) {
                result[key] = stringValue
            }
        }
        return result
    }

    private fun JsonElement?.stringValueOrNull(): String? {
        if (this == null) return null
        return when (this) {
            is JsonPrimitive -> {
                val stringContent = this.contentOrNull
                when {
                    stringContent != null -> stringContent
                    this.booleanOrNull != null -> this.booleanOrNull?.toString()
                    this.longOrNull != null -> this.longOrNull?.toString()
                    this.doubleOrNull != null -> this.doubleOrNull?.toString()
                    else -> this.content
                }
            }
            else -> this.toString()
        }
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
