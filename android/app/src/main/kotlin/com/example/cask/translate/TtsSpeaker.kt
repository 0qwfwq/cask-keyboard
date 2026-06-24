package com.example.cask.translate

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Reads translated (or source) text aloud in the right language, as naturally as the device can.
 *
 * Wraps Android's [TextToSpeech]. We don't bundle voices — instead we lean on the platform engine
 * (Google's, on most phones), which ships modern neural voices and downloads the per-language voice
 * data on demand. For each request we set the engine to the requested [Locale] and pick the
 * highest-quality voice it advertises for that language, so pronunciation is correct whatever the
 * language being read.
 *
 * Init is asynchronous; a [speak] issued before the engine is ready is queued and fired on init.
 * Everything stays on-device — no API key, no cloud TTS — in keeping with the rest of the keyboard.
 */
class TtsSpeaker(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    /** A request that arrived before the engine finished initialising. */
    private var pending: (() -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            failed = !ready
            if (ready) {
                pending?.invoke()
            }
            pending = null
        }
    }

    /**
     * Speak [text] in the language given by the ML Kit [languageCode] (e.g. "en", "es", "zh"). Any
     * in-progress utterance is interrupted. [onUnavailable] is invoked (on the calling thread) when the
     * device has no voice for that language so the caller can surface a hint.
     */
    fun speak(text: String, languageCode: String, onUnavailable: () -> Unit) {
        if (text.isBlank()) return
        if (failed) { onUnavailable(); return }
        val action = { doSpeak(text, languageCode, onUnavailable) }
        if (ready) action() else pending = action
    }

    private fun doSpeak(text: String, languageCode: String, onUnavailable: () -> Unit) {
        val engine = tts ?: return
        val locale = Locale.forLanguageTag(languageCode)
        val result = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            onUnavailable()
            return
        }
        bestVoiceFor(engine, locale)?.let { runCatching { engine.voice = it } }
        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)
        runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        }
    }

    /**
     * The most natural voice the engine offers for [locale]: highest [Voice.getQuality], and among
     * equals an on-device one (so it still works offline) that isn't flagged as low quality.
     */
    private fun bestVoiceFor(engine: TextToSpeech, locale: Locale): Voice? {
        val voices = runCatching { engine.voices }.getOrNull() ?: return null
        val lang = locale.language
        return voices
            .filter { v -> !v.isInstallableUntilLater() && v.locale?.language == lang }
            .filter { Voice.QUALITY_VERY_LOW != it.quality }
            .maxWithOrNull(
                compareBy<Voice> { it.quality }
                    .thenByDescending { !it.isNetworkConnectionRequired } // prefer offline on ties
            )
    }

    private fun Voice.isInstallableUntilLater(): Boolean =
        features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

    /** Stop any in-progress speech (e.g. when leaving translate mode). */
    fun stop() {
        runCatching { tts?.stop() }
    }

    /** Release the engine. The next [speak] reconstructs it via a fresh [TtsSpeaker]. */
    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private companion object {
        const val UTTERANCE_ID = "cask-translate-tts"
    }
}
