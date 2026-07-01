package com.example.cask.engine

import android.content.Context
import android.graphics.PointF
import android.util.Log

/**
 * The brain the IME talks to. It owns the current word being composed and the recent word context,
 * and turns them into (a) the suggestion-strip contents and (b) an auto-correct decision at each word
 * boundary, using the noisy-channel model:
 *
 * ```
 * score(candidate) = log P(typed | candidate)        // EditModel  (spatial error model)
 *                  + log P(candidate | context)       // LanguageModel (n-gram + personal)
 *                  + neural adjustment                // Rescorer (optional TFLite LM)
 * ```
 *
 * On top of the statistical model sit three things that make the difference between "okay" and "good":
 *
 *  1. **[CommonCorrections]** — a curated table that fixes the misspellings/contractions the shipped
 *     web-frequency dictionary wrongly considers valid words (`im`→`I'm`, `teh`→`the`, `recieve`→…).
 *  2. **A dominant-neighbour override** — even a dictionary word is corrected when an overwhelmingly
 *     more common single-edit neighbour exists (`thier`→`their`), gated hard to protect real words.
 *  3. **Staged learning** — a freshly typed unknown word is *provisional* until repeated, so a one-off
 *     typo never gets learned, never suppresses autocorrect, and never pollutes suggestions.
 *
 * The IME ([com.example.cask.CaskKeyboardService]) feeds it characters and boundaries and applies the
 * returned decisions to the text field; all the linguistics live here. Everything is on-device.
 */
class CorrectionEngine private constructor(
    private val dict: Dictionary,
    private val store: PersonalStore,
    private val lm: LanguageModel,
    private val rescorer: Rescorer,
) {

    /** The just-applied auto-correction, so the IME can offer an immediate one-press revert. */
    data class CommitDecision(
        val original: String,    // exactly what the user typed (with case)
        val output: String,      // what should be committed (with case)
        val autoCorrected: Boolean,
    )

    /**
     * What the suggestion strip should show while composing. [chips] are the ready-to-display words
     * (already re-cased). When [addWord] is true the typed word is unrecognised — not in the
     * dictionary and not close to anything — so the last chip becomes a "+" that adds it to the
     * personal dictionary.
     */
    data class Strip(val chips: List<String>, val addWord: Boolean)

    private class Cand(val word: String, val cost: Double, val score: Double)

    private val composing = StringBuilder()
    // Per-character tap position (in KeyGeometry key-units), parallel to [composing]; null where a
    // character arrived without a coordinate (apostrophe, paste, gesture). The spatial error model is
    // used only when every position has one (see [touchPath]); otherwise scoring falls back to discrete.
    private val touchPts = ArrayList<PointF?>()
    private var prev1: String? = null
    private var prev2: String? = null
    private val history = ArrayDeque<String>() // recent committed words (lowercase) for neural context
    private val gestureDecoder = GestureDecoder(dict, lm)

    val neuralActive: Boolean get() = rescorer.isReady

    // ---- Composing buffer (driven per keystroke) ---------------------------

    fun hasComposing(): Boolean = composing.isNotEmpty()
    fun composingRaw(): String = composing.toString()
    private fun composingLower(): String = composing.toString().lowercase()

    fun appendComposing(ch: Char, touch: PointF? = null) {
        composing.append(ch)
        touchPts.add(touch)
    }

    /** Replace the whole composing word at once (used to seed a glide/swipe-decoded word). */
    fun setComposing(word: String) {
        composing.setLength(0)
        composing.append(word)
        touchPts.clear() // a gesture-decoded word has no per-key taps -> discrete scoring
    }

    /** Remove one composing char; true if something was removed (else the IME deletes normally). */
    fun deleteComposing(): Boolean {
        if (composing.isEmpty()) return false
        composing.deleteCharAt(composing.length - 1)
        if (touchPts.isNotEmpty()) touchPts.removeAt(touchPts.size - 1)
        return true
    }

    fun clearComposing() { composing.setLength(0); touchPts.clear() }

    /** The per-character tap path iff every composing character has a coordinate; else null (fall back). */
    private fun touchPath(): List<PointF>? {
        if (touchPts.isEmpty() || touchPts.size != composing.length) return null
        val out = ArrayList<PointF>(touchPts.size)
        for (p in touchPts) out.add(p ?: return null)
        return out
    }

    // ---- Context lifecycle -------------------------------------------------

    /** Re-seed context from the text already before the cursor (field focus / cursor move). */
    fun resetContext(textBefore: CharSequence?) {
        composing.setLength(0)
        history.clear()
        prev1 = null
        prev2 = null
        val words = textBefore?.toString()
            ?.split(Regex("[^\\p{L}']+"))
            ?.filter { it.isNotBlank() }
            ?.map { it.lowercase() }
            ?: emptyList()
        words.takeLast(HISTORY).forEach { history.addLast(it) }
        prev1 = words.getOrNull(words.size - 1)
        prev2 = words.getOrNull(words.size - 2)
    }

    // ---- Suggestion strip --------------------------------------------------

    /**
     * Strip contents while a word is being composed. Layout: a top suggestion (the correction the
     * engine favours, or the user's word if it's already good), the user's exact word (always keepable),
     * and a third slot that is either the next-best alternative or — when the word is completely
     * unrecognised — a "+" add-to-dictionary affordance.
     */
    fun composingStrip(): Strip {
        val raw = composingRaw()
        val typed = composingLower()
        if (typed.isEmpty()) return Strip(emptyList(), addWord = false)

        // Curated correction: surface it in the strip. For an auto fix the correction leads (it's what
        // a boundary would commit); for an ambiguous one the typed word leads (it's likely what's meant).
        val fix = CommonCorrections.lookup(typed)
        if (fix != null && !store.isConfirmed(typed) && !store.isBlocked(typed, fix.to.lowercase())) {
            val corrected = recaseCurated(raw, fix.to)
            val mine = raw.ifEmpty { typed }
            val chips = ArrayList<String>(3)
            if (fix.auto) { chips.add(corrected); chips.add(mine) } else { chips.add(mine); chips.add(corrected) }
            for (m in dict.trie.prefixMatches(typed, COMPLETION_K)) {
                if (chips.size >= 3) break
                if (m.word == typed) continue
                val d = recase(raw, m.word)
                if (chips.none { it.equals(d, ignoreCase = true) }) chips.add(d)
            }
            return Strip(chips.distinctBy { it.lowercase() }.take(3), addWord = false)
        }

        // Unified candidate pool: corrections (edit-distance) + completions (prefix), best score wins.
        val pool = LinkedHashMap<String, Double>()
        val keepMax = { a: Double, b: Double -> if (a >= b) a else b }
        for (c in corrections(typed)) pool.merge(c.word, c.score, keepMax)
        for (c in completions(typed)) pool.merge(c.word, c.score, keepMax)
        val ranked = pool.entries.sortedByDescending { it.value }.map { it.key }

        // Is the typed word completely unrecognised? -> offer to add it (the "+" chip).
        val typedKnown = dict.isKnown(typed) || store.isConfirmed(typed)
        val hasCloseAlt = ranked.any { it != typed && dict.isKnown(it) && EditModel.cost(typed, it) <= ADD_WORD_MAX_DIST }
        val hasCompletion = ranked.any { it != typed && it.length > typed.length && it.startsWith(typed) && dict.isKnown(it) }
        if (isAlphabetic(typed) && typed.length >= 2 && !typedKnown && !hasCloseAlt && !hasCompletion) {
            return Strip(listOf(raw.ifEmpty { typed }), addWord = true)
        }

        // Normal layout: [best alternative], [your word], [next alternative].
        val ordered = ArrayList<String>(3)
        ranked.firstOrNull()?.let { if (it != typed) ordered.add(it) }
        if (typed !in ordered) ordered.add(typed)
        for (w in ranked) {
            if (ordered.size >= 3) break
            if (w !in ordered) ordered.add(w)
        }
        return Strip(ordered.take(3).map { displayFor(it, raw, typed) }, addWord = false)
    }

    /** Strip contents when idle (no word in progress): next-word predictions. */
    fun idleSuggestions(capitalize: Boolean): List<String> {
        // At a sentence start the last committed word sits on the other side of the boundary, so its
        // successors are the wrong signal — predict sentence starters instead.
        val preds = if (capitalize) lm.predictNext(null, null, 3) else lm.predictNext(prev1, prev2, 3)
        return preds.map { if (capitalize) it.replaceFirstChar(Char::uppercase) else it }
    }

    /** Explicitly add the word currently being composed to the personal dictionary (the "+" chip). */
    fun addCurrentWordToDictionary(): String? {
        val raw = composingRaw()
        val w = raw.lowercase()
        if (w.isEmpty() || !isAlphabetic(w)) return null
        store.addWord(w)
        dict.trie.insert(w, PERSONAL_TRIE_WEIGHT.toDouble() * store.confirmedFreq(w))
        return raw
    }

    // ---- Glide / swipe typing ----------------------------------------------

    /**
     * Decode a glide/swipe gesture into ranked candidate words (most likely first), using the live
     * letter-key [centers] and [keyRadius] plus the current word context. Best first; commit it and
     * offer the rest as suggestions.
     */
    fun gestureCandidates(points: List<PointF>, centers: Map<Char, PointF>, keyRadius: Float): List<String> =
        gestureDecoder.decode(points, centers, keyRadius, prev1, prev2)

    // ---- Commit decisions --------------------------------------------------

    /** Decide what to commit for the current word at a boundary, then learn it and advance context. */
    fun commitWord(): CommitDecision? {
        if (composing.isEmpty()) return null
        val raw = composing.toString()
        val typed = raw.lowercase()
        val p1 = prev1
        val p2 = prev2

        var output = raw
        var auto = false
        var corrected = false
        var contextToken = typed

        when {
            // Standalone "i" -> "I" (treated as the user's own word, not a flagged auto-correction).
            typed == "i" -> { output = "I"; contextToken = "i" }
            else -> {
                val fix = CommonCorrections.lookup(typed)
                if (fix != null && fix.auto && !store.isConfirmed(typed) && !store.isBlocked(typed, fix.to.lowercase())) {
                    output = recaseCurated(raw, fix.to)
                    auto = true; corrected = true; contextToken = contextTokenOf(fix.to)
                } else if (fix == null && isAlphabetic(typed) && typed.length >= MIN_AUTO_LEN && !hasProtectedCase(raw)) {
                    // No curated entry: fall back to the statistical model. A *suggest* fix (fix != null,
                    // not auto) is deliberately ambiguous, so we offer it in the strip but never force it.
                    val ranked = corrections(typed)
                    val best = ranked.firstOrNull { it.word != typed }
                    // The typed word's own score the same way candidates are scored (incl. context/neural),
                    // so the auto-apply margin is an apples-to-apples comparison.
                    val typedScore = ranked.firstOrNull { it.word == typed }?.score
                        ?: (EditModel.logProb(typed, typed) + lm.contextLogProb(typed, prev1, prev2))
                    if (DEBUG_AC) Log.d(
                        "CaskAC",
                        "typed='$typed' known=${dict.isKnown(typed)} conf=${store.isConfirmed(typed)} " +
                            "ctx=[$p2,$p1] best=${best?.word} cost=${best?.cost} score=${best?.score} " +
                            "typedScore=$typedScore margin=${best?.let { it.score - typedScore }}",
                    )
                    val applySingle = best != null && shouldAutoApply(typed, best, typedScore)
                    val singleScore = if (applySingle) best!!.score else Double.NEGATIVE_INFINITY
                    // Missing-space split for a non-word ("inthe"->"in the", "thankyou"->"thank you"):
                    // both halves must be real words and the pair must out-score any single-word fix.
                    val split = if (!dict.isKnown(typed) && !store.isConfirmed(typed)) bestSplit(typed) else null
                    if (split != null && split.score > singleScore && split.score > typedScore + SPLIT_MARGIN) {
                        output = recaseSplit(raw, split.left, split.right)
                        auto = true; corrected = true; contextToken = split.right
                    } else if (applySingle) {
                        output = recase(raw, best!!.word)
                        auto = true; corrected = true; contextToken = best.word
                    }
                }
            }
        }

        if (DEBUG_AC) Log.d("CaskAC", "commit raw='$raw' -> '$output' auto=$auto")
        learn(contextToken, p1, p2, isCorrection = corrected)
        advanceContext(contextToken, p1)
        composing.setLength(0)
        touchPts.clear()
        return CommitDecision(raw, output, auto)
    }

    /** The user tapped a suggestion chip: commit [picked] for the current word and learn it. */
    fun pickSuggestion(picked: String): CommitDecision {
        val raw = composing.toString()
        val token = contextTokenOf(picked)
        // A multi-word or punctuated pick (e.g. "a lot", "I'm") only advances context; a plain word is
        // learned as a normal commit (and staged toward the personal dictionary if it's unknown).
        learn(token, prev1, prev2, isCorrection = !isAlphabetic(token))
        advanceContext(token, prev1)
        composing.setLength(0)
        touchPts.clear()
        return CommitDecision(raw, picked, autoCorrected = false)
    }

    /**
     * A boundary character was committed after the word. A sentence ender (or newline) closes the
     * sentence: the words before it are the wrong context for what comes next, so drop them (the
     * n-gram models were trained per sentence too). Commas/quotes keep the context flowing.
     */
    fun noteTerminator(terminator: CharSequence) {
        if (terminator.any { it in SENTENCE_END || it == '\n' }) {
            prev1 = null
            prev2 = null
        }
    }

    /** The user reverted an auto-correction: stop making it, and treat their spelling as intended. */
    fun noteRevert(original: String, corrected: String) {
        val o = original.lowercase()
        val c = corrected.lowercase()
        store.blockCorrection(o, c)
        // Repair context: the committed word is now the original, not the correction.
        if (prev1 == c) prev1 = o
        if (history.lastOrNull() == c) { history.removeLast(); history.addLast(o) }
        if (isAlphabetic(o)) dict.trie.insert(o, PERSONAL_TRIE_WEIGHT.toDouble())
    }

    /** Apply learning for a committed [token]. Corrections only feed the context model; plain commits
     * also stage the word toward the personal vocabulary (and enter the trie once promoted). */
    private fun learn(token: String, p1: String?, p2: String?, isCorrection: Boolean) {
        if (token.isBlank()) return
        if (isCorrection || !isAlphabetic(token)) {
            store.learnContext(token, p1, p2)
        } else {
            // Only *high-frequency* dictionary words are trusted as "a word you own" on first use.
            // The shipped web-frequency list is full of junk entries (`yoi`, `baf`, `teh`, …) that are
            // technically "known"; confirming them on a single commit would protect them from ever
            // being auto-corrected (shouldAutoApply + the curated path both bail on isConfirmed). Stage
            // low-frequency dictionary words like unknowns so a one-off typo can't shield itself.
            val trustworthy = dict.isKnown(token) && dict.baseCount(token) >= TRUST_FREQ
            val promoted = store.learn(token, p1, p2, trustworthy)
            if (promoted) dict.trie.insert(token, PERSONAL_TRIE_WEIGHT.toDouble() * store.confirmedFreq(token))
        }
    }

    private fun advanceContext(token: String, p1: String?) {
        prev2 = p1
        prev1 = token
        history.addLast(token)
        while (history.size > HISTORY) history.removeFirst()
    }

    // ---- Whole-text fixing (the tools-row "Fix" action) --------------------

    /** Run the whole-text Fix pass (spelling + spacing + capitalization) over [text]. All local. */
    fun fixText(text: String): String =
        TextFixer.fix(text) { raw, p1, p2, allowStatistical -> fixWord(raw, p1, p2, allowStatistical) }

    /**
     * Stateless single-word fix used by [fixText]: the corrected form of [raw] given the two
     * preceding words, or null to leave it alone. Same policy as a boundary commit (curated table,
     * then the statistical noisy-channel model, then the missing-space split) but with no learning
     * and no composing state, so already-written text can be re-checked safely. When
     * [allowStatistical] is false (likely proper noun) only curated corrections may fire.
     */
    fun fixWord(raw: String, p1: String?, p2: String?, allowStatistical: Boolean = true): String? {
        val typed = raw.lowercase()
        if (typed.isEmpty()) return null
        if (typed == "i") return if (raw == "I") null else "I"
        val fix = CommonCorrections.lookup(typed)
        if (fix != null) {
            if (!fix.auto || store.isConfirmed(typed) || store.isBlocked(typed, fix.to.lowercase())) return null
            return recaseCurated(raw, fix.to).takeIf { it != raw }
        }
        if (!allowStatistical) return null
        if (!isAlphabetic(typed) || typed.length < MIN_AUTO_LEN || hasProtectedCase(raw)) return null
        // Fast path: words the auto-apply policy could never touch (owned, protected, or too common)
        // skip the candidate search entirely, so fixing a long text stays quick.
        if (store.isConfirmed(typed) || typed in PROTECTED_WORDS) return null
        if (dict.isKnown(typed) && dict.baseCount(typed) > TYPED_FREQ_CEILING) return null

        val ranked = corrections(typed, p1, p2, touch = null, hist = emptyList())
        val best = ranked.firstOrNull { it.word != typed }
        val typedScore = ranked.firstOrNull { it.word == typed }?.score
            ?: (EditModel.logProb(typed, typed) + lm.contextLogProb(typed, p1, p2))
        val applySingle = best != null && shouldAutoApply(typed, best, typedScore)
        val singleScore = if (applySingle) best!!.score else Double.NEGATIVE_INFINITY
        val split = if (!dict.isKnown(typed) && !store.isConfirmed(typed)) bestSplit(typed, p1, p2) else null
        return when {
            split != null && split.score > singleScore && split.score > typedScore + SPLIT_MARGIN ->
                recaseSplit(raw, split.left, split.right)
            applySingle -> recase(raw, best!!.word).takeIf { it != raw }
            else -> null
        }
    }

    // ---- Auto-capitalization ----------------------------------------------

    /** Whether the next typed letter should be capitalized (sentence start). */
    fun shouldCapitalizeNext(textBefore: CharSequence?): Boolean {
        val s = textBefore?.toString()?.trimEnd() ?: return true
        if (s.isEmpty()) return true
        return s.last() in SENTENCE_END
    }

    fun flush() = store.flush()

    // ---- Candidate generation & scoring -----------------------------------

    private class Split(val left: String, val right: String, val score: Double)

    /**
     * The most likely way to read [typed] as two run-together real words (a dropped space), or null.
     * Both halves must be in the dictionary; the pair is scored by the language model — `P(left|ctx)` +
     * `P(right|left)` — plus a fixed missing-space penalty so it competes fairly with a single-word fix.
     */
    private fun bestSplit(typed: String): Split? = bestSplit(typed, prev1, prev2)

    private fun bestSplit(typed: String, p1: String?, p2: String?): Split? {
        if (typed.length < SPLIT_MIN_LEN) return null
        var best: Split? = null
        for (i in 1 until typed.length) {
            val l = typed.substring(0, i)
            val r = typed.substring(i)
            if (!okSplitPart(l) || !okSplitPart(r)) continue
            if (!dict.isKnown(l) || !dict.isKnown(r)) continue
            val score = lm.contextLogProb(l, p1, p2) + lm.contextLogProb(r, l, p1) + SPLIT_PENALTY
            if (best == null || score > best.score) best = Split(l, r, score)
        }
        return best
    }

    /** A split half is acceptable if it's a multi-letter word, or one of the only sensible single letters. */
    private fun okSplitPart(p: String): Boolean = p.length >= 2 || p == "a" || p == "i"

    private fun corrections(typed: String): List<Cand> =
        corrections(typed, prev1, prev2, touchPath(), history.toList())

    private fun corrections(
        typed: String, p1: String?, p2: String?, touch: List<PointF>?, hist: List<String>,
    ): List<Cand> {
        val byCost = HashMap<String, Double>()
        dict.trie.fuzzySearch(typed, EditModel.budgetFor(typed)) { word, cost, _ ->
            val prev = byCost[word]
            if (prev == null || cost < prev) byCost[word] = cost
        }
        byCost.putIfAbsent(typed, 0.0)
        // Keep the cheapest candidates only, then score precisely. The error term is the spatial touch
        // model when every tap position is known (GBoard-style: decode by where the finger landed),
        // else the discrete proximity model. The discrete `cost` is still used for the auto-apply
        // distance ceilings in shouldAutoApply.
        val words = byCost.entries.sortedBy { it.value }.take(MAX_CANDIDATES).map { it.key }
        val neural = rescorer.rescore(hist, words)
        return words.mapIndexed { i, w ->
            val errLog = if (touch != null) EditModel.spatialLogProb(touch, w) else EditModel.logProb(typed, w)
            val score = errLog + firstCharPenalty(typed, w) + lm.contextLogProb(w, p1, p2) + neural[i]
            Cand(w, EditModel.cost(typed, w), score)
        }.sortedByDescending { it.score }
    }

    /**
     * People almost never miss the *first* key of a word, so candidates that change it are less
     * likely than the raw edit distance suggests. Penalise a first-letter mismatch unless it's
     * plausibly a slip (physical neighbours) or a swap of the first two letters ("hte" → "the").
     */
    private fun firstCharPenalty(typed: String, cand: String): Double {
        if (typed.isEmpty() || cand.isEmpty() || typed[0] == cand[0]) return 0.0
        if (KeyGeometry.areNeighbors(typed[0], cand[0])) return 0.0
        if (typed.length >= 2 && cand.length >= 2 && typed[0] == cand[1] && typed[1] == cand[0]) return 0.0
        return FIRST_CHAR_PENALTY
    }

    private fun completions(typed: String): List<Cand> {
        if (typed.length < MIN_COMPLETION_PREFIX) return emptyList()
        val matches = dict.trie.prefixMatches(typed, COMPLETION_K).filter { it.word != typed }
        if (matches.isEmpty()) return emptyList()
        val words = matches.map { it.word }
        val neural = rescorer.rescore(history.toList(), words)
        return matches.mapIndexed { i, m ->
            val incompletePenalty = COMPLETION_PENALTY * (m.word.length - typed.length)
            val score = incompletePenalty + lm.contextLogProb(m.word, prev1, prev2) + neural[i]
            Cand(m.word, 0.0, score)
        }.sortedByDescending { it.score }
    }

    /**
     * Hybrid auto-correct policy, now driven by the full noisy-channel score (spatial **and** context,
     * since a real English bigram prior ships — see [LanguageModel]). We never fight a word the user
     * owns (confirmed) or has reverted, and we only ever correct *to* a real word.
     *
     *  - **Unknown typed word** (not a real word): correct aggressively — whenever a reachable real word
     *    clearly out-scores it. The cost ceiling is generous (covers two-ish edits) because changing a
     *    non-word is low-risk and instantly revertable; very short strings need much stronger evidence
     *    (the edit model already makes short-word edits expensive, which protects `ok`/`hi`/`ur`).
     *  - **Dictionary typed word** (a real word, possibly a typo the web corpus accepts): protected.
     *    We leave it alone if it's common ([TYPED_FREQ_CEILING]) or explicitly [PROTECTED_WORDS], and
     *    otherwise correct only when a single cheap edit yields a strongly better word *or* an
     *    overwhelmingly more frequent neighbour exists ([dominantNeighbor]).
     */
    private fun shouldAutoApply(typed: String, best: Cand, typedScore: Double): Boolean {
        if (store.isBlocked(typed, best.word)) return false
        if (store.isConfirmed(typed)) return false
        // Only ever correct *to* a real word — a shipped-dictionary word or one you've confirmed
        // (provisional/half-learned typos are never in the trie, so they can't be a target).
        if (!dict.isKnown(best.word) && !store.isConfirmed(best.word)) return false
        val margin = best.score - typedScore
        return if (!dict.isKnown(typed)) {
            best.cost <= UNKNOWN_COST_CEIL &&
                margin >= (if (typed.length <= 2) UNKNOWN_MARGIN_SHORT else UNKNOWN_MARGIN)
        } else {
            if (typed in PROTECTED_WORDS) return false
            if (dict.baseCount(typed) > TYPED_FREQ_CEILING) return false
            (best.cost <= DICT_COST_CEIL && margin >= DICT_MARGIN) || dominantNeighbor(typed, best)
        }
    }

    /**
     * A dictionary word is auto-corrected only when an overwhelmingly more common neighbour is a single
     * cheap edit away — e.g. `thier`→`their`, `freind`→`friend`. The length floor, tiny-edit ceiling,
     * dominance ratio and typed-frequency ceiling together keep this from ever touching genuinely
     * common words (`form`/`from`, `were`/`where`, `well`), which lack such a lopsided neighbour.
     */
    private fun dominantNeighbor(typed: String, best: Cand): Boolean {
        if (typed.length < GENERIC_OVERRIDE_MIN_LEN) return false
        if (best.cost > STRONG_EDIT_COST) return false
        if (!dict.isKnown(best.word)) return false
        val tc = dict.baseCount(typed)
        val bc = dict.baseCount(best.word)
        if (tc <= 0.0 || tc > TYPED_FREQ_CEILING) return false
        return bc >= DOMINANCE_RATIO * tc
    }

    // ---- Helpers -----------------------------------------------------------

    private fun isAlphabetic(s: String): Boolean = s.isNotEmpty() && s.all { it.isLetter() }

    /** Intentional non-leading capitals (acronyms like USA, CamelCase like iPhone) ⇒ leave it alone. */
    private fun hasProtectedCase(raw: String): Boolean {
        for (i in 1 until raw.length) if (raw[i].isUpperCase()) return true
        return false
    }

    /** The single lowercase token to advance context with for a (possibly multi-word) committed string. */
    private fun contextTokenOf(s: String): String = s.lowercase().substringAfterLast(' ')

    /** Display form for a strip chip: the user's exact text for their own word, else a re-cased candidate. */
    private fun displayFor(word: String, raw: String, typed: String): String =
        if (word == typed) raw.ifEmpty { typed } else recase(raw, word)

    /** Re-case a two-word split: the leading word follows the typed pattern; the second stays lower
     *  unless the whole typed string was UPPER (so `inthe`→`in the`, `Inthe`→`In the`, `INTHE`→`IN THE`). */
    private fun recaseSplit(template: String, left: String, right: String): String {
        val allCaps = template.length > 1 && template.all { it.isUpperCase() }
        return recase(template, left) + " " + if (allCaps) right.uppercase() else right
    }

    /** Apply [template]'s capitalization pattern (Title / UPPER / lower) to lowercase [word]. */
    private fun recase(template: String, word: String): String {
        if (template.isEmpty()) return word
        val allCaps = template.length > 1 && template.all { it.isUpperCase() }
        return when {
            allCaps -> word.uppercase()
            template[0].isUpperCase() -> word.replaceFirstChar(Char::uppercase)
            else -> word
        }
    }

    /**
     * Re-case a curated correction, which already carries its own canonical capitalization/apostrophes
     * (`I'm`, `don't`, `a lot`). We only lift the first letter to match a capitalized/te-shifted typed
     * word; the canonical form is otherwise preserved (so `dont`→`don't`, `Dont`→`Don't`, `im`→`I'm`).
     */
    private fun recaseCurated(template: String, canonical: String): String {
        if (template.isEmpty()) return canonical
        val allCaps = template.length > 1 && template.all { it.isUpperCase() }
        return when {
            allCaps -> canonical.uppercase()
            template[0].isUpperCase() && canonical.isNotEmpty() && canonical[0].isLowerCase() ->
                canonical.replaceFirstChar(Char::uppercase)
            else -> canonical
        }
    }

    companion object {
        private const val HISTORY = 8
        private const val MIN_AUTO_LEN = 2
        private const val MIN_COMPLETION_PREFIX = 2
        private const val COMPLETION_K = 8
        private const val MAX_CANDIDATES = 64

        // Missing-space split ("inthe" -> "in the"). Only attempted on non-words >= this long; the
        // penalty stands in for the cost of the dropped space, and the result must beat any single-word
        // correction and clear this margin over leaving the typed string alone.
        private const val SPLIT_MIN_LEN = 4
        private const val SPLIT_PENALTY = -2.5
        private const val SPLIT_MARGIN = 1.0
        private const val COMPLETION_PENALTY = -0.18
        private const val ADD_WORD_MAX_DIST = 2.0   // a real word this close means the typed word *is* recognised

        // Auto-correct of an *unknown* (non-word) typed string: aggressive, since it's low-risk + revertable.
        private const val UNKNOWN_COST_CEIL = 3.4    // up to ~two edits (matches the trie generation budget)
        private const val UNKNOWN_MARGIN = 0.1       // candidate must out-score the typed string by this (nats)
        private const val UNKNOWN_MARGIN_SHORT = 4.0 // ≤2-letter strings need much stronger evidence

        // Auto-correct of a *dictionary* word (real but maybe a typo): protected, needs strong evidence.
        // Tuned aggressive — a real-word typo (thier/freind/seperate) should flip on its own; rare false
        // positives are one backspace away from undo (PendingRevert / noteRevert).
        private const val DICT_COST_CEIL = 1.8       // a slightly larger single edit (transpose/double/neighbour/indel)
        private const val DICT_MARGIN = 2.0          // and a clearly better score given spatial + context

        // A committed dictionary word is only "yours" (confirmed, hence protected from correction) on
        // first use when it's this frequent; rarer known words are staged like unknowns so junk
        // web-corpus entries can't shield themselves after a single accidental commit.
        private const val TRUST_FREQ = 5_000_000.0

        // Log each commit decision to logcat (`adb logcat -s CaskAC`) to diagnose why a word is /
        // isn't auto-corrected on-device. Off for normal use.
        private const val DEBUG_AC = false

        // Applied when a candidate changes the typed word's first letter (see [firstCharPenalty]):
        // first-key misses are rare, so such candidates need clearly better evidence elsewhere.
        private const val FIRST_CHAR_PENALTY = -0.9

        // Dominant-neighbour override for dictionary words (e.g. thier -> their), a frequency-only fallback.
        private const val GENERIC_OVERRIDE_MIN_LEN = 3
        private const val STRONG_EDIT_COST = 1.4         // one cheap edit (transpose/double/neighbour/indel)
        private const val DOMINANCE_RATIO = 8.0          // neighbour must be >= 8x more frequent
        private const val TYPED_FREQ_CEILING = 15_000_000.0 // and the typed word itself must be uncommon

        private const val PERSONAL_TRIE_WEIGHT = 1500

        private val SENTENCE_END = setOf('.', '!', '?')

        /**
         * Real words we never auto-correct *away* even when below [TYPED_FREQ_CEILING]: ultra-common
         * short words, dangerous near-pairs, and informal staples. (Words above the ceiling are already
         * protected by frequency; this guards the low-frequency stragglers.)
         */
        private val PROTECTED_WORDS: Set<String> = hashSetOf(
            "a", "i", "an", "as", "at", "be", "by", "do", "go", "he", "hi", "if", "in", "is", "it",
            "me", "my", "no", "of", "ok", "on", "or", "so", "to", "up", "us", "we", "ya", "yo",
            "am", "id", "ill", "im", "ive", "oh", "ad", "ed", "ex", "pm", "re", "un", "vs",
            "off", "our", "out", "own", "the", "and", "for", "but", "not", "now", "new", "one",
            "form", "from", "were", "where", "well", "than", "then", "want", "wont", "won", "way",
            "ok", "okay", "yeah", "yea", "yep", "yup", "nah", "nope", "lol", "omg", "idk", "btw",
            "tbh", "imo", "u", "ur", "gonna", "wanna", "gotta", "kinda", "dunno", "hey", "hmm",
        )

        fun load(context: Context): CorrectionEngine {
            val dict = Dictionary.load(context)
            val store = PersonalStore.load(context)
            // Seed confirmed personal words into the shared trie so they show up in completion/correction.
            for ((w, count) in store.confirmedWords()) {
                if (w.isNotEmpty() && w.all { it.isLetter() }) {
                    dict.trie.insert(w, PERSONAL_TRIE_WEIGHT.toDouble() * count)
                }
            }
            val rescorer = NeuralRescorer.tryLoad(context)
            // Shipped English bigram prior (restricted to the loaded vocab so successors are surfaceable).
            val ship = NgramModel.load(context) { dict.isKnown(it) }
            val lm = LanguageModel(dict, store, ship)
            return CorrectionEngine(dict, store, lm, rescorer)
        }
    }
}
