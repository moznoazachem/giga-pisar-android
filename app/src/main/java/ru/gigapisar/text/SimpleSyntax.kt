package ru.gigapisar.text

/**
 * "Simplify punctuation": a single dictated sentence goes in lowercase and without the final
 * period, like a line in a chat. The same rule as on the Mac and on Windows.
 */
object SimpleSyntax {
    private val sentenceEnds = setOf('.', '!', '?', '…', ';')

    /**
     * Is this one sentence? A sentence end inside the text (not at its very end) means there are
     * several, and then nothing is touched. Abbreviations like "т.д." fall under the same rule,
     * which is fine: no guessing on the person's behalf.
     */
    fun simplified(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || t.contains('\n')) return text
        if (t.dropLast(1).any { it in sentenceEnds }) return text

        // A question or an exclamation carries meaning: only the period goes.
        var out = if (t.endsWith(".")) t.dropLast(1) else t
        // An abbreviation or a name stays as is: lowercase only where the capital letter is
        // just the start of the sentence.
        val firstWord = out.substringBefore(' ')
        val allCaps = firstWord.length > 1 && firstWord == firstWord.uppercase()
        val first = out.firstOrNull()
        if (!allCaps && first != null && first.isUpperCase()) {
            out = first.lowercase() + out.drop(1)
        }
        return out
    }
}
