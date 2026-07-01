package com.example.cask.engine

/**
 * The tools-row **Fix** action: a one-tap, fully on-device cleanup pass over text that has already
 * been typed. Where the live autocorrect can only act on the word being composed, this re-reads the
 * whole field and repairs it in one shot:
 *
 *  - **Spelling** — every word is re-scored through the same local noisy-channel model the live
 *    autocorrect uses ([CorrectionEngine.fixWord]: curated corrections, proximity-weighted edit
 *    distance, n-gram context), with the two preceding words as context.
 *  - **Spacing** — runs of spaces collapse to one, stray spaces before `, . ! ? ; :` are removed,
 *    and the missing space after sentence punctuation is restored when a word follows it.
 *  - **Capitalization** — the first word of each sentence/line and standalone `i` are capitalized.
 *
 * It never touches URLs, e-mail addresses, @handles/#tags, numbers, emoticons (`:)` stays `:)`),
 * words with intentional inner capitals, or capitalized mid-sentence words (likely proper nouns).
 * Nothing is ever lowercased. Everything runs locally; the text never leaves the device.
 */
object TextFixer {

    /** Spans copied through untouched: URLs, e-mail addresses, @handles and #tags. */
    private val PROTECTED = Regex(
        "(https?://\\S+)|(www\\.\\S+)|([\\w.+-]+@[\\w-]+(\\.[\\w-]+)+)|((?<![\\w.])[@#]\\w+)",
    )

    private const val APOSTROPHES = "'’"
    private const val CLINGING = ",.!?;:"          // punctuation that hugs the word before it
    private const val SENTENCE_END = ".!?"
    private const val EMOTICON_EYES = ":;"
    private const val EMOTICON_MOUTHS = ")(DPpOo3/\\|*\$]["

    /**
     * Fix [text]. [fixWord] is the per-word spelling model: it receives a word exactly as typed, the
     * two (lowercased) words before it, and whether the statistical model may fire (false for likely
     * proper nouns); it returns the corrected form or null to keep the word.
     */
    fun fix(text: String, fixWord: (raw: String, prev1: String?, prev2: String?, allowStatistical: Boolean) -> String?): String {
        if (text.isBlank()) return text
        val protectedSpans = PROTECTED.findAll(text).map { it.range }.toList()
        val out = StringBuilder(text.length + 16)
        var i = 0
        var protIdx = 0
        var p1: String? = null // previous two committed words, lowercased (the model's context)
        var p2: String? = null
        var capitalize = true  // the next word starts a sentence
        var prevWordLen = 0    // length of the last word (1 ⇒ a following '.' reads as "e.g."-style)

        while (i < text.length) {
            while (protIdx < protectedSpans.size && protectedSpans[protIdx].last < i) protIdx++
            val prot = protectedSpans.getOrNull(protIdx)
            if (prot != null && i == prot.first) {
                spaceBeforeToken(out, tokenLen = 2, prevWordLen)
                out.append(text, prot.first, prot.last + 1)
                i = prot.last + 1
                capitalize = false
                p1 = null; p2 = null
                prevWordLen = 2
                continue
            }
            val protStart = prot?.first ?: text.length

            val ch = text[i]
            when {
                ch.isLetter() -> {
                    var j = i + 1
                    while (j < protStart &&
                        (text[j].isLetter() || (text[j] in APOSTROPHES && j + 1 < text.length && text[j + 1].isLetter()))
                    ) j++
                    val raw = text.substring(i, j)
                    i = j
                    // A capitalized word mid-sentence is likely a name: curated fixes only.
                    val allowStatistical = !(raw[0].isUpperCase() && !capitalize)
                    var word = fixWord(raw, p1, p2, allowStatistical) ?: raw
                    if (capitalize && word[0].isLowerCase()) word = word.replaceFirstChar(Char::uppercase)
                    spaceBeforeToken(out, raw.length, prevWordLen)
                    out.append(word)
                    val token = word.lowercase().substringAfterLast(' ')
                    p2 = p1; p1 = token
                    capitalize = false
                    prevWordLen = token.length
                }

                ch.isWhitespace() -> {
                    var j = i
                    var newlines = 0
                    while (j < text.length && text[j].isWhitespace()) {
                        if (text[j] == '\n') newlines++
                        j++
                    }
                    i = j
                    if (newlines > 0) {
                        // Keep the line structure; drop spaces hugging the break. A new line starts a
                        // new thought: capitalize it and don't carry word context across.
                        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) {
                            out.deleteCharAt(out.length - 1)
                        }
                        repeat(newlines) { out.append('\n') }
                        capitalize = true
                        p1 = null; p2 = null
                        prevWordLen = 0
                    } else if (out.isNotEmpty() && !out.last().isWhitespace()) {
                        out.append(' ') // collapse the run to a single space
                    }
                }

                ch.isDigit() -> {
                    var j = i + 1
                    while (j < text.length && (text[j].isDigit() ||
                            (text[j] in ".,:" && j + 1 < text.length && text[j + 1].isDigit()))
                    ) j++
                    spaceBeforeToken(out, tokenLen = 2, prevWordLen)
                    out.append(text, i, j)
                    i = j
                    capitalize = false
                    prevWordLen = 2
                }

                ch in CLINGING -> {
                    // An emoticon (":)", ";D") is not punctuation — copy it through untouched.
                    if (ch in EMOTICON_EYES && i + 1 < text.length && text[i + 1] in EMOTICON_MOUTHS) {
                        out.append(ch).append(text[i + 1])
                        i += 2
                        continue
                    }
                    while (out.isNotEmpty() && out.last() == ' ') out.deleteCharAt(out.length - 1)
                    out.append(ch)
                    i++
                    // "e.g."/"i.e." style dots after a single letter aren't sentence ends.
                    if (ch in SENTENCE_END && !(ch == '.' && prevWordLen == 1)) {
                        capitalize = true
                        p1 = null; p2 = null
                    }
                }

                else -> { // quotes, brackets, emoji, symbols: verbatim
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * Restore the missing space between clinging punctuation and a following token ("hi,there" →
     * "hi, there"). Single letters around a dot stay glued so "e.g."/"i.e." survive.
     */
    private fun spaceBeforeToken(out: StringBuilder, tokenLen: Int, prevWordLen: Int) {
        if (out.isEmpty()) return
        val last = out.last()
        if (last !in CLINGING) return
        if (last == '.' && (tokenLen == 1 || prevWordLen == 1)) return
        out.append(' ')
    }
}
