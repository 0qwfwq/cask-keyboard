package com.example.cask.engine

import kotlin.math.ln

/**
 * The **language model** half of the noisy channel: how likely a word is, both on its own and given
 * the one or two words before it. It interpolates four sources with additive (Laplace) smoothing:
 *
 *  - **unigram** — base dictionary frequency, boosted by how often *you* use the word;
 *  - **shipped bigram** — a fixed English `P(w2 | w1)` prior ([NgramModel]) so context works from the
 *    first launch, before the keyboard has learned anything about you;
 *  - **personal bigram / trigram** — learned from your own writing; weighted *above* the shipped prior
 *    so your phrasing progressively wins as it's seen.
 *
 * This is the change that makes prediction and context-aware autocorrect work cold: previously the
 * only context signal was the personal model, which is empty on a fresh install. The model now starts
 * as a real English context model and personalises over time. It produces both the candidate-scoring
 * term the noisy channel adds and the next-word predictions shown in the suggestion strip.
 */
class LanguageModel(
    private val dict: Dictionary,
    private val store: PersonalStore,
    private val ship: NgramModel,
) {

    /** `P(word)` — base frequency plus a strong boost for your own (confirmed) usage. */
    fun unigramProb(word: String): Double {
        val w = word.lowercase()
        val num = dict.baseCount(w) + PERSONAL_UNIGRAM_WEIGHT * store.confirmedFreq(w) + UNI_ADD
        val den = dict.baseTotal + UNI_ADD * VEFF
        return num / den
    }

    /** `log P(word | prev1, prev2)` via interpolated unigram + shipped-bigram + personal bi/trigram. */
    fun contextLogProb(word: String, prev1: String?, prev2: String?): Double {
        val w = word.lowercase()
        val c1 = prev1?.lowercase()
        val c2 = prev2?.lowercase()

        var num = LAM_UNI * unigramProb(w)
        var den = LAM_UNI
        if (c1 != null) {
            // Shipped English bigram prior (always available for common contexts).
            val st = ship.biTotal(c1)
            if (st > 0) {
                val pShip = (ship.biCount(c1, w) + NG_ADD) / (st + NG_ADD * VEFF)
                num += LAM_SHIP * pShip
                den += LAM_SHIP
            }
            // Shipped English trigram prior — sharper context from the previous *two* words.
            if (c2 != null) {
                val stt = ship.triTotal(c2, c1)
                if (stt > 0) {
                    val pShipTri = (ship.triCount(c2, c1, w) + NG_ADD) / (stt + NG_ADD * VEFF)
                    num += LAM_SHIP_TRI * pShipTri
                    den += LAM_SHIP_TRI
                }
            }
            // Personal bigram (your own phrasing) — weighted above the shipped prior.
            val bt = store.biTotal(c1)
            if (bt > 0) {
                val pBi = (store.biCount(c1, w) + NG_ADD) / (bt + NG_ADD * VEFF)
                num += LAM_BI * pBi
                den += LAM_BI
                if (c2 != null) {
                    val tt = store.triTotal(c2, c1)
                    if (tt > 0) {
                        val pTri = (store.triCount(c2, c1, w) + NG_ADD) / (tt + NG_ADD * VEFF)
                        num += LAM_TRI * pTri
                        den += LAM_TRI
                    }
                }
            }
        }
        return ln(num / den)
    }

    /**
     * Next-word predictions for the strip, blending normalised conditional probabilities: your
     * trigram successors first, then your bigram successors, then the shipped English bigram prior, so
     * the strip is genuinely useful from launch and personalises over time. A curated filler list only
     * fills any remaining slots. Returns lowercase words (caller re-cases); restricted to real words
     * and never a known-typo form (so `dont`/`im` are never *predicted* even though they're frequent).
     */
    fun predictNext(prev1: String?, prev2: String?, limit: Int): List<String> {
        val c1 = prev1?.lowercase()
        val c2 = prev2?.lowercase()
        val scores = HashMap<String, Double>()

        if (c1 != null && c2 != null) {
            val tt = store.triTotal(c2, c1).toDouble()
            if (tt > 0) for ((w, c) in store.trigramSuccessors(c2, c1)) {
                scores[w] = (scores[w] ?: 0.0) + W_TRI * (c / tt)
            }
            // Shipped English trigram successors — context-sharp predictions from first launch.
            val stt = ship.triTotal(c2, c1)
            if (stt > 0) for ((w, c) in ship.triSuccessors(c2, c1).take(SHIP_PRED_K)) {
                scores[w] = (scores[w] ?: 0.0) + W_SHIP_TRI * (c / stt)
            }
        }
        if (c1 != null) {
            val bt = store.biTotal(c1).toDouble()
            if (bt > 0) for ((w, c) in store.bigramSuccessors(c1)) {
                scores[w] = (scores[w] ?: 0.0) + W_BI * (c / bt)
            }
            val st = ship.biTotal(c1)
            if (st > 0) for ((w, c) in ship.successors(c1).take(SHIP_PRED_K)) {
                scores[w] = (scores[w] ?: 0.0) + W_SHIP * (c / st)
            }
        }
        if (scores.size < limit + 2) {
            // Curated fillers rank below any learned/shipped signal so the strip stays useful, not generic.
            val fillers = if (c1 == null) CommonCorrections.SENTENCE_STARTERS else CommonCorrections.COMMON_NEXT
            fillers.forEachIndexed { i, raw ->
                val w = raw.lowercase()
                if (!scores.containsKey(w)) scores[w] = W_FILLER * (fillers.size - i)
            }
        }
        return scores.entries
            .asSequence()
            .filter { it.key.length > 1 || it.key == "i" || it.key == "a" }
            .filter { it.key != c1 }
            .filter { isRealWord(it.key) }
            .filter { !CommonCorrections.isAutoTypo(it.key) }
            .sortedByDescending { it.value }
            .map { it.key }
            .take(limit)
            .toList()
    }

    /** A word fit to surface in predictions: dictionary, confirmed-personal, or a curated prediction word. */
    private fun isRealWord(word: String): Boolean =
        dict.isKnown(word) || store.confirmedFreq(word) > 0 || word in CommonCorrections.PREDICTION_WHITELIST

    private companion object {
        const val VEFF = 30_000.0                 // effective vocabulary for smoothing
        const val UNI_ADD = 0.5                   // unigram Laplace constant
        const val NG_ADD = 0.4                    // n-gram Laplace constant
        const val PERSONAL_UNIGRAM_WEIGHT = 60.0  // how hard your own usage boosts a word
        const val LAM_UNI = 0.25                  // interpolation weights (renormalised per context)
        const val LAM_SHIP = 0.30                 // shipped English bigram prior
        const val LAM_SHIP_TRI = 0.35             // shipped English trigram prior (sharper than the bigram)
        const val LAM_BI = 0.40                   // personal bigram (above the shipped prior)
        const val LAM_TRI = 0.50                  // personal trigram (highest when available)
        const val W_TRI = 6.0                     // prediction source weights (over normalised probs)
        const val W_BI = 3.0
        const val W_SHIP_TRI = 2.0                // shipped trigram successors (above shipped bigram)
        const val W_SHIP = 1.0
        const val SHIP_PRED_K = 12                // shipped successors considered per context
        const val W_FILLER = 0.001                // curated fallback predictions (below any real signal)
    }
}
