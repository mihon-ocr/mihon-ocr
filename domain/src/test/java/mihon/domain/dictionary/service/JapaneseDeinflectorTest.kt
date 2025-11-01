package mihon.domain.dictionary.service

import dev.esnault.wanakana.core.Wanakana
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class JapaneseDeinflectorTest {

    @Test
    fun `deinflect handles polite ichidan verb`() {
        val forms = JapaneseDeinflector.deinflect("食べます")

        forms shouldContain "食べる"
    }

    @Test
    fun `deinflect handles polite godan verb`() {
        val forms = JapaneseDeinflector.deinflect("読みました")

        forms shouldContain "読む"
    }

    @Test
    fun `deinflect handles progressive form`() {
        val forms = JapaneseDeinflector.deinflect("読んでいます")

        forms shouldContain "読む"
    }

    @Test
    fun `deinflect handles romaji input`() {
        val kana = Wanakana.toKana("tabemashita")
        val forms = JapaneseDeinflector.deinflect(kana)

        forms shouldContain "たべる"
    }

    @Test
    fun `deinflect handles adjective negatives`() {
        val forms = JapaneseDeinflector.deinflect("よくない")

        forms shouldContainAll listOf("よい", "いい")
    }

    @Test
    fun `deinflect handles adjective past`() {
        val forms = JapaneseDeinflector.deinflect("かわいかった")

        forms shouldContain "かわいい"
    }

    @Test
    fun `deinflect handles irregular iku`() {
        val forms = JapaneseDeinflector.deinflect("行って")

        forms shouldContain "行く"
    }

    @Test
    fun `deinflect handles suru compound`() {
        val forms = JapaneseDeinflector.deinflect("勉強しました")

        forms shouldContain "勉強する"
    }

    @Test
    fun `deinflect handles kuru polite negative`() {
        val forms = JapaneseDeinflector.deinflect("来ませんでした")

        forms shouldContain "来る"
    }

    @Test
    fun `deinflect handles kuru plain negative`() {
        val forms = JapaneseDeinflector.deinflect("こなかった")

        forms shouldContain "くる"
    }
}
