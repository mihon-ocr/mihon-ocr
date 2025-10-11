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
 * Supports both V1 and V3 schemas (images are ignored).
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
    data class PartOfSpeech(val text: String) : GlossaryEntry()
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
     * Parse glossary from the database.
     */
    fun parseGlossary(glossary: List<String>, isFormsEntry: Boolean = false): GlossaryData {
        if (glossary.isEmpty()) {
            return GlossaryData(emptyList(), false)
        }

        return try {
            val firstElement = glossary[0]

            // Check if it's structured content (string containing JSON) or simple array
            if (firstElement.startsWith("{")) {
                // V3 structured content, stored as a single JSON string in the list
                parseStructuredContent(firstElement)
            } else {
                // Simple string array (V1 definitions or V3 simple forms)
                if (isFormsEntry) {
                    GlossaryData(listOf(GlossaryEntry.AlternativeForms(glossary)), false)
                } else {
                    GlossaryData(listOf(GlossaryEntry.MultipleDefinitions(glossary)), false)
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse glossary: ${e.message}" }
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

            val content = structured["content"] ?: return GlossaryData(emptyList(), false)
            val entries = findGlossaryEntries(content)

            return GlossaryData(entries, true)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "Failed to parse structured content: ${e.message}" }
            return GlossaryData(emptyList(), false)
        }
    }

    private fun findGlossaryEntries(element: JsonElement): List<GlossaryEntry> {
        val entries = mutableListOf<GlossaryEntry>()

        when (element) {
            is JsonObject -> {
                // Check if this object is a known section type we can parse directly
                val data = element["data"]?.jsonObject
                val contentType = data?.get("content")?.jsonPrimitive?.content
                val content = element["content"]

                val parsedEntry = when (contentType) {
                    "glossary" -> parseGlossarySection(content)
                    "references" -> parseReferenceSection(content)
                    "infoGlossary" -> parseInfoSection(content)
                    "part-of-speech-info" -> parsePartOfSpeech(element)
                    else -> null
                }

                if (parsedEntry != null) {
                    entries.add(parsedEntry)
                } else {
                    // It's not a parsable section, recurse into its content
                    element["content"]?.let {
                        entries.addAll(findGlossaryEntries(it))
                    }
                }
            }
            is JsonArray -> {
                // Recurse into each element of the array
                element.forEach {
                    entries.addAll(findGlossaryEntries(it))
                }
            }
            is JsonPrimitive -> {
                // Can't be nested further; stop here
            }
        }
        return entries
    }

    private fun parsePartOfSpeech(element: JsonObject): GlossaryEntry? {
        val tag = element["tag"]?.jsonPrimitive?.content
        if (tag != "span") return null

        val text = element["content"]?.jsonPrimitive?.content
        return text?.let {GlossaryEntry.PartOfSpeech(it)}
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
                // Single definition - extract text, ignore images
                val text = extractTextFromListItem(content)
                text?.let { GlossaryEntry.Definition(it) }
            }
            is JsonArray -> {
                // Multiple definitions - extract text from each, ignore images
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
                    when (elementTag) {
                        "a" -> {
                            linkText = element["content"]?.jsonPrimitive?.content ?: ""
                            parts.add(linkText)
                            val href = element["href"]?.jsonPrimitive?.content ?: ""
                            // Extract query from href (format: ?query=...&wildcards=off)
                            linkQuery = href.substringAfter("query=").substringBefore("&")
                        }
                        "span" -> {
                            val spanContent = element["content"]?.jsonPrimitive?.content ?: ""
                            parts.add(spanContent)
                        }
                        "img" -> {
                            // Ignore images - don't add to parts
                        }
                        else -> {
                            // Try to extract text from unknown tags
                            element["content"]?.jsonPrimitive?.content?.let { parts.add(it) }
                        }
                    }
                }
                else -> {
                    logcat(LogPriority.WARN) { "Unexpected element type in reference section" }
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

    /**
     * Extract text content from a list item, ignoring images and other non-text elements
     */
    private fun extractTextFromListItem(item: JsonObject): String? {
        val tag = item["tag"]?.jsonPrimitive?.content
        if (tag != "li") return null

        val content = item["content"]
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> {
                // Handle list items with multiple children (text + images, etc.)
                content.mapNotNull { element ->
                    when (element) {
                        is JsonPrimitive -> element.content
                        is JsonObject -> {
                            val elementTag = element["tag"]?.jsonPrimitive?.content
                            if (elementTag == "img") {
                                // Skip images
                                null
                            } else {
                                // Try to extract text from other elements
                                element["content"]?.jsonPrimitive?.content
                            }
                        }
                        else -> null
                    }
                }.joinToString("")
            }
            is JsonObject -> {
                // Check if it's an image tag and skip it
                val contentTag = content["tag"]?.jsonPrimitive?.content
                if (contentTag == "img") {
                    null
                } else {
                    content["content"]?.jsonPrimitive?.content
                }
            }
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
                is GlossaryEntry.PartOfSpeech -> {
                    FormattedGlossaryItem.PartOfSpeech(entry.text)
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
    data class PartOfSpeech(val text: String) : FormattedGlossaryItem()
    data class Reference(val fullText: String, val linkText: String, val linkQuery: String) : FormattedGlossaryItem()
}
