package com.example.cask.engine

import android.content.Context
import java.io.BufferedReader

/**
 * The base (shipped) vocabulary with word frequencies, loaded once from
 * `assets/dictionary/en.txt`. Provides the shared [Trie] used for candidate generation and
 * completion, plus the raw base counts the language model needs for `P(word)`.
 *
 * The asset is frequency-ordered; a line may be a bare `word` (count derived from its rank via a
 * Zipf curve) or `word<TAB>count` (count used directly), so a full real frequency list drops in
 * without code changes. Personal/learned words are inserted into [trie] separately at runtime by the
 * engine but are *not* part of [baseCount].
 */
class Dictionary private constructor(
    val trie: Trie,
    private val counts: HashMap<String, Double>,
    val baseTotal: Double,
    private val top: List<String>,
) {

    fun baseCount(word: String): Double = counts[word.lowercase()] ?: 0.0

    fun isKnown(word: String): Boolean = counts.containsKey(word.lowercase())

    /** Globally most common words, highest first — the fallback for next-word prediction. */
    fun topWords(limit: Int): List<String> = if (top.size > limit) top.subList(0, limit) else top

    companion object {
        private const val ASSET = "dictionary/en.txt"
        private const val ZIPF_BASE = 5_000_000.0

        // A full frequency list (e.g. Norvig's 333k words) would build a huge in-memory trie at
        // startup. Keep only the most frequent words — far more than anyone types — so launch stays
        // fast and memory stays bounded regardless of how large the asset is.
        private const val MAX_WORDS = 60_000

        fun load(context: Context): Dictionary {
            // Pass 1: read every entry (deduped). Pass 2: keep the most frequent and build the trie.
            val entries = ArrayList<Pair<String, Double>>()
            val seen = HashSet<String>()
            var rank = 0
            runCatching {
                context.assets.open(ASSET).bufferedReader().use { reader: BufferedReader ->
                    reader.forEachLine { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                        val tab = line.indexOf('\t')
                        val word: String
                        val count: Double
                        if (tab >= 0) {
                            word = line.substring(0, tab).trim().lowercase()
                            count = line.substring(tab + 1).trim().toDoubleOrNull() ?: 1.0
                        } else {
                            word = line.lowercase()
                            rank++
                            count = (ZIPF_BASE / rank)
                        }
                        if (word.isEmpty() || !seen.add(word)) return@forEachLine
                        entries.add(word to count)
                    }
                }
            }
            val kept = if (entries.size > MAX_WORDS) {
                entries.sortedByDescending { it.second }.subList(0, MAX_WORDS)
            } else {
                entries
            }

            val trie = Trie()
            val counts = HashMap<String, Double>(kept.size * 2)
            val ordered = ArrayList<String>(kept.size)
            var total = 0.0
            for ((word, count) in kept) {
                counts[word] = count
                trie.insert(word, count)
                ordered.add(word)
                total += count
            }
            val top = ordered.sortedByDescending { counts[it] ?: 0.0 }
            return Dictionary(trie, counts, total.coerceAtLeast(1.0), top)
        }
    }
}
