package mihon.data.dictionary

import mihon.domain.dictionary.model.GlossaryEntry
import mihon.domain.dictionary.model.GlossaryNode
import mihon.domain.dictionary.model.GlossaryTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DictionaryParserImplTest {

    private val parser = DictionaryParserImpl()

    @Test
    fun `parseTermBankV3 handles structured content and deinflection`() {
        val json = """
            [
              [
                "食べる",
                "たべる",
                "",
                "",
                120,
                [
                  {"type":"text","text":"to eat"},
                  {
                    "type":"structured-content",
                    "content": {
                      "tag": "ul",
                      "content": [
                        {
                          "tag": "li",
                          "content": [
                            "Structured example",
                            {
                              "tag": "ruby",
                              "content": [
                                "漢字",
                                {"tag": "rt", "content": ["かんじ"]}
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  },
                  ["食べる", ["v5"]]
                ],
                42,
                "common"
              ]
            ]
        """.trimIndent()

        val terms = parser.parseTermBank(json, version = 3)
        assertEquals(1, terms.size)

        val term = terms.first()
        assertEquals("食べる", term.expression)
        assertEquals("たべる", term.reading)
        assertEquals(3, term.glossary.size)

        assertTrue(term.glossary[0] is GlossaryEntry.TextDefinition)

        val structuredEntry = term.glossary[1]
        assertTrue(structuredEntry is GlossaryEntry.StructuredContent)
        val structured = structuredEntry as GlossaryEntry.StructuredContent
        assertTrue(structured.nodes.isNotEmpty())

        val unorderedListNode = structured.nodes.first()
        assertTrue(unorderedListNode is GlossaryNode.Element)
        unorderedListNode as GlossaryNode.Element
        assertEquals(GlossaryTag.UnorderedList, unorderedListNode.tag)

        val listItem = unorderedListNode.children
            .filterIsInstance<GlossaryNode.Element>()
            .first { it.tag == GlossaryTag.ListItem }
        assertEquals(GlossaryTag.ListItem, listItem.tag)

        val rubyNode = listItem.children
            .filterIsInstance<GlossaryNode.Element>()
            .first { it.tag == GlossaryTag.Ruby }
        assertEquals(GlossaryTag.Ruby, rubyNode.tag)
        val rubyText = rubyNode.children.filterIsInstance<GlossaryNode.Text>().first().text
        assertEquals("漢字", rubyText)

        val deinflectionEntry = term.glossary[2]
        assertTrue(deinflectionEntry is GlossaryEntry.Deinflection)
        val deinflection = deinflectionEntry as GlossaryEntry.Deinflection
        assertEquals("食べる", deinflection.baseForm)
        assertEquals(listOf("v5"), deinflection.rules)
    }

    @Test
    fun `parseTermBankV1 creates text definitions`() {
        val json = """
            [
              [
                "猫",
                "ねこ",
                "",
                "",
                80,
                "cat",
                "feline"
              ]
            ]
        """.trimIndent()

        val terms = parser.parseTermBank(json, version = 1)
        assertEquals(1, terms.size)
        val term = terms.first()
        assertEquals(2, term.glossary.size)
        term.glossary.forEachIndexed { index, entry ->
            assertTrue(entry is GlossaryEntry.TextDefinition)
            val textEntry = entry as GlossaryEntry.TextDefinition
            val expected = if (index == 0) "cat" else "feline"
            assertEquals(expected, textEntry.text)
        }
    }
}
