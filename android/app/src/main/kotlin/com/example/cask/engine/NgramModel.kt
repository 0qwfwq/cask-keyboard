package com.example.cask.engine

import android.content.Context
import java.io.BufferedReader

/**
 * The **shipped** English n-gram language model: a fixed `P(w2 | w1)` bigram prior plus a
 * `P(w3 | w1, w2)` trigram prior, loaded once from `assets/dictionary/bigrams.txt` (Norvig's
 * `count_2w.txt`) and `assets/dictionary/trigrams.txt` (built from Norvig's public-domain `big.txt`
 * by `tools/build_trigrams.py`).
 *
 * Why it exists: Cask's context model used to be learned *only* from your own typing, so a fresh
 * install had no language context at all — next-word prediction fell back to a static filler list,
 * and autocorrect could never use context to disambiguate. These priors give real English context
 * from first launch; the [PersonalStore] bi/trigram model interpolates on top and progressively wins
 * as it learns your phrasing (see [LanguageModel]).
 *
 * Layout: each asset is grouped by its context (the first word for bigrams, the first two for
 * trigrams) with successors heaviest-first, so the in-memory form is one [Ctx] per context holding
 * the successor counts, their running total, and the pre-ordered successor list used directly for
 * prediction. Everything is on-device; no network.
 */
class NgramModel private constructor(
    private val contexts: HashMap<String, Ctx>,
    private val triContexts: HashMap<String, Ctx>,
) {

    /** Successors of one context: lookup counts, the total mass, and the heaviest-first list. */
    class Ctx(
        val counts: HashMap<String, Int>,
        val total: Double,
        val ordered: List<Pair<String, Int>>,
    )

    /** `count(c1, w)` in the shipped bigram model (0 if the pair wasn't shipped). */
    fun biCount(c1: String, w: String): Int = contexts[c1]?.counts?.get(w) ?: 0

    /** Total successor mass for `c1` (0.0 if `c1` has no shipped successors). */
    fun biTotal(c1: String): Double = contexts[c1]?.total ?: 0.0

    /** Shipped bigram successors of `c1`, heaviest first — the next-word prediction backbone. */
    fun successors(c1: String): List<Pair<String, Int>> = contexts[c1]?.ordered ?: emptyList()

    /** `count(c2, c1, w)` in the shipped trigram model (0 if the triple wasn't shipped). */
    fun triCount(c2: String, c1: String, w: String): Int = triContexts[triKey(c2, c1)]?.counts?.get(w) ?: 0

    /** Total successor mass for the `(c2, c1)` trigram context (0.0 if none shipped). */
    fun triTotal(c2: String, c1: String): Double = triContexts[triKey(c2, c1)]?.total ?: 0.0

    /** Shipped trigram successors of `(c2, c1)`, heaviest first — sharper next-word prediction. */
    fun triSuccessors(c2: String, c1: String): List<Pair<String, Int>> =
        triContexts[triKey(c2, c1)]?.ordered ?: emptyList()

    fun hasContext(c1: String): Boolean = contexts.containsKey(c1)

    val contextCount: Int get() = contexts.size

    companion object {
        private const val ASSET = "dictionary/bigrams.txt"
        private const val TRI_ASSET = "dictionary/trigrams.txt"
        // Trigram context-key separator. A space never appears inside a token (words are [a-z']), so it
        // cannot collide with a real word boundary.
        private const val SEP = " "

        private fun triKey(c2: String, c1: String) = c2 + SEP + c1

        /** Empty model — used as a safe fallback if the assets are missing/unreadable. */
        fun empty(): NgramModel = NgramModel(HashMap(), HashMap())

        /**
         * Load the shipped bigram + trigram models. [inVocab] (optional) drops any n-gram whose words
         * aren't in the loaded vocabulary, keeping the model consistent with what can be surfaced.
         */
        fun load(context: Context, inVocab: ((String) -> Boolean)? = null): NgramModel {
            val contexts = loadGrouped(context, ASSET, keyCols = 1, inVocab)
            val triContexts = loadGrouped(context, TRI_ASSET, keyCols = 2, inVocab)
            return NgramModel(contexts, triContexts)
        }

        /**
         * Parse a grouped n-gram asset into `context -> Ctx`. Each line is `key…<TAB>successor<TAB>count`;
         * [keyCols] is how many leading tab fields form the context (1 for bigrams `w1`, 2 for trigrams
         * `w1 w2`). The context key joins those fields with [SEP].
         */
        private fun loadGrouped(
            context: Context,
            asset: String,
            keyCols: Int,
            inVocab: ((String) -> Boolean)?,
        ): HashMap<String, Ctx> {
            val raw = HashMap<String, ArrayList<Pair<String, Int>>>()
            runCatching {
                context.assets.open(asset).bufferedReader().use { reader: BufferedReader ->
                    reader.forEachLine { line ->
                        if (line.isEmpty() || line[0] == '#') return@forEachLine
                        val cols = line.split('\t')
                        if (cols.size < keyCols + 2) return@forEachLine
                        val keyWords = cols.subList(0, keyCols)
                        val w = cols[keyCols]
                        val count = cols[keyCols + 1].trim().toIntOrNull() ?: return@forEachLine
                        if (inVocab != null && (keyWords.any { !inVocab(it) } || !inVocab(w))) return@forEachLine
                        raw.getOrPut(keyWords.joinToString(SEP)) { ArrayList(8) }.add(w to count)
                    }
                }
            }
            val out = HashMap<String, Ctx>(raw.size * 2)
            for ((key, list) in raw) {
                // The asset is already heaviest-first per context; guard with a sort in case it isn't.
                list.sortByDescending { it.second }
                val counts = HashMap<String, Int>(list.size * 2)
                var total = 0.0
                for ((wd, c) in list) {
                    counts[wd] = c
                    total += c
                }
                out[key] = Ctx(counts, total, list)
            }
            return out
        }
    }
}
