package com.example.cask.engine

import android.graphics.PointF
import kotlin.math.max

/**
 * The **error model** half of the noisy channel: how likely is it that a user who *meant* a given
 * candidate word actually *typed* the observed string? It is a weighted Damerau–Levenshtein distance
 * where:
 *
 *  - substitutions are priced by physical key distance ([KeyGeometry]) — neighbour slips are cheap;
 *  - adjacent transpositions ("teh"→"the") are cheap, because they're one of the most common typos;
 *  - doubled-letter mistakes ("hapy"/"happpy") are discounted, because finger-bounce is common.
 *
 * The raw alignment cost is turned into a log-probability with [logProb]: `log P(typed | candidate)`.
 * Lower cost ⇒ higher probability. The orchestrator adds this to the language-model term.
 */
object EditModel {

    /** Weighted optimal-string-alignment (restricted Damerau) cost between [typed] and [candidate]. */
    fun cost(typed: String, candidate: String): Double {
        val s = typed
        val t = candidate
        val n = s.length
        val m = t.length
        if (n == 0) return m * KeyGeometry.DELETE_COST
        if (m == 0) return n * KeyGeometry.INSERT_COST

        // dp[i][j] = cost to explain s[0..i) as a typing of t[0..j).
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) {
            val doubled = i >= 2 && s[i - 1] == s[i - 2]
            dp[i][0] = dp[i - 1][0] + if (doubled) KeyGeometry.DOUBLE_LETTER_COST else KeyGeometry.INSERT_COST
        }
        for (j in 1..m) {
            val doubled = j >= 2 && t[j - 1] == t[j - 2]
            dp[0][j] = dp[0][j - 1] + if (doubled) KeyGeometry.DOUBLE_LETTER_COST else KeyGeometry.DELETE_COST
        }

        for (i in 1..n) {
            for (j in 1..m) {
                val insCost = if (i >= 2 && s[i - 1] == s[i - 2]) KeyGeometry.DOUBLE_LETTER_COST else KeyGeometry.INSERT_COST
                val delCost = if (j >= 2 && t[j - 1] == t[j - 2]) KeyGeometry.DOUBLE_LETTER_COST else KeyGeometry.DELETE_COST
                var best = minOf(
                    dp[i - 1][j] + insCost,                                   // typed an extra char
                    dp[i][j - 1] + delCost,                                   // missed a char
                    dp[i - 1][j - 1] + KeyGeometry.substitutionCost(s[i - 1], t[j - 1]),
                )
                if (i >= 2 && j >= 2 && s[i - 1] == t[j - 2] && s[i - 2] == t[j - 1]) {
                    best = minOf(best, dp[i - 2][j - 2] + KeyGeometry.TRANSPOSE_COST)
                }
                dp[i][j] = best
            }
        }
        return dp[n][m]
    }

    /**
     * `log P(typed | candidate)`. The cost is scaled into nats; we divide by an effective length so a
     * one-key slip in a long word isn't penalised more than the same slip in a short word.
     */
    fun logProb(typed: String, candidate: String): Double {
        val c = cost(typed, candidate)
        val norm = max(1, max(typed.length, candidate.length))
        return -ERROR_SCALE * c / norm * EFFECTIVE_LEN
    }

    /**
     * The **spatial** error term: `log P(touches | candidate)` when the exact tap position of every
     * letter is known. Same alignment DP as [cost], but each substitution is priced by how far the
     * actual touch fell from the candidate letter's key ([KeyGeometry.spatialSubCost]) instead of by a
     * fixed key-to-key distance. This is the GBoard-style decode: a tap that landed between two keys is
     * resolved by *where the finger was*, jointly with the language model in the orchestrator.
     */
    fun spatialLogProb(touches: List<PointF>, candidate: String): Double {
        val c = spatialCost(touches, candidate)
        val norm = max(1, max(touches.size, candidate.length))
        return -ERROR_SCALE * c / norm * EFFECTIVE_LEN
    }

    /** Weighted alignment cost between the [touches] sequence and [candidate] using the spatial model. */
    private fun spatialCost(touches: List<PointF>, candidate: String): Double {
        val n = touches.size
        val m = candidate.length
        if (n == 0) return m * KeyGeometry.DELETE_COST
        if (m == 0) return n * KeyGeometry.INSERT_COST

        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) dp[i][0] = dp[i - 1][0] + KeyGeometry.INSERT_COST
        for (j in 1..m) dp[0][j] = dp[0][j - 1] + KeyGeometry.DELETE_COST

        for (i in 1..n) {
            val tx = touches[i - 1].x
            val ty = touches[i - 1].y
            for (j in 1..m) {
                var best = minOf(
                    dp[i - 1][j] + KeyGeometry.INSERT_COST,                              // an extra tap
                    dp[i][j - 1] + KeyGeometry.DELETE_COST,                              // a missed letter
                    dp[i - 1][j - 1] + KeyGeometry.spatialSubCost(tx, ty, candidate[j - 1]),
                )
                // Transposition: the two taps fit the swapped letters. Priced at TRANSPOSE_COST plus how
                // poorly they actually fit, so a genuine swap stays cheap and a coincidence does not.
                if (i >= 2 && j >= 2) {
                    val a = KeyGeometry.spatialSubCost(tx, ty, candidate[j - 2])
                    val b = KeyGeometry.spatialSubCost(touches[i - 2].x, touches[i - 2].y, candidate[j - 1])
                    best = minOf(best, dp[i - 2][j - 2] + KeyGeometry.TRANSPOSE_COST + 0.5 * (a + b))
                }
                dp[i][j] = best
            }
        }
        return dp[n][m]
    }

    /** A generation budget for [Trie.fuzzySearch]: a little headroom over two plausible edits. */
    fun budgetFor(typed: String): Double =
        (BASE_BUDGET + typed.length * PER_CHAR_BUDGET).coerceAtMost(MAX_BUDGET)

    private const val ERROR_SCALE = 3.6   // weight of the error model vs. the language model (lower => context decides ties)
    private const val EFFECTIVE_LEN = 5.0 // reference word length for cost normalisation
    private const val BASE_BUDGET = 2.0
    private const val PER_CHAR_BUDGET = 0.2
    private const val MAX_BUDGET = 4.0
}
