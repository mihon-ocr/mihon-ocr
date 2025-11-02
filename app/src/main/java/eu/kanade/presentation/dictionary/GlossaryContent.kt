package eu.kanade.presentation.dictionary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag

@Composable
fun GlossarySection(
    entries: List<GlossaryEntry>,
    isFormsEntry: Boolean,
    modifier: Modifier = Modifier,
    onLinkClick: (String) -> Unit,
) {
    if (entries.isEmpty()) return

    if (isFormsEntry) {
        val forms = remember(entries) { extractForms(entries) }
        if (forms.isNotEmpty()) {
            FormsRow(forms = forms, modifier = modifier)
            return
        }
    }

    Column(modifier = modifier) {
        entries.forEach { entry ->
            GlossaryEntryItem(entry = entry, onLinkClick = onLinkClick)
        }
    }
}

@Composable
private fun FormsRow(forms: List<String>, modifier: Modifier = Modifier) {
    if (forms.isEmpty()) return

    Row(
        modifier = modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Forms: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = forms.joinToString(", "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun GlossaryEntryItem(
    entry: GlossaryEntry,
    indentLevel: Int = 0,
    onLinkClick: (String) -> Unit,
) {
    when (entry) {
        is GlossaryEntry.TextDefinition -> DefinitionRow(text = entry.text, indentLevel = indentLevel)
        is GlossaryEntry.StructuredContent -> StructuredDefinition(entry.nodes, indentLevel, onLinkClick)
        is GlossaryEntry.ImageDefinition -> ImageEntryRow(entry.image, indentLevel)
        is GlossaryEntry.Deinflection -> DeinflectionRow(entry, indentLevel)
        is GlossaryEntry.Unknown -> Unit
    }
}

@Composable
private fun DefinitionRow(text: String, indentLevel: Int) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StructuredDefinition(
    nodes: List<GlossaryNode>,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    if (nodes.isEmpty()) return
    val containsLink = nodes.containsLink()
    if (!nodes.any { it.hasBlockContent() }) {
        if (containsLink) {
            InlineDefinitionRow(nodes, indentLevel, onLinkClick)
        } else {
            DefinitionRow(text = collectText(nodes), indentLevel = indentLevel)
        }
        return
    }
    Column(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
    ) {
        nodes.forEach { node -> StructuredNode(node, indentLevel, onLinkClick) }
    }
}

@Composable
private fun ImageEntryRow(image: GlossaryImageAttributes, indentLevel: Int) {
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "◦ ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        val description = buildString {
            append("Image: ")
            append(image.path)
            image.title?.takeIf { it.isNotBlank() }?.let {
                append(" (")
                append(it)
                append(")")
            }
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DeinflectionRow(entry: GlossaryEntry.Deinflection, indentLevel: Int) {
    val ruleChain = entry.rules.joinToString(" → ")
    val content = if (ruleChain.isBlank()) {
        entry.baseForm
    } else {
        "${entry.baseForm} ← $ruleChain"
    }
    DefinitionRow(text = content, indentLevel = indentLevel)
}

@Composable
private fun StructuredNode(node: GlossaryNode, indentLevel: Int, onLinkClick: (String) -> Unit) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        is GlossaryNode.LineBreak -> Spacer(modifier = Modifier.height(4.dp))
        is GlossaryNode.Element -> StructuredElement(node, indentLevel, onLinkClick)
    }
}

@Composable
private fun StructuredElement(
    node: GlossaryNode.Element,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    when (node.tag) {
        GlossaryTag.UnorderedList -> StructuredList(node.children, indentLevel, ListType.Unordered, onLinkClick)
        GlossaryTag.OrderedList -> StructuredList(node.children, indentLevel, ListType.Ordered, onLinkClick)
        GlossaryTag.ListItem -> StructuredListItem(node, indentLevel, 0, ListType.Unordered, onLinkClick)
        GlossaryTag.Ruby -> RubyNode(node)
        GlossaryTag.Link -> LinkNode(node, onLinkClick)
        GlossaryTag.Image -> Unit // Ignore images
        GlossaryTag.Details -> DetailsNode(node, indentLevel, onLinkClick)
        GlossaryTag.Summary -> SummaryNode(node, indentLevel)
        GlossaryTag.Table -> TableNode(node, indentLevel, onLinkClick)
        GlossaryTag.Div -> {
            when (node.attributes.dataAttributes["content"]) {
                "example-sentence" -> ExampleSentenceNode(node, indentLevel, onLinkClick)
                "attribution" -> Unit // Hide attribution links (for cleanness)
                else -> {
                    Column {
                        node.children.forEach { child -> StructuredNode(child, indentLevel, onLinkClick) }
                    }
                }
            }
        }
        GlossaryTag.Span -> {
            // Span is an inline container - render its children in the current layout context
            node.children.forEach { child -> StructuredNode(child, indentLevel, onLinkClick) }
        }
        GlossaryTag.Thead, GlossaryTag.Tbody, GlossaryTag.Tfoot, GlossaryTag.Tr,
        GlossaryTag.Td, GlossaryTag.Th, GlossaryTag.Unknown, GlossaryTag.Rt, GlossaryTag.Rp -> {
            Column {
                node.children.forEach { child -> StructuredNode(child, indentLevel, onLinkClick) }
            }
        }
    }
}

@Composable
private fun StructuredList(
    children: List<GlossaryNode>,
    indentLevel: Int,
    type: ListType,
    onLinkClick: (String) -> Unit,
) {
    if (children.isEmpty()) return
    var itemIndex = 0
    Column(modifier = Modifier.padding(start = bulletIndent(1))) {
        children.forEach { child ->
            if (child is GlossaryNode.Element && child.tag == GlossaryTag.ListItem) {
                StructuredListItem(child, indentLevel + 1, itemIndex, type, onLinkClick)
                itemIndex += 1
            } else {
                StructuredNode(child, indentLevel, onLinkClick)
            }
        }
    }
}

@Composable
private fun StructuredListItem(
    node: GlossaryNode.Element,
    indentLevel: Int,
    index: Int,
    type: ListType,
    onLinkClick: (String) -> Unit,
) {
    val inlineText = if (node.children.any { child -> child.hasBlockContent() }) {
        null
    } else {
        collectText(node.children)
    }
    val containsLink = node.children.containsLink()

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val marker = when (type) {
            ListType.Unordered -> "•"
            ListType.Ordered -> "${index + 1}."
        }

        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )

        if (!inlineText.isNullOrBlank() && !containsLink) {
            Text(
                text = inlineText,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Column {
                val children = node.children
                val firstListIndex = children.indexOfFirst {
                    it is GlossaryNode.Element && (it.tag == GlossaryTag.OrderedList || it.tag == GlossaryTag.UnorderedList)
                }.let { if (it == -1) children.size else it }

                val inlineChildren = children.subList(0, firstListIndex)
                val blockChildren = children.subList(firstListIndex, children.size)

                if (inlineChildren.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(bottom = 4.dp),
                    ) {
                        inlineChildren.forEach { child ->
                            StructuredNode(child, indentLevel, onLinkClick)
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }

                if (blockChildren.isNotEmpty()) {
                    blockChildren.forEach { child ->
                        StructuredNode(child, indentLevel, onLinkClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun RubyNode(
    node: GlossaryNode.Element,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val baseNodes = node.children.filterNot { child ->
        child is GlossaryNode.Element && (child.tag == GlossaryTag.Rt || child.tag == GlossaryTag.Rp)
    }
    val readingNodes = node.children.filterIsInstance<GlossaryNode.Element>()
        .filter { it.tag == GlossaryTag.Rt }
        .flatMap { it.children }

    val baseText = collectText(baseNodes)
    val readingText = collectText(readingNodes)

    val display = if (readingText.isNotBlank()) {
        "$baseText ($readingText)"
    } else {
        baseText
    }

    Text(
        text = display,
        style = textStyle,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun LinkNode(
    node: GlossaryNode.Element,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    val linkText = collectText(node.children).ifBlank { node.attributes.properties["href"] ?: "" }
    val href = node.attributes.properties["href"]
    val display = if (!href.isNullOrBlank() && !href.startsWith("?query")) {
        "$linkText (${href})"
    } else {
        // Has no href or uses a `?query` link, which is ignored
        linkText
    }
    val trimmedLink = linkText.trim()
    Text(
        text = display,
        style = textStyle,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(bottom = 2.dp)
            .clickable(enabled = trimmedLink.isNotEmpty()) {
                if (trimmedLink.isNotEmpty()) {
                    onLinkClick(trimmedLink)
                }
            },
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
    )
}

@Composable
private fun DetailsNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(start = bulletIndent(indentLevel)),
    ) {
        node.children.forEach { child -> StructuredNode(child, indentLevel + 1, onLinkClick) }
    }
}

@Composable
private fun SummaryNode(node: GlossaryNode.Element, indentLevel: Int) {
    val summary = collectText(node.children)
    Text(
        text = summary,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = bulletIndent(indentLevel), bottom = 2.dp),
    )
}

@Composable
private fun ExampleSentenceNode(
    node: GlossaryNode.Element,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    val sentenceANode = node.children.filterIsInstance<GlossaryNode.Element>().find {
        it.attributes.dataAttributes["content"] == "example-sentence-a"
    }
    val sentenceBNode = node.children.filterIsInstance<GlossaryNode.Element>().find {
        it.attributes.dataAttributes["content"] == "example-sentence-b"
    }

    if (sentenceANode == null) return

    Column(
        modifier = Modifier
            .padding(start = bulletIndent(indentLevel), top = 4.dp, bottom = 4.dp)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    ) {
        val jpSpan = sentenceANode.children.filterIsInstance<GlossaryNode.Element>().firstOrNull()
        if (jpSpan != null) {
            FlowRow {
                jpSpan.children.forEach { node ->
                    InlineNode(node, onLinkClick)
                }
            }
        }

        val engSpan = sentenceBNode?.children?.filterIsInstance<GlossaryNode.Element>()?.firstOrNull()
        if (engSpan != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = collectText(engSpan.children),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

@Composable
internal fun InlineNode(
    node: GlossaryNode,
    onLinkClick: (String) -> Unit,
    isKeyword: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = textStyle,
            fontWeight = if (isKeyword) FontWeight.SemiBold else null,
        )
        is GlossaryNode.LineBreak -> { /* Ignore in inline context */ }
        is GlossaryNode.Element -> {
            val isChildKeyword = isKeyword || node.attributes.dataAttributes["content"] == "example-keyword"
            when (node.tag) {
                GlossaryTag.Ruby -> RubyNode(node, modifier = Modifier, textStyle = textStyle)
                GlossaryTag.Span -> {
                    node.children.forEach { InlineNode(it, onLinkClick, isChildKeyword, textStyle) }
                }
                GlossaryTag.Link -> LinkNode(node, onLinkClick, modifier = Modifier, textStyle = textStyle)
                else -> {
                    val text = collectText(listOf(node))
                    if (text.isNotBlank()) {
                        Text(
                            text = text,
                            style = textStyle,
                            fontWeight = if (isChildKeyword) FontWeight.SemiBold else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineDefinitionRow(
    nodes: List<GlossaryNode>,
    indentLevel: Int,
    onLinkClick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 6.dp),
        )
        FlowRow {
            nodes.forEach { node ->
                InlineNode(node, onLinkClick)
            }
        }
    }
}

internal fun bulletIndent(indentLevel: Int): Dp {
    val level = indentLevel.coerceAtLeast(0)
    return 12.dp * level.toFloat()
}

private enum class ListType {
    Unordered,
    Ordered,
}
