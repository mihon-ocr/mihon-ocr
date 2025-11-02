package eu.kanade.presentation.dictionary

import mihon.domain.dictionary.model.GlossaryEntry

/**
 * Legacy shim retained for compatibility with older presentation logic.
 * New UI renders glossary tree directly via [GlossarySection].
 * This file and its uses should be removed.
 */
object GlossaryFormatter {
    fun parseGlossary(glossary: List<GlossaryEntry>, isFormsEntry: Boolean = false): GlossaryData {
        return GlossaryData(entries = glossary, isFormsEntry = isFormsEntry)
    }

    fun formatForDisplay(glossaryData: GlossaryData): List<GlossaryEntry> {
        return glossaryData.entries
    }
}

data class GlossaryData(
    val entries: List<GlossaryEntry>,
    val isFormsEntry: Boolean,
)
