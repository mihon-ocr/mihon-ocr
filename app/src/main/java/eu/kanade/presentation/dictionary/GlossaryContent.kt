package eu.kanade.presentation.dictionary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mihon.domain.dictionary.model.GlossaryElementAttributes
import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryImageAttributes
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag

@Composable
fun GlossarySection(
    entries: List<GlossaryEntry>,
    isFormsEntry: Boolean,
    modifier: Modifier = Modifier,
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
            GlossaryEntryItem(entry = entry)
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
private fun GlossaryEntryItem(entry: GlossaryEntry, indentLevel: Int = 0) {
    when (entry) {
        is GlossaryEntry.TextDefinition -> DefinitionRow(text = entry.text, indentLevel = indentLevel)
        is GlossaryEntry.StructuredContent -> StructuredDefinition(entry.nodes, indentLevel)
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
            text = "◦ ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StructuredDefinition(nodes: List<GlossaryNode>, indentLevel: Int) {
    if (nodes.isEmpty()) return
    Row(
        modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "◦ ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Column {
            nodes.forEach { node -> StructuredNode(node, indentLevel + 1) }
        }
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
private fun StructuredNode(node: GlossaryNode, indentLevel: Int) {
    when (node) {
        is GlossaryNode.Text -> Text(
            text = node.text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        is GlossaryNode.LineBreak -> Spacer(modifier = Modifier.height(4.dp))
        is GlossaryNode.Element -> StructuredElement(node, indentLevel)
    }
}

@Composable
private fun StructuredElement(node: GlossaryNode.Element, indentLevel: Int) {
    when (node.tag) {
        GlossaryTag.UnorderedList, GlossaryTag.OrderedList -> {
            Column(modifier = Modifier.padding(start = bulletIndent(indentLevel))) {
                node.children.forEach { child -> StructuredNode(child, indentLevel + 1) }
            }
        }
        GlossaryTag.ListItem -> {
            Row(
                modifier = Modifier.padding(start = bulletIndent(indentLevel), top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = "• ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column {
                    node.children.forEach { child -> StructuredNode(child, indentLevel + 1) }
                }
            }
        }
        GlossaryTag.Ruby -> RubyNode(node, indentLevel)
        GlossaryTag.Link -> LinkNode(node)
        GlossaryTag.Image -> InlineImageNode(node.attributes)
        GlossaryTag.Details -> DetailsNode(node, indentLevel)
        GlossaryTag.Summary -> SummaryNode(node, indentLevel)
        GlossaryTag.Rt, GlossaryTag.Rp -> {
            node.children.forEach { child -> StructuredNode(child, indentLevel) }
        }
        GlossaryTag.Table, GlossaryTag.Thead, GlossaryTag.Tbody, GlossaryTag.Tfoot, GlossaryTag.Tr,
        GlossaryTag.Td, GlossaryTag.Th, GlossaryTag.Span, GlossaryTag.Div, GlossaryTag.Unknown -> {
            Column {
                node.children.forEach { child -> StructuredNode(child, indentLevel) }
            }
        }
    }
}

@Composable
private fun RubyNode(node: GlossaryNode.Element, indentLevel: Int) {
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
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = bulletIndent(indentLevel).coerceAtLeast(0.dp), bottom = 2.dp),
    )
}

@Composable
private fun LinkNode(node: GlossaryNode.Element) {
    val linkText = collectText(node.children).ifBlank { node.attributes.properties["href"] ?: "" }
    val href = node.attributes.properties["href"]
    val display = if (!href.isNullOrBlank()) {
        "$linkText (${href})"
    } else {
        linkText
    }
    Text(
        text = display,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun InlineImageNode(attributes: GlossaryElementAttributes) {
    val path = attributes.properties["path"] ?: return
    val alt = attributes.properties["alt"]
    val description = buildString {
        append("[Image: ")
        append(path)
        alt?.takeIf { it.isNotBlank() }?.let {
            append(" | ")
            append(it)
        }
        append(']')
    }
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun DetailsNode(node: GlossaryNode.Element, indentLevel: Int) {
    Column(
        modifier = Modifier.padding(start = bulletIndent(indentLevel)),
    ) {
        node.children.forEach { child -> StructuredNode(child, indentLevel + 1) }
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

private fun extractForms(entries: List<GlossaryEntry>): List<String> {
    if (entries.isEmpty()) return emptyList()
    return entries.mapNotNull { entry ->
        when (entry) {
            is GlossaryEntry.TextDefinition -> entry.text.takeIf { it.isNotBlank() }
            is GlossaryEntry.StructuredContent -> collectText(entry.nodes).takeIf { it.isNotBlank() }
            is GlossaryEntry.Deinflection -> entry.baseForm.takeIf { it.isNotBlank() }
            else -> null
        }
    }
}

private fun collectText(nodes: List<GlossaryNode>): String {
    val builder = StringBuilder()
    nodes.forEach { node -> builder.appendNodeText(node) }
    return builder.toString().replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
}

private fun StringBuilder.appendNodeText(node: GlossaryNode) {
    when (node) {
        is GlossaryNode.Text -> append(node.text)
        is GlossaryNode.LineBreak -> append('\n')
        is GlossaryNode.Element -> node.children.forEach { child -> appendNodeText(child) }
    }
}

private fun bulletIndent(indentLevel: Int): Dp {
    val level = indentLevel.coerceAtLeast(0)
    return 12.dp * level.toFloat()
}
