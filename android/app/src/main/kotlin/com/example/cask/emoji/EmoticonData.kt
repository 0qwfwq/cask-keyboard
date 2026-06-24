package com.example.cask.emoji

import android.content.Context

/** One text emoticon (kaomoji) plus its search keywords. */
data class EmoticonItem(val text: String, val keywords: String)

/**
 * The emoticon (kaomoji) list, loaded from `assets/emoji/emoticons.txt`
 * (`emoticon<TAB>keywords` per line). Fully searchable; expand the asset to grow the list.
 */
class EmoticonData private constructor(val all: List<EmoticonItem>) {

    /** Case-insensitive search across keywords and the emoticon text itself. */
    fun search(query: String, limit: Int = 200): List<EmoticonItem> {
        val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return all
        return all.asSequence()
            .filter { item ->
                val hay = item.keywords + " " + item.text.lowercase()
                terms.all { hay.contains(it) }
            }
            .take(limit)
            .toList()
    }

    companion object {
        private const val ASSET = "emoji/emoticons.txt"

        fun load(context: Context): EmoticonData {
            val items = ArrayList<EmoticonItem>()
            runCatching {
                context.assets.open(ASSET).bufferedReader().forEachLine { raw ->
                    val line = raw.trimEnd('\n', '\r')
                    if (line.isBlank() || line.startsWith("#")) return@forEachLine
                    val tab = line.indexOf('\t')
                    if (tab <= 0) {
                        items.add(EmoticonItem(line.trim(), ""))
                    } else {
                        val text = line.substring(0, tab)
                        val keywords = line.substring(tab + 1).trim().lowercase()
                        items.add(EmoticonItem(text, keywords))
                    }
                }
            }
            return EmoticonData(items)
        }
    }
}
