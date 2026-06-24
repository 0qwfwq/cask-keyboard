package com.example.cask.voice

/**
 * Turns a raw speech-recognition hypothesis into the text to insert plus any editor action the user
 * asked for out loud. Two jobs:
 *
 *  1. **Spoken punctuation / formatting** — "comma" → ",", "new line" → "\n", "question mark" → "?",
 *     "open parenthesis" → "(", etc. Each symbol knows how it hugs the surrounding text
 *     ([Attach]): tight punctuation and closing brackets attach to the word on their *left* with no
 *     leading space ("hi," not "hi ,"); opening brackets/quotes attach to the word on their *right*;
 *     everything else (words, free symbols like "&") gets normal spacing. On Android 13+ the
 *     recognizer is asked to format results itself (see [VoiceInputController]), so this pass also
 *     handles already-formatted output: a literal punctuation token like "." is attached, not spaced.
 *  2. **Send-on-speech** — a trailing "send", "send it", "submit"… is stripped and reported as
 *     [Result.send] so the keyboard can fire the field's Send/Go/Done action.
 *
 * Pure and stateless so it is trivial to reason about and unit-test.
 */
object VoiceCommands {

    data class Result(val text: String, val send: Boolean)

    /** How a symbol hugs the text around it. */
    private enum class Attach { LEFT, RIGHT, FREE }

    private class Punct(val text: String, val attach: Attach)

    private fun left(text: String) = Punct(text, Attach.LEFT)
    private fun right(text: String) = Punct(text, Attach.RIGHT)
    private fun free(text: String) = Punct(text, Attach.FREE)

    // Single spoken word → symbol.
    private val ONE_WORD = mapOf(
        "comma" to left(","),
        "period" to left("."),
        "dot" to left("."),
        "colon" to left(":"),
        "semicolon" to left(";"),
        "ellipsis" to left("…"),
        "hyphen" to left("-"),
        "dash" to left("—"),
        "apostrophe" to left("'"),
        "ampersand" to free("&"),
        "asterisk" to free("*"),
        "star" to free("*"),
        "slash" to free("/"),
        "backslash" to free("\\"),
        "hashtag" to free("#"),
        "newline" to left("\n"),
    )

    // Two spoken words → symbol.
    private val TWO_WORD = mapOf(
        "full stop" to left("."),
        "question mark" to left("?"),
        "exclamation mark" to left("!"),
        "exclamation point" to left("!"),
        "quotation mark" to left("\""),
        "new line" to left("\n"),
        "new paragraph" to left("\n\n"),
        "open parenthesis" to right("("),
        "open paren" to right("("),
        "left parenthesis" to right("("),
        "left paren" to right("("),
        "close parenthesis" to left(")"),
        "close paren" to left(")"),
        "right parenthesis" to left(")"),
        "right paren" to left(")"),
        "open bracket" to right("["),
        "left bracket" to right("["),
        "close bracket" to left("]"),
        "right bracket" to left("]"),
        "open brace" to right("{"),
        "left brace" to right("{"),
        "close brace" to left("}"),
        "right brace" to left("}"),
        "open quote" to right("\""),
        "open quotes" to right("\""),
        "close quote" to left("\""),
        "close quotes" to left("\""),
        "at sign" to free("@"),
        "at symbol" to free("@"),
        "dollar sign" to free("\$"),
        "pound sign" to free("#"),
        "number sign" to free("#"),
        "percent sign" to left("%"),
        "smiley face" to free("🙂"),
        "sad face" to free("🙁"),
    )

    // Three spoken words → symbol.
    private val THREE_WORD = mapOf(
        "open square bracket" to right("["),
        "left square bracket" to right("["),
        "close square bracket" to left("]"),
        "right square bracket" to left("]"),
        "open curly brace" to right("{"),
        "left curly brace" to right("{"),
        "close curly brace" to left("}"),
        "right curly brace" to left("}"),
    )

    // Symbols (already produced by the recognizer as literal tokens) that hug the word on their left.
    private const val ATTACH_LEFT_CHARS = ",.!?;:)]}…%\""
    private const val OPEN_CHARS = "([{"

    // Trailing phrases that mean "fire the field's send/go action". Longest first.
    private val SEND_PHRASES = listOf(
        "send it now", "send message", "send the message", "send it", "send that", "send", "submit",
    )

    fun process(raw: String): Result {
        var tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return Result("", false)

        val send = matchTrailingSend(tokens)
        if (send != null) tokens = tokens.dropLast(send)

        val sb = StringBuilder()
        var afterOpen = false // previous emit was an opening bracket/quote/newline: no leading space next
        var i = 0
        while (i < tokens.size) {
            val w1 = clean(tokens[i])
            val w2 = if (i + 1 < tokens.size) clean(tokens[i + 1]) else null
            val w3 = if (i + 2 < tokens.size) clean(tokens[i + 2]) else null

            val three = if (w2 != null && w3 != null) THREE_WORD["$w1 $w2 $w3"] else null
            val two = if (three == null && w2 != null) TWO_WORD["$w1 $w2"] else null
            val one = if (three == null && two == null && w1.isNotEmpty()) ONE_WORD[w1] else null

            when {
                three != null -> { afterOpen = emit(sb, three, afterOpen); i += 3 }
                two != null -> { afterOpen = emit(sb, two, afterOpen); i += 2 }
                one != null -> { afterOpen = emit(sb, one, afterOpen); i += 1 }
                else -> { afterOpen = emitToken(sb, tokens[i], w1, afterOpen); i += 1 }
            }
        }
        return Result(sb.toString().trim(), send != null)
    }

    /** Number of trailing tokens that form a send command, or null if none. */
    private fun matchTrailingSend(tokens: List<String>): Int? {
        val tail = tokens.map { clean(it) }
        for (phrase in SEND_PHRASES) {
            val parts = phrase.split(' ')
            if (tail.size >= parts.size && tail.subList(tail.size - parts.size, tail.size) == parts) {
                return parts.size
            }
        }
        return null
    }

    /** A spoken token reduced to a lowercase, punctuation-free comparison key. */
    private fun clean(token: String): String =
        token.lowercase().trim { !it.isLetterOrDigit() }

    /** Emit a recognised punctuation [Punct]; returns whether the next token should skip its space. */
    private fun emit(sb: StringBuilder, p: Punct, afterOpen: Boolean): Boolean {
        append(sb, p.text, attachLeft = p.attach == Attach.LEFT, afterOpen = afterOpen)
        return p.attach == Attach.RIGHT || p.text.endsWith("\n")
    }

    /**
     * Emit a token that wasn't a spoken command. If the recognizer already formatted it into a literal
     * punctuation mark (so [cleaned] is empty), attach it like the matching spoken symbol instead of
     * spacing it; otherwise it's an ordinary word.
     */
    private fun emitToken(sb: StringBuilder, raw: String, cleaned: String, afterOpen: Boolean): Boolean {
        if (cleaned.isEmpty()) {
            val first = raw.first()
            append(sb, raw, attachLeft = first in ATTACH_LEFT_CHARS, afterOpen = afterOpen)
            return first in OPEN_CHARS || raw.endsWith("\n")
        }
        append(sb, raw, attachLeft = false, afterOpen = afterOpen)
        return false
    }

    private fun append(sb: StringBuilder, text: String, attachLeft: Boolean, afterOpen: Boolean) {
        if (sb.isNotEmpty()) {
            val last = sb.last()
            val noSpace = attachLeft || afterOpen || last == ' ' || last == '\n'
            if (!noSpace) sb.append(' ')
        }
        sb.append(text)
    }
}
