package com.example.cask.engine

/**
 * A prefix tree over the lowercase vocabulary. It does two jobs for the engine:
 *
 *  1. **Completion** — [prefixMatches] returns the highest-weighted words that start with what the
 *     user has typed so far (e.g. "keybo" → "keyboard").
 *  2. **Correction candidate generation** — [fuzzySearch] walks the trie while maintaining a single
 *     weighted-edit-distance DP row against the typed string, pruning any branch whose best possible
 *     cost already exceeds the budget. This yields, in one pass, every dictionary word reachable
 *     within a small number of (proximity-weighted) edits — the candidate set the noisy-channel
 *     scorer then ranks.
 *
 * Substitution costs come from [KeyGeometry], so candidate generation is already spatially aware.
 * Transposition / doubled-letter refinements are applied later by [EditModel] when the small
 * candidate set is re-scored precisely.
 */
class Trie {

    private class Node {
        val children = HashMap<Char, Node>()
        var isWord = false
        var weight = 0.0 // unigram weight of the word ending here (0 for internal nodes)
    }

    private val root = Node()
    var size = 0
        private set

    /** Insert [word] with [weight]; if it already exists keep the larger weight. */
    fun insert(word: String, weight: Double) {
        if (word.isEmpty()) return
        var node = root
        for (ch in word) node = node.children.getOrPut(ch) { Node() }
        if (!node.isWord) {
            node.isWord = true
            size++
        }
        if (weight > node.weight) node.weight = weight
    }

    private fun nodeFor(s: String): Node? {
        var node = root
        for (ch in s) node = node.children[ch] ?: return null
        return node
    }

    fun contains(word: String): Boolean = nodeFor(word)?.isWord == true

    /** Unigram weight of [word], or 0.0 if it isn't in the trie. */
    fun weightOf(word: String): Double = nodeFor(word)?.takeIf { it.isWord }?.weight ?: 0.0

    /** Up to [limit] words beginning with [prefix], heaviest first (used for inline completion). */
    fun prefixMatches(prefix: String, limit: Int): List<Scored> {
        val start = nodeFor(prefix) ?: return emptyList()
        val out = ArrayList<Scored>()
        collect(start, StringBuilder(prefix), out, cap = limit * 8 + 32)
        out.sortByDescending { it.weight }
        return if (out.size > limit) out.subList(0, limit) else out
    }

    private fun collect(node: Node, sb: StringBuilder, out: MutableList<Scored>, cap: Int) {
        if (out.size >= cap) return
        if (node.isWord) out.add(Scored(sb.toString(), node.weight))
        for ((ch, child) in node.children) {
            sb.append(ch)
            collect(child, sb, out, cap)
            sb.deleteCharAt(sb.length - 1)
            if (out.size >= cap) return
        }
    }

    /**
     * Every word within weighted edit cost [budget] of [typed], reported via [onCandidate] together
     * with the generation cost. Uses an incremental Levenshtein DP row per trie node with branch
     * pruning, so cost stays close to the number of *plausible* words rather than the whole trie.
     */
    fun fuzzySearch(typed: String, budget: Double, onCandidate: (word: String, cost: Double, weight: Double) -> Unit) {
        val n = typed.length
        // Row for the empty candidate prefix: deleting/inserting the first j typed chars.
        val firstRow = DoubleArray(n + 1)
        for (j in 1..n) firstRow[j] = firstRow[j - 1] + KeyGeometry.INSERT_COST
        val sb = StringBuilder()
        for ((ch, child) in root.children) {
            sb.append(ch)
            descend(child, ch, typed, firstRow, budget, sb, onCandidate)
            sb.deleteCharAt(sb.length - 1)
        }
    }

    private fun descend(
        node: Node,
        ch: Char,
        typed: String,
        prevRow: DoubleArray,
        budget: Double,
        sb: StringBuilder,
        onCandidate: (String, Double, Double) -> Unit,
    ) {
        val n = typed.length
        val curRow = DoubleArray(n + 1)
        curRow[0] = prevRow[0] + KeyGeometry.DELETE_COST // candidate has a char the typed string lacks
        var rowMin = curRow[0]
        for (j in 1..n) {
            val sub = prevRow[j - 1] + KeyGeometry.substitutionCost(typed[j - 1], ch)
            val del = prevRow[j] + KeyGeometry.DELETE_COST
            val ins = curRow[j - 1] + KeyGeometry.INSERT_COST
            val v = minOf(sub, del, ins)
            curRow[j] = v
            if (v < rowMin) rowMin = v
        }
        if (node.isWord && curRow[n] <= budget) onCandidate(sb.toString(), curRow[n], node.weight)
        // Prune: if even the best cell already exceeds the budget, no descendant can do better.
        if (rowMin > budget) return
        for ((nextCh, child) in node.children) {
            sb.append(nextCh)
            descend(child, nextCh, typed, curRow, budget, sb, onCandidate)
            sb.deleteCharAt(sb.length - 1)
        }
    }

    data class Scored(val word: String, val weight: Double)
}
