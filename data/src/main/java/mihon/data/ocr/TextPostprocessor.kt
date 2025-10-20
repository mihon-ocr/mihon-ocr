package mihon.data.ocr

import java.util.regex.Pattern

class TextPostprocessor {

    companion object {
        private val ELLIPSIS_PATTERN = Pattern.compile("[・.]{2,}")

        // Half-width to full-width conversion mappings for Japanese text
        private val HALF_TO_FULL_ASCII = mapOf(
            '!' to '！', '"' to '"', '#' to '＃', '$' to '＄', '%' to '％',
            '&' to '＆', '\'' to '\'', '(' to '（', ')' to '）', '*' to '＊',
            '+' to '＋', ',' to '，', '-' to '－', '.' to '．', '/' to '／',
            '0' to '０', '1' to '１', '2' to '２', '3' to '３', '4' to '４',
            '5' to '５', '6' to '６', '7' to '７', '8' to '８', '9' to '９',
            ':' to '：', ';' to '；', '<' to '＜', '=' to '＝', '>' to '＞',
            '?' to '？', '@' to '＠',
            'A' to 'Ａ', 'B' to 'Ｂ', 'C' to 'Ｃ', 'D' to 'Ｄ', 'E' to 'Ｅ',
            'F' to 'Ｆ', 'G' to 'Ｇ', 'H' to 'Ｈ', 'I' to 'Ｉ', 'J' to 'Ｊ',
            'K' to 'Ｋ', 'L' to 'Ｌ', 'M' to 'Ｍ', 'N' to 'Ｎ', 'O' to 'Ｏ',
            'P' to 'Ｐ', 'Q' to 'Ｑ', 'R' to 'Ｒ', 'S' to 'Ｓ', 'T' to 'Ｔ',
            'U' to 'Ｕ', 'V' to 'Ｖ', 'W' to 'Ｗ', 'X' to 'Ｘ', 'Y' to 'Ｙ',
            'Z' to 'Ｚ',
            '[' to '［', '\\' to '＼', ']' to '］', '^' to '＾', '_' to '＿',
            '`' to '\'',
            'a' to 'ａ', 'b' to 'ｂ', 'c' to 'ｃ', 'd' to 'ｄ', 'e' to 'ｅ',
            'f' to 'ｆ', 'g' to 'ｇ', 'h' to 'ｈ', 'i' to 'ｉ', 'j' to 'ｊ',
            'k' to 'ｋ', 'l' to 'ｌ', 'm' to 'ｍ', 'n' to 'ｎ', 'o' to 'ｏ',
            'p' to 'ｐ', 'q' to 'ｑ', 'r' to 'ｒ', 's' to 'ｓ', 't' to 'ｔ',
            'u' to 'ｕ', 'v' to 'ｖ', 'w' to 'ｗ', 'x' to 'ｘ', 'y' to 'ｙ',
            'z' to 'ｚ',
            '{' to '｛', '|' to '｜', '}' to '｝', '~' to '～'
        )
    }

    fun postprocess(text: String): String {
        var result = text

        // Remove all whitespace
        result = result.replace("\\s".toRegex(), "")

        // Replace horizontal ellipsis with three dots
        result = result.replace("…", "...")

        // Replace multiple dots or middots with equivalent number of periods
        val matcher = ELLIPSIS_PATTERN.matcher(result)
        val sb = StringBuffer()
        while (matcher.find()) {
            val length = matcher.end() - matcher.start()
            matcher.appendReplacement(sb, ".".repeat(length))
        }
        matcher.appendTail(sb)
        result = sb.toString()

        result = halfToFullWidth(result)

        return result
    }

    // Convert half-width ASCII characters and digits to full-width
    private fun halfToFullWidth(text: String): String {
        val result = StringBuilder(text.length)
        for (char in text) {
            result.append(HALF_TO_FULL_ASCII[char] ?: char)
        }
        return result.toString()
    }
}
