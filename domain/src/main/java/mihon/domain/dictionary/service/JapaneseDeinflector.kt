package mihon.domain.dictionary.service

import java.util.ArrayDeque
import java.util.LinkedHashSet

/**
 * Generates possible dictionary headwords for conjugated Japanese terms.
 */
object JapaneseDeinflector {

    private const val MAX_FORMS = 24

    private data class Rule(
        val suffix: String,
        val apply: (stem: String, original: String) -> Set<String>,
    )

    private val rules: List<Rule> = listOf(
        Rule("ていなかった") { stem, _ -> singleStem(stem + "て") },
        Rule("でいなかった") { stem, _ -> singleStem(stem + "で") },
        Rule("いませんでした") { stem, _ -> singleStem(stem) },
        Rule("ていませんでした") { stem, _ -> singleStem(stem + "て") },
        Rule("でいませんでした") { stem, _ -> singleStem(stem + "で") },
        Rule("いません") { stem, _ -> singleStem(stem) },
        Rule("いました") { stem, _ -> singleStem(stem) },
        Rule("います") { stem, _ -> singleStem(stem) },
        Rule("ませんでした") { stem, _ -> generateMasuCandidates(stem) },
        Rule("ません") { stem, _ -> generateMasuCandidates(stem) },
        Rule("ました") { stem, _ -> generateMasuCandidates(stem) },
        Rule("ます") { stem, _ -> generateMasuCandidates(stem) },
        Rule("させられる") { stem, _ -> handleSaserareru(stem) },
        Rule("させる") { stem, _ -> handleSaseru(stem) },
        Rule("される") { stem, _ -> handleSareru(stem) },
        Rule("られた") { stem, _ -> handleRareru(stem) },
        Rule("られる") { stem, _ -> handleRareru(stem) },
        Rule("れた") { stem, _ -> handleReruPast(stem) },
        Rule("れる") { stem, _ -> handleReru(stem) },
        Rule("たくなかった") { stem, _ -> generateTaiCandidates(stem) },
        Rule("たくない") { stem, _ -> generateTaiCandidates(stem) },
        Rule("たい") { stem, _ -> generateTaiCandidates(stem) },
        Rule("くなかった") { stem, _ -> adjectiveFromStem(stem) },
        Rule("くない") { stem, _ -> adjectiveFromStem(stem) },
        Rule("かった") { stem, _ -> adjectiveFromStem(stem) },
        Rule("くて") { stem, _ -> adjectiveFromStem(stem) },
        Rule("じゃなかった") { stem, _ -> singleStem(stem) },
        Rule("じゃない") { stem, _ -> singleStem(stem) },
        Rule("でした") { stem, _ -> singleStem(stem) },
        Rule("です") { stem, _ -> singleStem(stem) },
        Rule("だった") { stem, _ -> singleStem(stem) },
        Rule("ている") { stem, _ -> singleStem(stem + "て") },
        Rule("でいる") { stem, _ -> singleStem(stem + "で") },
        Rule("ていた") { stem, _ -> singleStem(stem + "て") },
        Rule("でいた") { stem, _ -> singleStem(stem + "で") },
        Rule("ていない") { stem, _ -> singleStem(stem + "て") },
        Rule("でいない") { stem, _ -> singleStem(stem + "で") },
        Rule("よう") { stem, _ -> handleYou(stem) },
        Rule("れば") { stem, _ -> handleReba(stem) },
        Rule("ろ") { stem, _ -> handleImperativeRo(stem) },
        Rule("ない") { stem, _ -> generateNegativeCandidates(stem) },
        Rule("なかった") { stem, _ -> generateNegativeCandidates(stem) },
        Rule("って") { stem, original -> handleTte(stem, original) },
        Rule("った") { stem, original -> handleTta(stem, original) },
        Rule("んで") { stem, _ -> handleNde(stem) },
        Rule("んだ") { stem, _ -> handleNda(stem) },
        Rule("いて") { stem, _ -> handleIte(stem) },
        Rule("いた") { stem, _ -> handleIta(stem) },
        Rule("いで") { stem, _ -> handleIde(stem) },
        Rule("いだ") { stem, _ -> handleIda(stem) },
        Rule("して") { stem, _ -> handleShite(stem) },
        Rule("した") { stem, _ -> handleShita(stem) },
        Rule("て") { stem, _ -> handleTe(stem) },
        Rule("で") { stem, _ -> handleDe(stem) },
        Rule("た") { stem, _ -> handleTa(stem) },
        Rule("だ") { stem, _ -> singleStem(stem) },
    ).sortedByDescending { it.suffix.length }

    fun deinflect(input: String): List<String> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return emptyList()

        val visited = LinkedHashSet<String>()
        val queue: ArrayDeque<String> = ArrayDeque()

        visited += trimmed
        queue += trimmed

        while (queue.isNotEmpty() && visited.size < MAX_FORMS) {
            val current = queue.removeFirst()
            for (rule in rules) {
                if (!current.endsWith(rule.suffix)) continue
                if (current.length <= rule.suffix.length) continue

                val stem = current.substring(0, current.length - rule.suffix.length)
                if (stem.isEmpty()) continue

                val candidates = rule.apply(stem, current)
                for (candidate in candidates) {
                    val normalized = candidate.trim()
                    if (normalized.isEmpty()) continue
                    if (visited.add(normalized)) {
                        queue.add(normalized)
                        if (visited.size >= MAX_FORMS) break
                    }
                }
                if (visited.size >= MAX_FORMS) break
            }
        }

        return visited.toList()
    }

    private fun generateMasuCandidates(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromI(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun generateTaiCandidates(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromI(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun generateNegativeCandidates(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromA(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuruNegative(stem))
    }

    private fun handleSaseru(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromA(stem))
        addCandidates(applySuru(stem))
    }

    private fun handleSareru(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromA(stem))
        addCandidates(applySuru(stem))
    }

    private fun handleSaserareru(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromA(stem))
        addCandidates(applySuru(stem))
    }

    private fun handleRareru(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromA(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleReru(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromE(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleReruPast(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromE(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleTe(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleDe(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleTa(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleTte(stem: String, original: String): Set<String> = buildCandidates {
        addCandidate(stem + "う")
        addCandidate(stem + "つ")
        addCandidate(stem + "る")
        if (original.endsWith("行って") || original.endsWith("いって")) {
            addCandidate(stem + "く")
        }
    }

    private fun handleTta(stem: String, original: String): Set<String> = buildCandidates {
        addCandidate(stem + "う")
        addCandidate(stem + "つ")
        addCandidate(stem + "る")
        if (original.endsWith("行った") || original.endsWith("いった")) {
            addCandidate(stem + "く")
        }
    }

    private fun handleNde(stem: String): Set<String> = buildCandidates {
        addCandidate(stem + "む")
        addCandidate(stem + "ぶ")
        addCandidate(stem + "ぬ")
    }

    private fun handleNda(stem: String): Set<String> = buildCandidates {
        addCandidate(stem + "む")
        addCandidate(stem + "ぶ")
        addCandidate(stem + "ぬ")
    }

    private fun handleIte(stem: String): Set<String> = buildCandidates {
        addCandidate(stem + "く")
    }

    private fun handleIta(stem: String): Set<String> = buildCandidates {
        addCandidate(stem + "く")
    }

    private fun handleIde(stem: String): Set<String> = buildCandidates {
        addCandidate(stem + "ぐ")
    }

    private fun handleIda(stem: String): Set<String> = buildCandidates {
        addCandidate(stem + "ぐ")
    }

    private fun handleShite(stem: String): Set<String> =
        if (stem.isEmpty()) {
            linkedSetOf("する")
        } else {
            buildCandidates {
                addCandidate(stem + "す")
                addCandidate(stem + "する")
            }
        }

    private fun handleShita(stem: String): Set<String> =
        if (stem.isEmpty()) {
            linkedSetOf("する")
        } else {
            buildCandidates {
                addCandidate(stem + "す")
                addCandidate(stem + "する")
            }
        }

    private fun handleYou(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromO(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleReba(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromE(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun handleImperativeRo(stem: String): Set<String> = buildCandidates {
        addCandidate(applyIchidan(stem))
        addCandidate(applyGodanFromE(stem))
        addCandidates(applySuru(stem))
        addCandidates(applyKuru(stem))
    }

    private fun adjectiveFromStem(stem: String): Set<String> = buildCandidates {
        if (stem.isNotEmpty()) {
            addCandidate(stem + "い")
            if (stem == "よ" || stem.endsWith("良")) {
                addCandidate("いい")
                addCandidate(stem + "い")
            }
        }
    }

    private fun applyIchidan(stem: String): String? =
        if (stem.isNotEmpty()) stem + "る" else null

    private fun applyGodanFromI(stem: String): String? = mapLastCharacter(stem, GODAN_I_TO_U)

    private fun applyGodanFromA(stem: String): String? = mapLastCharacter(stem, GODAN_A_TO_U)

    private fun applyGodanFromE(stem: String): String? = mapLastCharacter(stem, GODAN_E_TO_U)

    private fun applyGodanFromO(stem: String): String? = mapLastCharacter(stem, GODAN_O_TO_U)

    private fun applySuru(stem: String): List<String> {
        if (!stem.endsWith("し")) return emptyList()
        val base = stem.dropLast(1)
        return listOf(base + "する")
    }

    private fun applyKuru(stem: String): List<String> {
        val result = mutableListOf<String>()
        when {
            stem.endsWith("来") -> result += stem + "る"
            stem.endsWith("き") -> result += stem.dropLast(1) + "くる"
            stem.endsWith("ぎ") -> result += stem.dropLast(1) + "ぐる"
        }
        if (stem == "来") result += "来る"
        if (stem == "き" || stem == "ぎ") result += "くる"
        return result
    }

    private fun applyKuruNegative(stem: String): List<String> {
        val result = mutableListOf<String>()
        when {
            stem.endsWith("こ") -> result += stem.dropLast(1) + "くる"
            stem.endsWith("来") -> result += stem + "る"
        }
        if (stem == "こ") result += "くる"
        if (stem == "来") result += "来る"
        return result
    }

    private fun mapLastCharacter(stem: String, mapping: Map<Char, Char>): String? {
        if (stem.isEmpty()) return null
        val last = stem.last()
        val replacement = mapping[last] ?: return null
        return stem.dropLast(1) + replacement
    }

    private inline fun buildCandidates(block: MutableSet<String>.() -> Unit): Set<String> {
        val result = LinkedHashSet<String>()
        result.block()
        return result
    }

    private fun MutableSet<String>.addCandidate(value: String?) {
        if (!value.isNullOrBlank()) {
            add(value)
        }
    }

    private fun MutableSet<String>.addCandidates(values: Iterable<String>) {
        values.forEach { addCandidate(it) }
    }

    private fun singleStem(stem: String): Set<String> =
        if (stem.isBlank()) emptySet() else linkedSetOf(stem)

    private val GODAN_I_TO_U = mapOf(
        'い' to 'う',
        'き' to 'く',
        'ぎ' to 'ぐ',
        'し' to 'す',
        'じ' to 'ず',
        'ち' to 'つ',
        'ぢ' to 'づ',
        'に' to 'ぬ',
        'ひ' to 'ふ',
        'び' to 'ぶ',
        'ぴ' to 'ぷ',
        'み' to 'む',
        'り' to 'る',
    )

    private val GODAN_A_TO_U = mapOf(
        'か' to 'く',
        'が' to 'ぐ',
        'さ' to 'す',
        'ざ' to 'ず',
        'た' to 'つ',
        'だ' to 'づ',
        'な' to 'ぬ',
        'は' to 'ふ',
        'ば' to 'ぶ',
        'ぱ' to 'ぷ',
        'ま' to 'む',
        'ら' to 'る',
        'わ' to 'う',
    )

    private val GODAN_E_TO_U = mapOf(
        'け' to 'く',
        'げ' to 'ぐ',
        'せ' to 'す',
        'ぜ' to 'ず',
        'て' to 'つ',
        'で' to 'ど',
        'ね' to 'ぬ',
        'へ' to 'ふ',
        'べ' to 'ぶ',
        'ぺ' to 'ぷ',
        'め' to 'む',
        'れ' to 'る',
        'え' to 'う',
    )

    private val GODAN_O_TO_U = mapOf(
        'こ' to 'く',
        'ご' to 'ぐ',
        'そ' to 'す',
        'ぞ' to 'ず',
        'と' to 'つ',
        'ど' to 'づ',
        'の' to 'ぬ',
        'ほ' to 'ふ',
        'ぼ' to 'ぶ',
        'ぽ' to 'ぷ',
        'も' to 'む',
        'ろ' to 'る',
        'お' to 'う',
    )
}
