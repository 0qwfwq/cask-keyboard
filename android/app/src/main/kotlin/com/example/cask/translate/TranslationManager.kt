package com.example.cask.translate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.Locale

/**
 * Thin wrapper around ML Kit's on-device translator — the same offline neural-MT models Google
 * Translate uses for its offline mode, so the output is natural/native rather than word-for-word.
 *
 * A model for a given source→target pair downloads once (then everything is offline). We keep a
 * single live [Translator] for the current pair and rebuild it when either language changes. All work
 * is on-device: no API key, no network after the one-time download, in keeping with the rest of the
 * keyboard.
 */
class TranslationManager {

    data class Lang(val code: String, val name: String)

    /** The lifecycle of a single translation request, delivered to the UI on the main thread. */
    sealed interface State {
        /** Translation finished; [text] is the natural-language result (empty for blank input). */
        data class Done(val text: String) : State
        /** The language model for this pair is being fetched (first use of the pair). */
        object Downloading : State
        /** The request failed (download or translate); [message] is user-facing. */
        data class Failed(val message: String) : State
    }

    var sourceLang: String = defaultSource(); private set
    var targetLang: String = defaultTarget(); private set

    private var translator: Translator? = null
    private var builtFor: Pair<String, String>? = null
    private val downloaded = HashSet<Pair<String, String>>()

    // A second translator for the reverse direction (target→source), used only by the word-alignment
    // colouring so it can also translate output words *back* and match them to the input. Reuses the
    // same already-downloaded language models, so it needs no extra download.
    private var reverseTranslator: Translator? = null
    private var reverseBuiltFor: Pair<String, String>? = null

    // Single-word translations for the word-alignment colouring, keyed by lowercased word. Forward is
    // source→target, reverse is target→source. Both only ever hold current-pair results (cleared on a
    // language change).
    private val wordCache = LinkedHashMap<String, String>()
    private val reverseWordCache = LinkedHashMap<String, String>()

    // Bumped on every language change. Async ML Kit callbacks capture the epoch they were issued
    // under and bail if it has since moved on, so a result for an old language pair (or one whose
    // translator we've already closed) can never fire into the UI — or call translate() on a closed
    // client, which used to crash the keyboard when you switched the language mid-download.
    private var epoch = 0

    /** Every language ML Kit can translate, as (code, native-ish display name), sorted by name. */
    val languages: List<Lang> by lazy {
        TranslateLanguage.getAllLanguages()
            .map { Lang(it, displayName(it)) }
            .sortedBy { it.name }
    }

    fun displayName(code: String): String =
        Locale.forLanguageTag(code).displayName.replaceFirstChar { it.uppercase() }

    /** Short label for the round language buttons, e.g. "EN", "ES". */
    fun shortLabel(code: String): String = code.uppercase()

    fun setSource(code: String) {
        if (code != sourceLang) { sourceLang = code; invalidate() }
    }

    fun setTarget(code: String) {
        if (code != targetLang) { targetLang = code; invalidate() }
    }

    /** Swap the two languages (handy after picking, not yet surfaced in the UI). */
    fun swap() {
        val s = sourceLang; sourceLang = targetLang; targetLang = s; invalidate()
    }

    private fun invalidate() {
        epoch++
        runCatching { translator?.close() }
        runCatching { reverseTranslator?.close() }
        translator = null
        reverseTranslator = null
        builtFor = null
        reverseBuiltFor = null
        wordCache.clear()
        reverseWordCache.clear()
    }

    private fun reverseTranslator(): Translator {
        val pair = targetLang to sourceLang
        reverseTranslator?.let { if (reverseBuiltFor == pair) return it }
        runCatching { reverseTranslator?.close() }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(targetLang)
            .setTargetLanguage(sourceLang)
            .build()
        return Translation.getClient(options).also { reverseTranslator = it; reverseBuiltFor = pair }
    }

    private fun translator(): Translator {
        val pair = sourceLang to targetLang
        translator?.let { if (builtFor == pair) return it }
        translator?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()
        return Translation.getClient(options).also { translator = it; builtFor = pair }
    }

    /**
     * Translate [text] using the current languages. [onState] fires on the main thread: first
     * [State.Downloading] when a model must be fetched, then [State.Done] or [State.Failed]. Blank
     * input short-circuits to an empty result, as does a no-op pair (same source and target).
     *
     * Every ML Kit interaction is guarded: a synchronous failure (e.g. building the client) surfaces
     * as [State.Failed] rather than crashing, and async callbacks are dropped if the language changed
     * while they were in flight (see [epoch]).
     */
    fun translate(text: String, onState: (State) -> Unit) {
        if (text.isBlank()) { onState(State.Done("")); return }
        if (sourceLang == targetLang) { onState(State.Done(text)); return }
        val myEpoch = epoch
        val pair = sourceLang to targetLang
        val client = runCatching { translator() }.getOrElse {
            onState(State.Failed("Translation unavailable")); return
        }
        if (pair in downloaded) {
            runTranslate(client, text, onState, myEpoch)
        } else {
            onState(State.Downloading)
            runCatching {
                client.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        if (myEpoch != epoch) return@addOnSuccessListener
                        downloaded.add(pair)
                        runTranslate(client, text, onState, myEpoch)
                    }
                    .addOnFailureListener {
                        if (myEpoch == epoch) onState(State.Failed("Couldn't download the language"))
                    }
            }.onFailure { if (myEpoch == epoch) onState(State.Failed("Couldn't download the language")) }
        }
    }

    private fun runTranslate(client: Translator, text: String, onState: (State) -> Unit, myEpoch: Int) {
        if (myEpoch != epoch) return // the language changed (and this client may be closed): drop it
        runCatching {
            client.translate(text)
                .addOnSuccessListener { if (myEpoch == epoch) onState(State.Done(it)) }
                .addOnFailureListener { if (myEpoch == epoch) onState(State.Failed("Couldn't translate")) }
        }.onFailure { if (myEpoch == epoch) onState(State.Failed("Couldn't translate")) }
    }

    /**
     * Translate individual source [words] into the target language so the UI can align each output word
     * to the input word it came from. Results are cached per language pair and delivered on the main
     * thread as a lowercased-word → translation map. Best-effort: an untranslatable word is just absent.
     */
    fun translateWords(words: List<String>, onResult: (Map<String, String>) -> Unit) {
        batchWords(words, reverse = false, onResult)
    }

    /**
     * The reverse: translate individual target [words] back into the source language. The alignment
     * uses this so an output word that fuses several inputs (e.g. Spanish "estás" → "you are") can be
     * matched to all of them. Same caching/guarantees as [translateWords].
     */
    fun translateWordsReverse(words: List<String>, onResult: (Map<String, String>) -> Unit) {
        batchWords(words, reverse = true, onResult)
    }

    private fun batchWords(words: List<String>, reverse: Boolean, onResult: (Map<String, String>) -> Unit) {
        if (words.isEmpty()) { onResult(emptyMap()); return }
        if (sourceLang == targetLang) { onResult(words.associate { it.lowercase() to it }); return }
        val cache = if (reverse) reverseWordCache else wordCache
        val myEpoch = epoch
        val client = runCatching { if (reverse) reverseTranslator() else translator() }
            .getOrElse { onResult(snapshot(words, cache)); return }
        val needed = words.map { it.lowercase() }.distinct().filter { it !in cache }
        if (needed.isEmpty()) { onResult(snapshot(words, cache)); return }
        var remaining = needed.size
        val finish = {
            remaining--
            if (remaining <= 0 && myEpoch == epoch) onResult(snapshot(words, cache))
        }
        for (w in needed) {
            runCatching {
                client.translate(w)
                    .addOnSuccessListener { res -> if (myEpoch == epoch) putWord(cache, w, res); finish() }
                    .addOnFailureListener { finish() }
            }.onFailure { finish() }
        }
    }

    private fun putWord(cache: LinkedHashMap<String, String>, word: String, translation: String) {
        if (cache.size >= WORD_CACHE_MAX) cache.clear()
        cache[word] = translation
    }

    private fun snapshot(words: List<String>, cache: Map<String, String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (w in words) { val k = w.lowercase(); cache[k]?.let { out[k] = it } }
        return out
    }

    fun close() = invalidate()

    private companion object {
        /** Cap on cached single-word translations (cleared wholesale when exceeded). */
        const val WORD_CACHE_MAX = 800

        fun defaultSource(): String =
            TranslateLanguage.fromLanguageTag(Locale.getDefault().language) ?: TranslateLanguage.ENGLISH

        fun defaultTarget(): String =
            if (defaultSource() == TranslateLanguage.SPANISH) TranslateLanguage.ENGLISH
            else TranslateLanguage.SPANISH
    }
}
