package com.example.cask.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Drives Android's [SpeechRecognizer] for continuous, hands-free dictation.
 *
 * The platform recognizer stops after each pause; for a "keep talking" experience we transparently
 * restart it after every result (and after recoverable errors) until the caller [stop]s or [pause]s.
 * Results are reported as live [Callbacks.onPartial] hypotheses while speaking and a committed
 * [Callbacks.onFinal] per utterance; microphone level flows through [Callbacks.onRms] to drive the
 * spacebar waveform. Spoken punctuation and the "send" command are handled downstream in
 * [VoiceCommands]; here we just ask the recognizer to format results when the OS supports it.
 *
 * Everything touches [SpeechRecognizer] on the main thread, as it requires.
 */
class VoiceInputController(
    private val context: Context,
    private val callbacks: Callbacks,
) {

    interface Callbacks {
        fun onReadyForSpeech()
        fun onRms(db: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        /** [recoverable] false means voice typing can't continue (e.g. permission, no engine). */
        fun onError(message: String, recoverable: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    private var active = false      // between start() and stop()
    private var paused = false
    private var restartPending = false
    private var consecutiveErrors = 0

    val isPaused: Boolean get() = paused

    /** True only if the device actually has a speech engine to talk to. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (active) return
        if (!isAvailable()) {
            callbacks.onError("Voice typing isn't available on this device.", recoverable = false)
            return
        }
        active = true
        paused = false
        consecutiveErrors = 0
        ensureRecognizer()
        listen()
    }

    fun pause() {
        if (!active || paused) return
        paused = true
        cancelPendingRestart()
        // stopListening lets the in-flight utterance finalize (so nothing said is lost).
        runCatching { recognizer?.stopListening() }
    }

    fun resume() {
        if (!active || !paused) return
        paused = false
        consecutiveErrors = 0
        listen()
    }

    fun togglePause() {
        if (paused) resume() else pause()
    }

    fun stop() {
        if (!active) return
        active = false
        paused = false
        cancelPendingRestart()
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
    }

    // ---- internals ---------------------------------------------------------

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!active || paused) return
        ensureRecognizer()
        runCatching { recognizer?.startListening(buildIntent()) }
            .onFailure { scheduleRestart() }
    }

    private fun scheduleRestart() {
        if (!active || paused || restartPending) return
        restartPending = true
        main.postDelayed({
            restartPending = false
            listen()
        }, RESTART_DELAY_MS)
    }

    private fun cancelPendingRestart() {
        restartPending = false
        main.removeCallbacksAndMessages(null)
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        // Give the speaker a beat of silence before an utterance is considered done.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Let the engine punctuate/capitalize for the most natural, accurate text.
            putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
        }
    }

    private fun firstResult(results: Bundle?): String? =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull { it.isNotBlank() }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            consecutiveErrors = 0
            callbacks.onReadyForSpeech()
        }

        override fun onRmsChanged(rmsdB: Float) {
            callbacks.onRms(rmsdB)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let { callbacks.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            firstResult(results)?.let { callbacks.onFinal(it) }
            // Keep the session going for the next sentence.
            if (active && !paused) listen()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    callbacks.onError("Microphone permission is needed for voice typing.", recoverable = false)
                    stop()
                }
                // A silent/garbled utterance — just listen again, this is normal mid-dictation.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (active && !paused) listen()
                }
                else -> {
                    consecutiveErrors++
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        callbacks.onError("Voice typing stopped.", recoverable = false)
                        stop()
                    } else if (active && !paused) {
                        scheduleRestart()
                    }
                }
            }
        }

        override fun onBeginningOfSpeech() {}
        override fun onEndOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val RESTART_DELAY_MS = 120L
        const val MAX_CONSECUTIVE_ERRORS = 4
    }
}
