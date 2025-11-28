/**
 * Contains non-UI, helper functions for processing the GlossaryNode tree.
 *
 * The functions in this file are responsible for transforming, analyzing, or extracting glossary data.
 */
package eu.kanade.presentation.dictionary

import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import kotlin.collections.forEach

internal fun extractForms(entries: List<GlossaryEntry>): List<String> {
    if (entries.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        when (entry) {
            is GlossaryEntry.TextDefinition -> entry.text.takeIf { it.isNotBlank() }
            is GlossaryEntry.StructuredContent -> collectText(entry.nodes).takeIf { it.isNotBlank() }
            is GlossaryEntry.Deinflection -> entry.baseForm.takeIf { it.isNotBlank() }
            else -> null
        }
    }
        .distinct()
}

internal fun collectText(nodes: List<GlossaryNode>): String {
    val builder = StringBuilder()
    nodes.forEach { node -> builder.appendNodeText(node) }
    return builder.toString().replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
}

internal fun StringBuilder.appendNodeText(node: GlossaryNode) {
    when (node) {
        is GlossaryNode.Text -> append(node.text)
        is GlossaryNode.LineBreak -> append('\n')
        is GlossaryNode.Element -> when (node.tag) {
            GlossaryTag.Ruby -> appendRubyInline(node)
            GlossaryTag.Link -> appendLinkInline(node)
            GlossaryTag.Image -> Unit // Ignore images
            GlossaryTag.Summary -> {
                append("Summary: ")
                node.children.forEach { child -> appendNodeText(child) }
            }
            else -> node.children.forEach { child -> appendNodeText(child) }
        }
    }
}


internal fun StringBuilder.appendRubyInline(node: GlossaryNode.Element) {
    val baseNodes = node.children.filterNot { child ->
        child is GlossaryNode.Element && (child.tag == GlossaryTag.Rt || child.tag == GlossaryTag.Rp)
    }
    val readingNodes = node.children.filterIsInstance<GlossaryNode.Element>()
        .filter { it.tag == GlossaryTag.Rt }
        .flatMap { it.children }

    val baseText = collectText(baseNodes)
    val readingText = collectText(readingNodes)

    if (readingText.isNotBlank()) {
        append("[")
        append(baseText)
        append("[")
        append(readingText)
        append("]]")
    } else {
        append(baseText)
    }
}

internal fun StringBuilder.appendLinkInline(node: GlossaryNode.Element) {
    val linkText = collectText(node.children).ifBlank { node.attributes.properties["href"] ?: "" }
    append(linkText)
    node.attributes.properties["href"]?.takeIf { it.isNotBlank() }?.let { href ->
        append(" (")
        append(href)
        append(')')
    }
}

internal fun GlossaryNode.hasBlockContent(): Boolean {
    return when (this) {
        is GlossaryNode.Text -> false
        is GlossaryNode.LineBreak -> true
        is GlossaryNode.Element -> when (tag) {
            GlossaryTag.Ruby, GlossaryTag.Link, GlossaryTag.Span, GlossaryTag.Image ->
                children.any { child -> child.hasBlockContent() }
            GlossaryTag.Rt, GlossaryTag.Rp -> false
            GlossaryTag.Div -> children.any { child -> child.hasBlockContent() }
            GlossaryTag.Unknown -> children.any { child -> child.hasBlockContent() }
            else -> true
        }
    }
}

internal fun GlossaryNode.containsLink(): Boolean {
    return when (this) {
        is GlossaryNode.Text -> false
        is GlossaryNode.LineBreak -> false
        is GlossaryNode.Element -> tag == GlossaryTag.Link || children.any { it.containsLink() }
    }
}

internal fun List<GlossaryNode>.containsLink(): Boolean {
    return any { it.containsLink() }
}
