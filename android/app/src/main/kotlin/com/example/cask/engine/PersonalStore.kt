package com.example.cask.engine

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Everything the keyboard learns about *you*, persisted on-device only (no network, no telemetry).
 * Backs all the learning behaviours:
 *
 *  - **Your frequencies & phrasing** → [confirmedFreq] / [bigramSuccessors] / [trigramSuccessors].
 *  - **New personal words, without learning your typos** → a two-stage vocabulary. A freshly committed
 *    unknown word is only *provisional* ([provisionalCount]); it does not count as a real word, does
 *    not suppress autocorrect, and never enters the suggestion trie. Only after it has been typed
 *    [PROMOTE_THRESHOLD] times — or you explicitly [addWord] it / revert a correction of it — does it
 *    become *confirmed*. This is what stops a single accidental keystroke from permanently poisoning
 *    autocorrect (the old behaviour: every committed string was learned immediately).
 *  - **Don't fight reverts** → [blocked] (corrections you undid; never auto-applied again).
 *
 * Saves are debounced onto a background thread and written atomically. Reads happen on the UI/IME
 * thread; mutations and serialization are serialised on [lock] so a save never sees a half-written map.
 */
class PersonalStore private constructor(private val file: File) {

    private val lock = Any()

    /** Confirmed vocabulary: real words you own, with usage counts. Drives the LM boost + the trie. */
    private val confirmed = HashMap<String, Int>()

    /** Provisional vocabulary: unknown words seen but not yet trusted. Promoted at [PROMOTE_THRESHOLD]. */
    private val provisional = HashMap<String, Int>()

    private val bigram = HashMap<String, HashMap<String, Int>>()
    private val trigram = HashMap<String, HashMap<String, Int>>()
    private val biTotal = HashMap<String, Int>()
    private val triTotal = HashMap<String, Int>()
    private val blocked = HashMap<String, HashSet<String>>()

    private val io = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "cask-personal-io").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    // ---- Reads (UI/IME thread) --------------------------------------------

    /** Usage count of a *confirmed* word (0 if unknown or still provisional). */
    fun confirmedFreq(word: String): Int = confirmed[word] ?: 0

    /** True once the user genuinely owns [word] (typed it enough, added it, or reverted a fix of it). */
    fun isConfirmed(word: String): Boolean = (confirmed[word] ?: 0) > 0

    /** How many times an unknown word has been seen without being promoted yet. */
    fun provisionalCount(word: String): Int = provisional[word] ?: 0

    fun biCount(c1: String, w: String): Int = bigram[c1]?.get(w) ?: 0
    fun biTotal(c1: String): Int = biTotal[c1] ?: 0
    fun triCount(c2: String, c1: String, w: String): Int = trigram[triKey(c2, c1)]?.get(w) ?: 0
    fun triTotal(c2: String, c1: String): Int = triTotal[triKey(c2, c1)] ?: 0
    fun bigramSuccessors(c1: String): Map<String, Int> = bigram[c1] ?: emptyMap()
    fun trigramSuccessors(c2: String, c1: String): Map<String, Int> = trigram[triKey(c2, c1)] ?: emptyMap()

    /** True if the user previously reverted the correction [typed] → [corrected] (so don't repeat it). */
    fun isBlocked(typed: String, corrected: String): Boolean = blocked[typed]?.contains(corrected) == true

    /** All confirmed words (so the engine can seed them into the shared trie at startup). */
    fun confirmedWords(): Map<String, Int> = confirmed

    // ---- Mutations (learning) ---------------------------------------------

    /**
     * Record that the user committed [word] in the context of the two preceding words. [isDictWord]
     * tells us whether the word is in the shipped dictionary. Returns `true` only when this commit
     * *newly* makes the word trie-worthy (an unknown word just crossed [PROMOTE_THRESHOLD]), so the
     * caller knows to insert it into the suggestion trie.
     */
    fun learn(word: String, prev1: String?, prev2: String?, isDictWord: Boolean): Boolean {
        if (word.isBlank()) return false
        var promoted = false
        synchronized(lock) {
            recordContextLocked(word, prev1, prev2)
            when {
                // Already a real word (dictionary or previously confirmed): just count usage.
                isDictWord || confirmed.containsKey(word) -> confirmed[word] = (confirmed[word] ?: 0) + 1
                // Unknown word: stage it, and promote once it has been seen enough times.
                else -> {
                    val seen = (provisional[word] ?: 0) + 1
                    if (seen >= PROMOTE_THRESHOLD) {
                        provisional.remove(word)
                        confirmed[word] = (confirmed[word] ?: 0) + seen
                        promoted = true
                    } else {
                        provisional[word] = seen
                    }
                }
            }
        }
        scheduleSave()
        return promoted
    }

    /** Advance the context model only (used when the committed word was an auto-correction we trust). */
    fun learnContext(word: String, prev1: String?, prev2: String?) {
        if (word.isBlank()) return
        synchronized(lock) { recordContextLocked(word, prev1, prev2) }
        scheduleSave()
    }

    /** Explicitly add [word] to the personal dictionary (the "+" chip): confirm it immediately. */
    fun addWord(word: String) {
        if (word.isBlank()) return
        synchronized(lock) {
            provisional.remove(word)
            confirmed[word] = maxOf(confirmed[word] ?: 0, ADDED_WORD_FREQ)
        }
        scheduleSave()
    }

    /**
     * Learn from a block of user-supplied text (the "personalise from your own writing" setting):
     * confirm the vocabulary that recurs in it and fold its word sequences into the personal
     * bi/trigram context model. Intended to run from the app process against the same on-device store
     * the keyboard reads; the keyboard picks the changes up the next time it (re)starts. Returns the
     * number of word tokens ingested.
     */
    fun ingest(text: String): Int {
        val words = Regex("[a-z']+").findAll(text.lowercase())
            .map { it.value.trim('\'') }
            .filter { it.isNotEmpty() }
            .toList()
        if (words.isEmpty()) return 0
        synchronized(lock) {
            // Confirm vocabulary that recurs in the corpus (a word the user actually writes).
            val freq = HashMap<String, Int>()
            for (w in words) freq[w] = (freq[w] ?: 0) + 1
            for ((w, c) in freq) {
                if (c < INGEST_CONFIRM_MIN || (w.length < 2 && w != "i" && w != "a")) continue
                provisional.remove(w)
                confirmed[w] = maxOf(confirmed[w] ?: 0, c)
            }
            // Fold the word sequence into the personal bigram/trigram context model.
            var p1: String? = null
            var p2: String? = null
            for (w in words) {
                recordContextLocked(w, p1, p2)
                p2 = p1
                p1 = w
            }
            prune()
        }
        flush()
        return words.size
    }

    /** Remember that auto-correcting [typed]→[corrected] was wrong, and treat [typed] as a real word. */
    fun blockCorrection(typed: String, corrected: String) {
        if (typed.isBlank()) return
        synchronized(lock) {
            blocked.getOrPut(typed) { HashSet() }.add(corrected)
            // Reverting is an explicit signal the user owns this spelling: confirm it.
            provisional.remove(typed)
            confirmed[typed] = (confirmed[typed] ?: 0) + REVERT_REINFORCE
        }
        scheduleSave()
    }

    private fun recordContextLocked(word: String, prev1: String?, prev2: String?) {
        if (prev1 != null) {
            val m = bigram.getOrPut(prev1) { HashMap() }
            m[word] = (m[word] ?: 0) + 1
            biTotal[prev1] = (biTotal[prev1] ?: 0) + 1
            if (prev2 != null) {
                val key = triKey(prev2, prev1)
                val tm = trigram.getOrPut(key) { HashMap() }
                tm[word] = (tm[word] ?: 0) + 1
                triTotal[key] = (triTotal[key] ?: 0) + 1
            }
        }
    }

    // ---- Persistence -------------------------------------------------------

    private fun scheduleSave() {
        synchronized(lock) {
            pending?.cancel(false)
            pending = io.schedule({ saveNow() }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** Force a synchronous-ish flush (used on input finish / service teardown). */
    fun flush() {
        synchronized(lock) { pending?.cancel(false) }
        io.execute { saveNow() }
    }

    private fun saveNow() {
        val json = synchronized(lock) {
            prune()
            serialize()
        }
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.writeText(json)
                tmp.delete()
            }
        }
    }

    private fun serialize(): String {
        val root = JSONObject()
        root.put("v", 2)
        root.put("uni", JSONObject(confirmed as Map<*, *>))
        root.put("prov", JSONObject(provisional as Map<*, *>))
        root.put("bi", nestedToJson(bigram))
        root.put("tri", nestedToJson(trigram))
        val blk = JSONObject()
        for ((k, set) in blocked) blk.put(k, JSONArray(set))
        root.put("blk", blk)
        return root.toString()
    }

    private fun nestedToJson(src: Map<String, HashMap<String, Int>>): JSONObject {
        val obj = JSONObject()
        for ((k, inner) in src) obj.put(k, JSONObject(inner as Map<*, *>))
        return obj
    }

    /** Keep the store bounded so it can't grow without limit on a heavy user. */
    private fun prune() {
        if (confirmed.size > MAX_UNIGRAM) {
            confirmed.entries.sortedBy { it.value }
                .take(confirmed.size - MAX_UNIGRAM)
                .forEach { confirmed.remove(it.key) }
        }
        if (provisional.size > MAX_PROVISIONAL) {
            // Provisional words are cheap to forget — drop the least-seen first.
            provisional.entries.sortedBy { it.value }
                .take(provisional.size - MAX_PROVISIONAL)
                .forEach { provisional.remove(it.key) }
        }
        if (bigram.size > MAX_CONTEXTS) trimContexts(bigram, biTotal)
        if (trigram.size > MAX_CONTEXTS) trimContexts(trigram, triTotal)
    }

    private fun trimContexts(map: HashMap<String, HashMap<String, Int>>, totals: HashMap<String, Int>) {
        val drop = map.keys.sortedBy { totals[it] ?: 0 }.take(map.size - MAX_CONTEXTS)
        for (k in drop) { map.remove(k); totals.remove(k) }
    }

    private fun triKey(c2: String, c1: String) = c2 + SEP + c1

    companion object {
        private const val SEP = "\t" // trigram context-key separator (never appears inside a word)
        private const val FILE_NAME = "cask_personal.json"
        private const val SAVE_DEBOUNCE_MS = 1500L
        private const val PROMOTE_THRESHOLD = 3   // times an unknown word must be typed before it's "yours"
        private const val INGEST_CONFIRM_MIN = 2  // times a word must recur in an imported corpus to confirm it
        private const val ADDED_WORD_FREQ = 5     // starting weight for a word added via the "+" chip
        private const val REVERT_REINFORCE = 3
        private const val MAX_UNIGRAM = 30_000
        private const val MAX_PROVISIONAL = 4_000
        private const val MAX_CONTEXTS = 40_000

        fun load(context: Context): PersonalStore {
            val file = File(context.filesDir, FILE_NAME)
            val store = PersonalStore(file)
            runCatching {
                if (file.exists()) store.deserialize(JSONObject(file.readText()))
            }
            return store
        }
    }

    private fun deserialize(root: JSONObject) {
        // "uni" historically held all learned words; load it as the confirmed vocabulary.
        root.optJSONObject("uni")?.let { obj ->
            for (k in obj.keys()) confirmed[k] = obj.optInt(k)
        }
        root.optJSONObject("prov")?.let { obj ->
            for (k in obj.keys()) provisional[k] = obj.optInt(k)
        }
        root.optJSONObject("bi")?.let { obj ->
            for (c1 in obj.keys()) {
                val inner = obj.optJSONObject(c1) ?: continue
                val m = HashMap<String, Int>()
                var total = 0
                for (w in inner.keys()) { val c = inner.optInt(w); m[w] = c; total += c }
                bigram[c1] = m; biTotal[c1] = total
            }
        }
        root.optJSONObject("tri")?.let { obj ->
            for (key in obj.keys()) {
                val inner = obj.optJSONObject(key) ?: continue
                val m = HashMap<String, Int>()
                var total = 0
                for (w in inner.keys()) { val c = inner.optInt(w); m[w] = c; total += c }
                trigram[key] = m; triTotal[key] = total
            }
        }
        root.optJSONObject("blk")?.let { obj ->
            for (k in obj.keys()) {
                val arr = obj.optJSONArray(k) ?: continue
                val set = HashSet<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                blocked[k] = set
            }
        }
    }
}
