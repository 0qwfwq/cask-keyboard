package com.example.cask.emoji

import android.content.Context
import android.graphics.Paint

/** One emoji plus its lowercase name (used as search keywords). */
data class EmojiItem(val emoji: String, val name: String)

/** A named, ordered group of emoji (e.g. "Smileys & Emotion") with a representative tab icon. */
data class EmojiCategory(val title: String, val icon: String, val items: List<EmojiItem>)

/**
 * The complete emoji set, parsed once from the official Unicode `assets/emoji/emoji-test.txt`
 * (so it is genuinely "every emoji", organized by Unicode's own groups). We keep only
 * `fully-qualified` entries, drop the `Component` group (skin-tone/​hair modifiers that aren't
 * emoji on their own), and fold out skin-tone variants so the grid shows each distinct emoji once.
 *
 * The asset can carry emoji newer than the device knows how to draw. We filter every candidate
 * through [Paint.hasGlyph], so an entry only survives if the system font can actually render it as a
 * real glyph (not a "tofu" □ box). The upshot: ship the latest Unicode set and a phone on a current
 * Android shows the new emoji, while an older phone silently omits the ones it can't display — no
 * per-version lists to maintain.
 *
 * Swap in a newer `emoji-test.txt` to update — no code change needed.
 */
class EmojiData private constructor(
    val categories: List<EmojiCategory>,
    private val index: List<EmojiItem>,
) {

    /** Case-insensitive keyword search across every emoji's name. */
    fun search(query: String, limit: Int = 200): List<EmojiItem> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        return index.asSequence()
            .filter { item -> terms.all { item.name.contains(it) } }
            .take(limit)
            .toList()
    }

    companion object {
        private const val ASSET = "emoji/emoji-test.txt"

        // Skin-tone modifiers U+1F3FB..U+1F3FF (UTF-16: high D83C + low DFFB..DFFF). Folded out so
        // each distinct emoji appears once. Checked via surrogate pairs to stay API-level safe.
        private fun hasSkinTone(s: String): Boolean {
            for (i in 0 until s.length - 1) {
                if (s[i].code == 0xD83C && s[i + 1].code in 0xDFFB..0xDFFF) return true
            }
            return false
        }

        // Per-group tab icon shown in the category bar.
        private val ICONS = mapOf(
            "Smileys & Emotion" to "😀",
            "People & Body" to "👋",
            "Animals & Nature" to "🐶",
            "Food & Drink" to "🍔",
            "Travel & Places" to "✈️",
            "Activities" to "⚽",
            "Objects" to "💡",
            "Symbols" to "❤️",
            "Flags" to "🏁",
        )

        fun load(context: Context): EmojiData {
            val groups = LinkedHashMap<String, MutableList<EmojiItem>>()
            val index = ArrayList<EmojiItem>()
            // Used to test whether the device's font can actually draw each emoji as one glyph.
            val glyphPaint = Paint()
            var group = ""
            runCatching {
                context.assets.open(ASSET).bufferedReader().forEachLine { raw ->
                    val line = raw.trimEnd()
                    when {
                        line.startsWith("# group:") -> group = line.substringAfter("# group:").trim()
                        line.isEmpty() || line.startsWith("#") -> Unit
                        line.contains("; fully-qualified") -> {
                            if (group == "Component") return@forEachLine
                            val afterHash = line.substringAfter("# ", "").trim()
                            if (afterHash.isEmpty()) return@forEachLine
                            val emoji = afterHash.substringBefore(' ')
                            if (emoji.isEmpty() || hasSkinTone(emoji)) return@forEachLine
                            // Skip emoji this device can't render (too new for its font) so the grid
                            // never shows "tofu" boxes — see the class doc.
                            if (!glyphPaint.hasGlyph(emoji)) return@forEachLine
                            // afterHash = "<emoji> E<ver> <name...>" — drop the emoji + version tokens.
                            val name = afterHash.substringAfter(' ').substringAfter(' ').lowercase()
                            val item = EmojiItem(emoji, name)
                            groups.getOrPut(group) { ArrayList() }.add(item)
                            index.add(item)
                        }
                    }
                }
            }
            val categories = groups.map { (title, items) ->
                EmojiCategory(title, ICONS[title] ?: "★", items)
            }
            return EmojiData(categories, index)
        }
    }
}
