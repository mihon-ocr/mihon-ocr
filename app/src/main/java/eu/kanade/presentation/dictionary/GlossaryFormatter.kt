package eu.kanade.presentation.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

/**
 * This formatter parses and renders glossary entries from dictionaries stored in the database.
 * The glossary column contains two distinct primary structures:
 *
 * 1. Simple String Array: Plain JSON array of alternative forms/spellings
 *    Example: ["ダイレクトプッシュ","ダイレクト・プッシュ"]
 *    Used when definition_tags = 'forms'
 *
 * 2. Structured Content JSON: Complex nested JSON with rich definition data
 *    Contains type="structured-content" with nested objects defining layout similar to HTML.
 *
 *    Variations include:
 *    A. Basic Definition: Single translation/definition (data.content="glossary")
 *    B. Multiple Definitions: Array of related definitions/abbreviations
 *    C. Definition with Info: Primary definition + supplementary note (data.content="infoGlossary")
 *    D. Definition with References: Standard definition + cross-reference links (data.content="references")
 */

/**
 * Represents a formatted glossary entry with type information
 */
sealed class GlossaryEntry {
    data class Definition(val text: String) : GlossaryEntry()
    data class AlternativeForms(val forms: List<String>) : GlossaryEntry()
    data class Info(val text: String) : GlossaryEntry()
    data class Reference(val text: String, val linkText: String, val linkQuery: String) : GlossaryEntry()
    data class MultipleDefinitions(val definitions: List<String>) : GlossaryEntry()
}

/**
 * Result of parsing a glossary entry
 */
data class GlossaryData(
    val entries: List<GlossaryEntry>,
    val isStructuredContent: Boolean,
)

object GlossaryFormatter {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parse glossary JSON from the database
     */
    fun parseGlossary(glossaryJson: String): GlossaryData {
        if (glossaryJson.isBlank() || glossaryJson == "[]") {
            return GlossaryData(emptyList(), false)
        }

        return try {
            val jsonArray = json.parseToJsonElement(glossaryJson).jsonArray

            if (jsonArray.isEmpty()) {
                return GlossaryData(emptyList(), false)
            }

            val firstElement = jsonArray[0]

            // Check if it's structured content (string containing JSON) or simple array
            if (firstElement is JsonPrimitive && firstElement.isString) {
                val content = firstElement.content

                // Try to parse as nested JSON (structured content)
                if (content.startsWith("{")) {
                    parseStructuredContent(content)
                } else {
                    // Simple string array (alternative forms)
                    val forms = jsonArray.map { it.jsonPrimitive.content }
                    GlossaryData(listOf(GlossaryEntry.AlternativeForms(forms)), false)
                }
            } else {
                // Fallback for unexpected format
                GlossaryData(emptyList(), false)
            }
        } catch (e: Exception) {
            // If parsing fails, return empty
            GlossaryData(emptyList(), false)
        }
    }

    private fun parseStructuredContent(contentJson: String): GlossaryData {
        try {
            val structured = json.parseToJsonElement(contentJson).jsonObject
            val type = structured["type"]?.jsonPrimitive?.content

            if (type != "structured-content") {
                return GlossaryData(emptyList(), false)
            }

            val content = structured["content"]
            val entries = mutableListOf<GlossaryEntry>()

            when (content) {
                is JsonArray -> {
                    // Multiple sections (e.g., definition + references + info)
                    content.forEach { section ->
                        parseSection(section.jsonObject)?.let { entries.add(it) }
                    }
                }
                is JsonObject -> {
                    // Single section
                    parseSection(content)?.let { entries.add(it) }
                }
                else -> {
                    logcat(LogPriority.WARN) { "Glossary format was not valid: $contentJson" }
                }
            }

            return GlossaryData(entries, true)
        } catch (e: Exception) {
            return GlossaryData(emptyList(), false)
        }
    }

    private fun parseSection(section: JsonObject): GlossaryEntry? {
        val tag = section["tag"]?.jsonPrimitive?.content

        if (tag != "ul") return null

        val data = section["data"]?.jsonObject
        val contentType = data?.get("content")?.jsonPrimitive?.content
        val content = section["content"]

        return when (contentType) {
            "glossary" -> parseGlossarySection(content)
            "references" -> parseReferenceSection(content)
            "infoGlossary" -> parseInfoSection(content)
            else -> null
        }
    }

    private fun parseGlossarySection(content: JsonElement?): GlossaryEntry? {
        return when (content) {
            is JsonObject -> {
                // Single definition
                val text = extractTextFromListItem(content)
                text?.let { GlossaryEntry.Definition(it) }
            }
            is JsonArray -> {
                // Multiple definitions
                val definitions = content.mapNotNull { item ->
                    if (item is JsonObject) {
                        extractTextFromListItem(item)
                    } else null
                }
                if (definitions.isNotEmpty()) {
                    GlossaryEntry.MultipleDefinitions(definitions)
                } else null
            }
            else -> null
        }
    }

    private fun parseReferenceSection(content: JsonElement?): GlossaryEntry? {
        if (content !is JsonObject) return null

        val tag = content["tag"]?.jsonPrimitive?.content
        if (tag != "li") return null

        val contentArray = content["content"]
        if (contentArray !is JsonArray) return null

        val parts = mutableListOf<String>()
        var linkText = ""
        var linkQuery = ""

        contentArray.forEach { element ->
            when (element) {
                is JsonPrimitive -> {
                    parts.add(element.content)
                }
                is JsonObject -> {
                    val elementTag = element["tag"]?.jsonPrimitive?.content
                    if (elementTag == "a") {
                        linkText = element["content"]?.jsonPrimitive?.content ?: ""
                        parts.add(linkText)
                        val href = element["href"]?.jsonPrimitive?.content ?: ""
                        // Extract query from href (format: ?query=...&wildcards=off)
                        linkQuery = href.substringAfter("query=").substringBefore("&")
                    } else if (elementTag == "span") {
                        // This is the refGlosses span with small text
                        val spanContent = element["content"]?.jsonPrimitive?.content ?: ""
                        parts.add(spanContent)
                    }
                }
                else -> {
                    logcat(LogPriority.WARN) { "Glossary reference format was not valid: $element" }
                }
            }
        }

        val fullText = parts.joinToString("")
        return if (linkText.isNotEmpty()) {
            GlossaryEntry.Reference(fullText, linkText, linkQuery)
        } else null
    }

    private fun parseInfoSection(content: JsonElement?): GlossaryEntry? {
        if (content !is JsonObject) return null

        val text = extractTextFromListItem(content)
        return text?.let { GlossaryEntry.Info(it) }
    }

    private fun extractTextFromListItem(item: JsonObject): String? {
        val tag = item["tag"]?.jsonPrimitive?.content
        if (tag != "li") return null

        val content = item["content"]
        return when (content) {
            is JsonPrimitive -> content.content
            else -> null
        }
    }

    /**
     * Format glossary entries for display
     */
    fun formatForDisplay(glossaryData: GlossaryData): List<FormattedGlossaryItem> {
        return glossaryData.entries.mapNotNull { entry ->
            when (entry) {
                is GlossaryEntry.Definition -> {
                    FormattedGlossaryItem.Definition(entry.text)
                }
                is GlossaryEntry.MultipleDefinitions -> {
                    FormattedGlossaryItem.MultipleDefinitions(entry.definitions)
                }
                is GlossaryEntry.AlternativeForms -> {
                    FormattedGlossaryItem.AlternativeForms(entry.forms)
                }
                is GlossaryEntry.Info -> {
                    FormattedGlossaryItem.Info(entry.text)
                }
                is GlossaryEntry.Reference -> {
                    FormattedGlossaryItem.Reference(entry.text, entry.linkText, entry.linkQuery)
                }
            }
        }
    }
}

/**
 * UI-friendly formatted glossary items
 */
sealed class FormattedGlossaryItem {
    data class Definition(val text: String) : FormattedGlossaryItem()
    data class MultipleDefinitions(val definitions: List<String>) : FormattedGlossaryItem()
    data class AlternativeForms(val forms: List<String>) : FormattedGlossaryItem()
    data class Info(val text: String) : FormattedGlossaryItem()
    data class Reference(val fullText: String, val linkText: String, val linkQuery: String) : FormattedGlossaryItem()
}
