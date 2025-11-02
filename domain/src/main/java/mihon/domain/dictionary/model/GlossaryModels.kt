package mihon.domain.dictionary.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface GlossaryEntry {
    @Serializable
    @SerialName("text")
    data class TextDefinition(val text: String) : GlossaryEntry

    @Serializable
    @SerialName("structured")
    data class StructuredContent(val nodes: List<GlossaryNode>) : GlossaryEntry

    @Serializable
    @SerialName("image")
    data class ImageDefinition(val image: GlossaryImageAttributes) : GlossaryEntry

    @Serializable
    @SerialName("deinflection")
    data class Deinflection(val baseForm: String, val rules: List<String>) : GlossaryEntry

    @Serializable
    @SerialName("unknown")
    data class Unknown(val rawJson: String) : GlossaryEntry
}

@Serializable
sealed interface GlossaryNode {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : GlossaryNode

    @Serializable
    @SerialName("line_break")
    data object LineBreak : GlossaryNode

    @Serializable
    @SerialName("element")
    data class Element(
        val tag: GlossaryTag,
        val children: List<GlossaryNode> = emptyList(),
        val attributes: GlossaryElementAttributes = GlossaryElementAttributes(),
    ) : GlossaryNode
}

@Serializable
enum class GlossaryTag {
    @SerialName("span")
    Span,

    @SerialName("div")
    Div,

    @SerialName("ruby")
    Ruby,

    @SerialName("rt")
    Rt,

    @SerialName("rp")
    Rp,

    @SerialName("table")
    Table,

    @SerialName("thead")
    Thead,

    @SerialName("tbody")
    Tbody,

    @SerialName("tfoot")
    Tfoot,

    @SerialName("tr")
    Tr,

    @SerialName("td")
    Td,

    @SerialName("th")
    Th,

    @SerialName("ol")
    OrderedList,

    @SerialName("ul")
    UnorderedList,

    @SerialName("li")
    ListItem,

    @SerialName("details")
    Details,

    @SerialName("summary")
    Summary,

    @SerialName("a")
    Link,

    @SerialName("img")
    Image,

    @SerialName("unknown")
    Unknown,
    ;

    companion object {
        fun fromRaw(tag: String?): GlossaryTag {
            return when (tag) {
                "span" -> Span
                "div" -> Div
                "ruby" -> Ruby
                "rt" -> Rt
                "rp" -> Rp
                "table" -> Table
                "thead" -> Thead
                "tbody" -> Tbody
                "tfoot" -> Tfoot
                "tr" -> Tr
                "td" -> Td
                "th" -> Th
                "ol" -> OrderedList
                "ul" -> UnorderedList
                "li" -> ListItem
                "details" -> Details
                "summary" -> Summary
                "a" -> Link
                "img" -> Image
                else -> Unknown
            }
        }
    }
}

@Serializable
data class GlossaryElementAttributes(
    val properties: Map<String, String> = emptyMap(),
    val dataAttributes: Map<String, String> = emptyMap(),
    val style: Map<String, String> = emptyMap(),
)

@Serializable
data class GlossaryImageAttributes(
    val path: String,
    val width: Int? = null,
    val height: Int? = null,
    val title: String? = null,
    val alt: String? = null,
    val description: String? = null,
    val pixelated: Boolean? = null,
    val imageRendering: String? = null,
    val appearance: String? = null,
    val background: Boolean? = null,
    val collapsed: Boolean? = null,
    val collapsible: Boolean? = null,
    val verticalAlign: String? = null,
    val border: String? = null,
    val borderRadius: String? = null,
    val sizeUnits: String? = null,
    val dataAttributes: Map<String, String> = emptyMap(),
)
