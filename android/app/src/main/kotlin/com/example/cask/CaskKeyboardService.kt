package com.example.cask

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.PointF
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.example.cask.clipboard.ClipboardStore
import com.example.cask.engine.CorrectionEngine
import com.example.cask.emoji.GifInserter
import com.example.cask.emoji.GifResult
import com.example.cask.voice.VoiceCommands
import com.example.cask.voice.VoiceInputController
import com.example.cask.voice.VoicePermissionActivity
import java.io.File
import java.util.concurrent.Executors

/**
 * The core of the keyboard.
 *
 * [InputMethodService] is the Android base class for an "Input Method Editor" (IME) — i.e. a
 * software keyboard. The system binds to this service whenever a text field requests input, and we
 * return a [View] from [onCreateInputView] which becomes the keyboard the user sees.
 *
 * All actual text manipulation goes through [currentInputConnection], the bridge to the focused text
 * field (in any app).
 *
 * ## Text intelligence
 * Letters are typed into a **composing region** (the underlined word) rather than committed
 * immediately, so the [CorrectionEngine] can still change them. At each word boundary the engine
 * returns a [CorrectionEngine.CommitDecision]: the word is finished as typed, or auto-corrected under
 * the hybrid policy. The suggestion strip shows live corrections/completions while composing and
 * next-word predictions when idle; tapping a chip commits it. Sentence-start auto-capitalization and
 * double-space → ". " are applied here too. Everything the engine learns stays on-device.
 */
class CaskKeyboardService : InputMethodService(), CaskKeyboardView.OnKeyboardActionListener {

    private var keyboardView: CaskKeyboardView? = null
    private lateinit var engine: CorrectionEngine
    private val gifInserter by lazy { GifInserter(this) }

    // The Fix tool re-scores every word in the field, which can take a moment on long text; run it
    // off the UI thread and apply the result back on it (checking the field hasn't changed meanwhile).
    private val mainHandler = Handler(Looper.getMainLooper())
    private val fixExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cask-fix").apply { isDaemon = true }
    }
    private var fixRunning = false

    // Clipboard history (powers the clipboard tool in the suggestion strip). We snapshot the system
    // clip whenever it changes and when the keyboard regains focus, keeping the last 12 hours on-device.
    private lateinit var clipboard: ClipboardStore
    private var clipboardManager: ClipboardManager? = null
    private val clipChangedListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    /** Disabled for password / numeric / no-suggestion fields, where we type characters verbatim. */
    private var correctionEnabled = true

    // ---- Voice-to-type state ----
    private var voice: VoiceInputController? = null
    private var voiceActive = false
    /** True once the current spoken utterance has put text in the composing region. */
    private var voiceUtteranceStarted = false
    /** A leading space to glue this utterance onto preceding text (computed once per utterance). */
    private var voiceLeadingSpace = ""

    /** The just-applied auto-correction, enabling a one-press backspace revert. */
    private data class PendingRevert(val original: String, val corrected: String, val terminatorLen: Int)
    private var pendingRevert: PendingRevert? = null

    override fun onCreate() {
        super.onCreate()
        engine = CorrectionEngine.load(this)
        clipboard = ClipboardStore.get(this)
        clipboardManager = (getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            runCatching { it.addPrimaryClipChangedListener(clipChangedListener) }
        }
    }

    /** Called by the system to create the view shown above the candidate strip. */
    override fun onCreateInputView(): View {
        val view = CaskKeyboardView(this)
        view.actionListener = this
        keyboardView = view
        return view
    }

    /** Recolour + reset the layout each time the keyboard is shown for a field. */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val palette = AppColors.forPackage(this, info?.packageName)
        keyboardView?.applyAppColor(palette.accent, palette.isLight)

        correctionEnabled = info != null && isCorrectableField(info)
        keyboardView?.setGestureTypingEnabled(correctionEnabled)
        keyboardView?.resetTools() // a fresh field starts on suggestions, not a carried-over tools row
        pendingRevert = null
        engine.resetContext(currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0))
        refreshIdle()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (voiceActive) endVoice()
        currentInputConnection?.finishComposingText()
        engine.clearComposing()
        engine.flush()
        super.onFinishInputView(finishingInput)
    }

    /**
     * The nav-bar "down-arrow" (and the back gesture) dismiss the IME via a BACK key. We let the
     * keyboard view handle it first so that, while searching in the picker or in translate mode, it
     * only backs out of that sub-mode instead of hiding the whole IME. We key off the BACK press
     * (rather than [hideWindow]) so genuine dismissals, like switching apps, still hide normally.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isInputViewShown &&
            keyboardView?.onBackPressed() == true
        ) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        voice?.stop()
        voice = null
        fixExecutor.shutdownNow()
        clipboardManager?.let { runCatching { it.removePrimaryClipChangedListener(clipChangedListener) } }
        super.onDestroy()
    }

    /** Snapshot the system clipboard into our on-device history (best-effort; reads can be denied). */
    private fun captureClipboard() {
        runCatching { clipboard.captureCurrent(this) }
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // If the cursor moved somewhere our composing region didn't put it, the user navigated away:
        // drop the in-progress word and re-seed context so suggestions/auto-correct stay correct.
        val movedAway = engine.hasComposing() && (candidatesEnd < 0 || newSelEnd != candidatesEnd)
        if (movedAway || (!engine.hasComposing() && newSelStart != newSelEnd)) {
            currentInputConnection?.let { ic ->
                ic.finishComposingText()
                engine.resetContext(ic.getTextBeforeCursor(CONTEXT_CHARS, 0))
                refreshIdle()
            }
        }
    }

    // ---- OnKeyboardActionListener: events coming up from the key views ----

    override fun onText(text: CharSequence, touch: PointF?) {
        val ic = currentInputConnection ?: return
        if (!correctionEnabled) { ic.commitText(text, 1); return }

        val ch = if (text.length == 1) text[0] else null
        // Letters always extend a word; an apostrophe extends one in progress (contractions).
        if (ch != null && (ch.isLetter() || (ch == '\'' && engine.hasComposing()))) {
            // Part of a word: extend the composing region (with the tap position, for the spatial
            // model) and refresh live suggestions.
            pendingRevert = null
            engine.appendComposing(ch, touch)
            ic.setComposingText(engine.composingRaw(), 1)
            refreshComposingStrip()
            return
        }

        // Word boundary: finish the current word (maybe auto-correcting), then emit the separator.
        ic.beginBatchEdit()
        val decision = finishComposing(ic)
        val terminator = commitTerminator(ic, text)
        ic.endBatchEdit()
        engine.noteTerminator(terminator) // a sentence ender closes the n-gram context

        pendingRevert = if (decision != null && decision.autoCorrected) {
            PendingRevert(decision.original, decision.output, terminator.length)
        } else null
        refreshIdle()
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return

        // 1) Backspace right after an auto-correction reverts it (and teaches the engine).
        pendingRevert?.let { pr ->
            pendingRevert = null
            ic.beginBatchEdit()
            ic.deleteSurroundingText(pr.terminatorLen + pr.corrected.length, 0)
            ic.commitText(pr.original, 1)
            ic.endBatchEdit()
            engine.noteRevert(pr.original, pr.corrected)
            refreshIdle()
            return
        }

        if (!correctionEnabled) { deletePlain(ic); return }

        // 2) Editing the in-progress word: shrink the composing region.
        if (engine.deleteComposing()) {
            if (engine.hasComposing()) {
                ic.setComposingText(engine.composingRaw(), 1)
                refreshComposingStrip()
            } else {
                ic.finishComposingText()
                refreshIdle()
            }
            return
        }

        // 3) Plain delete; context before the cursor changed, so re-seed it.
        deletePlain(ic)
        engine.resetContext(ic.getTextBeforeCursor(CONTEXT_CHARS, 0))
        refreshIdle()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        if (correctionEnabled) {
            ic.beginBatchEdit()
            finishComposing(ic)
            ic.endBatchEdit()
            engine.noteTerminator("\n") // a new line starts a new thought: drop the word context
        }
        pendingRevert = null

        val info = currentInputEditorInfo
        if (info != null) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (!noEnterAction &&
                action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                ic.performEditorAction(action)
                return
            }
        }
        ic.commitText("\n", 1)
        refreshIdle()
    }

    override fun onPickText(text: CharSequence) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (correctionEnabled) finishComposing(ic)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        pendingRevert = null
        if (correctionEnabled) {
            engine.resetContext(ic.getTextBeforeCursor(CONTEXT_CHARS, 0))
            refreshIdle()
        }
    }

    override fun onPickGif(gif: GifResult) {
        val ic = currentInputConnection ?: return
        if (correctionEnabled) {
            ic.beginBatchEdit()
            finishComposing(ic)
            ic.endBatchEdit()
        }
        pendingRevert = null
        gifInserter.insert(ic, currentInputEditorInfo, gif)
    }

    /** Paste a clipboard image/screenshot: try rich-content insert, else fall back to the clipboard. */
    override fun onPickImage(file: File) {
        val ic = currentInputConnection ?: return
        if (correctionEnabled) {
            ic.beginBatchEdit()
            finishComposing(ic)
            ic.endBatchEdit()
        }
        pendingRevert = null
        insertImage(ic, file)
    }

    /**
     * The Fix tool: read everything in the field, run the on-device cleanup model over it
     * ([CorrectionEngine.fixText] — spelling, spacing, capitalization; nothing leaves the device),
     * and replace the field's text with the fixed version in one edit.
     */
    override fun onFixText() {
        val ic = currentInputConnection ?: return
        if (fixRunning) return
        ic.beginBatchEdit()
        if (correctionEnabled) finishComposing(ic)
        ic.endBatchEdit()
        pendingRevert = null

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val original = extracted?.text?.toString()
        if (original == null) { toast("Can't read this field"); return }
        if (original.isBlank()) { toast("Nothing to fix yet"); return }
        if (original.length > MAX_FIX_CHARS) { toast("Too much text to fix in one go"); return }
        val start = extracted!!.startOffset // non-null: original came from it

        fixRunning = true
        fixExecutor.execute {
            val fixed = runCatching { engine.fixText(original) }.getOrDefault(original)
            mainHandler.post {
                fixRunning = false
                val icNow = currentInputConnection ?: return@post
                if (fixed == original) { toast("Looks good — nothing to fix"); return@post }
                // Only apply if the field still holds exactly what we fixed (the user kept typing?).
                val nowText = icNow.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
                if (nowText != original) { toast("Text changed — tap fix again"); return@post }
                icNow.beginBatchEdit()
                if (icNow.setComposingRegion(start, start + original.length)) {
                    icNow.setComposingText(fixed, 1)
                    icNow.finishComposingText()
                } else {
                    // Editor without composing-region support: replace around the cursor instead.
                    val before = icNow.getTextBeforeCursor(original.length, 0)?.length ?: 0
                    val after = icNow.getTextAfterCursor(original.length, 0)?.length ?: 0
                    icNow.deleteSurroundingText(before, after)
                    icNow.commitText(fixed, 1)
                }
                icNow.endBatchEdit()
                if (correctionEnabled) {
                    engine.resetContext(icNow.getTextBeforeCursor(CONTEXT_CHARS, 0))
                    refreshIdle()
                }
            }
        }
    }

    /** Spacebar cursor control: shift the cursor left/right, first finishing any composing word. */
    override fun onCursorMove(delta: Int) {
        val ic = currentInputConnection ?: return
        if (engine.hasComposing()) {
            // The user is steering away from the in-progress word: keep it exactly as typed.
            ic.finishComposingText()
            engine.clearComposing()
        }
        pendingRevert = null
        sendDownUpKeyEvents(if (delta > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)
        if (correctionEnabled) {
            engine.resetContext(ic.getTextBeforeCursor(CONTEXT_CHARS, 0))
            refreshIdle()
        }
    }

    /** Held delete escalated to word mode: remove the previous word (or the composing one) at once. */
    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        pendingRevert = null
        if (correctionEnabled && engine.hasComposing()) {
            engine.clearComposing()
            ic.setComposingText("", 1)
            ic.finishComposingText()
            refreshIdle()
            return
        }
        val before = ic.getTextBeforeCursor(WORD_DELETE_LOOKBACK, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val n = before.length
        var i = n
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0) {
            val word = before[i - 1].isLetterOrDigit()
            while (i > 0 && !before[i - 1].isWhitespace() &&
                (before[i - 1].isLetterOrDigit() || before[i - 1] == '\'') == word
            ) i--
        }
        ic.deleteSurroundingText(n - i, 0)
        if (correctionEnabled) {
            engine.resetContext(ic.getTextBeforeCursor(CONTEXT_CHARS, 0))
            refreshIdle()
        }
    }

    /**
     * Insert a local image into the focused field via the rich-content API (the same path GIFs use).
     * Fields that accept images (most messengers) take it inline; otherwise we put it on the system
     * clipboard so the user can paste it manually — the button always does *something*.
     */
    private fun insertImage(ic: InputConnection, file: File) {
        val editor = currentInputEditorInfo
        val uri = runCatching { FileProvider.getUriForFile(this, "$packageName.gifs", file) }.getOrNull()
        val accepts = editor != null && uri != null &&
            EditorInfoCompat.getContentMimeTypes(editor).any { ClipDescription.compareMimeTypes(it, IMAGE_MIME) }
        if (accepts) {
            val info = InputContentInfoCompat(uri!!, ClipDescription("image", arrayOf(IMAGE_MIME)), null)
            val ok = runCatching {
                InputConnectionCompat.commitContent(
                    ic, editor!!, info,
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
                )
            }.getOrDefault(false)
            if (ok) return
        }
        if (uri != null) {
            runCatching {
                clipboardManager?.setPrimaryClip(ClipData.newUri(contentResolver, "image", uri))
            }
            toast("Image copied — long-press the field to paste")
        } else {
            toast("Couldn't insert image here")
        }
    }

    override fun onSuggestionPicked(word: CharSequence) {
        val ic = currentInputConnection ?: return
        if (!correctionEnabled) { ic.commitText("$word ", 1); return }
        val decision = engine.pickSuggestion(word.toString())
        ic.beginBatchEdit()
        ic.setComposingText(decision.output, 1)
        ic.finishComposingText()
        ic.commitText(" ", 1)
        ic.endBatchEdit()
        pendingRevert = null
        refreshIdle()
    }

    /** The "+" chip: add the in-progress word to the personal dictionary, keeping it in place. */
    override fun onAddWord() {
        if (!correctionEnabled) return
        if (engine.addCurrentWordToDictionary() == null) return
        // The word is now recognised; refresh the strip so the "+" turns back into suggestions.
        refreshComposingStrip()
    }

    // ---- Glide / swipe typing ----------------------------------------------

    /**
     * A glide started. Drop the single letter the key-down typed for this gesture, then commit any
     * word still composing (e.g. a word from the *previous* swipe) so this glide starts cleanly at a
     * boundary — without that, a second swipe would wipe the first word instead of following it.
     */
    override fun onGestureStart() {
        if (!correctionEnabled) return
        val ic = currentInputConnection ?: return
        engine.deleteComposing() // the letter typed on key-down (gestures replace whole words)
        ic.beginBatchEdit()
        if (engine.hasComposing()) {
            finishComposing(ic)
        } else {
            ic.setComposingText("", 1)
            ic.finishComposingText()
        }
        ic.endBatchEdit()
        pendingRevert = null
    }

    /** A glide finished: decode it, commit the best word as the composing region, offer alternates. */
    override fun onGesture(points: List<PointF>, keyCenters: Map<Char, PointF>, keyRadius: Float) {
        if (!correctionEnabled) return
        val ic = currentInputConnection ?: return
        val candidates = engine.gestureCandidates(points, keyCenters, keyRadius)
        if (candidates.isEmpty()) { refreshIdle(); return }

        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)
        val capitalize = engine.shouldCapitalizeNext(before)
        val display = if (capitalize) candidates.map { it.replaceFirstChar(Char::uppercase) } else candidates
        val best = display.first()

        ic.beginBatchEdit()
        // Auto-insert a space before the word (like GBoard) unless we're at a clean boundary already.
        val last = before?.lastOrNull()
        if (last != null && !last.isWhitespace() && last !in "([{\"'") ic.commitText(" ", 1)
        engine.setComposing(best)
        ic.setComposingText(best, 1)
        ic.endBatchEdit()

        pendingRevert = null
        keyboardView?.setAutoShift(false)
        keyboardView?.setSuggestions(display)
    }

    // ---- Voice-to-type -----------------------------------------------------

    /** Spacebar held: start dictation (requesting the mic permission first if we don't have it). */
    override fun onVoiceStart() {
        val ic = currentInputConnection
        if (ic == null) { keyboardView?.cancelVoiceMode(); return }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // The keyboard can't prompt directly — bounce through a tiny activity, then the user
            // holds the spacebar again once granted.
            runCatching { startActivity(VoicePermissionActivity.intent(this)) }
            keyboardView?.cancelVoiceMode()
            return
        }

        val controller = voice ?: VoiceInputController(this, voiceCallbacks).also { voice = it }
        if (!controller.isAvailable()) {
            toast("Voice typing isn't available on this device.")
            keyboardView?.cancelVoiceMode()
            return
        }

        // Finish any in-progress typed word so dictated text starts on a clean boundary.
        ic.finishComposingText()
        engine.clearComposing()
        keyboardView?.setSuggestions(emptyList())

        voiceActive = true
        voiceUtteranceStarted = false
        voiceLeadingSpace = ""
        keyboardView?.setVoicePaused(false)
        controller.start()
    }

    /** User left voice mode (tapped elsewhere): commit what's there and resume normal typing. */
    override fun onVoiceStop() {
        endVoice()
    }

    override fun onVoicePauseToggle() {
        val controller = voice ?: return
        controller.togglePause()
        keyboardView?.setVoicePaused(controller.isPaused)
    }

    private val voiceCallbacks = object : VoiceInputController.Callbacks {
        override fun onReadyForSpeech() {
            // A fresh listening session begins the next utterance.
            voiceUtteranceStarted = false
        }

        override fun onRms(db: Float) {
            keyboardView?.updateVoiceLevel(db)
        }

        override fun onPartial(text: String) {
            if (!voiceActive) return
            val ic = currentInputConnection ?: return
            val processed = VoiceCommands.process(text)
            if (processed.text.isEmpty()) return
            ic.setComposingText(leadingSpace(ic, processed.text) + processed.text, 1)
        }

        override fun onFinal(text: String) {
            if (!voiceActive) return
            val ic = currentInputConnection ?: return
            val processed = VoiceCommands.process(text)
            val out = leadingSpace(ic, processed.text) + processed.text
            ic.beginBatchEdit()
            if (out.isNotEmpty()) ic.setComposingText(out, 1)
            ic.finishComposingText()
            ic.endBatchEdit()
            voiceUtteranceStarted = false
            if (processed.send) {
                endVoice()
                performVoiceSend(ic)
            }
        }

        override fun onError(message: String, recoverable: Boolean) {
            // The controller only surfaces non-recoverable errors; it restarts itself otherwise.
            toast(message)
            finishVoiceUi()
        }
    }

    /**
     * The space (if any) needed to attach this utterance to the text before it. Computed once per
     * utterance — before we put anything in the composing region — then reused for its partials. No
     * space is added when the utterance opens with attaching punctuation ("period"/"." → "word.", not
     * "word ."), nor right after an opening bracket/quote.
     */
    private fun leadingSpace(ic: InputConnection, text: String): String {
        if (voiceUtteranceStarted) return voiceLeadingSpace
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val last = before.lastOrNull()
        val firstNew = text.firstOrNull()
        voiceLeadingSpace = when {
            last == null || last.isWhitespace() -> ""
            last in "([{\"'" -> ""
            firstNew != null && firstNew in ATTACHING_PUNCT -> ""
            else -> " "
        }
        voiceUtteranceStarted = true
        return voiceLeadingSpace
    }

    /** Fire the field's Send/Go/Done action (spoken "send"); fall back to a newline. */
    private fun performVoiceSend(ic: InputConnection) {
        val info = currentInputEditorInfo
        val action = info?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION } ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
    }

    /** Stop the recognizer and hand control back to typing (commits any shown partial). */
    private fun endVoice() {
        if (!voiceActive && voice == null) return
        voiceActive = false
        voice?.stop()
        currentInputConnection?.finishComposingText()
        keyboardView?.cancelVoiceMode()
        resumeTypingContext()
    }

    /** Reset just the UI/engine after the recognizer already stopped itself (e.g. on error). */
    private fun finishVoiceUi() {
        voiceActive = false
        currentInputConnection?.finishComposingText()
        keyboardView?.cancelVoiceMode()
        resumeTypingContext()
    }

    private fun resumeTypingContext() {
        voiceUtteranceStarted = false
        voiceLeadingSpace = ""
        if (correctionEnabled) {
            engine.resetContext(currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0))
        }
        refreshIdle()
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    // ---- Helpers -----------------------------------------------------------

    /** Finish the composing word (if any), applying the engine's commit decision. */
    private fun finishComposing(ic: InputConnection): CorrectionEngine.CommitDecision? {
        val decision = engine.commitWord() ?: return null
        ic.setComposingText(decision.output, 1)
        ic.finishComposingText()
        return decision
    }

    /** Commit a boundary character, turning a second consecutive space into ". ". Returns what was inserted. */
    private fun commitTerminator(ic: InputConnection, text: CharSequence): String {
        if (text == " ") {
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            if (before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                return ". "
            }
        }
        ic.commitText(text, 1)
        return text.toString()
    }

    private fun deletePlain(ic: InputConnection) {
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    /** Refresh the live strip while composing (corrections/completions, or the "+" add-word chip). */
    private fun refreshComposingStrip() {
        val strip = engine.composingStrip()
        keyboardView?.setSuggestions(strip.chips, strip.addWord)
    }

    /** Refresh the idle strip and sentence-start auto-capitalization. */
    private fun refreshIdle() {
        val kb = keyboardView ?: return
        // No live correction here (a password / email / URL field, or a non-text field). Show an
        // empty strip — its far-left tools toggle still works, so fix / translate / clipboard stay
        // one tap away.
        if (!correctionEnabled) { kb.setSuggestions(emptyList()); kb.setAutoShift(false); return }
        val before = currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0)
        val capitalize = engine.shouldCapitalizeNext(before)
        kb.setAutoShift(capitalize)
        // The tools row is no longer auto-shown at sentence start — it lives behind the strip's left
        // toggle now — so always surface next-word predictions/suggestions here.
        kb.setSuggestions(engine.idleSuggestions(capitalize))
    }

    /**
     * Text fields where live correction makes sense. We deliberately ignore
     * `TYPE_TEXT_FLAG_NO_SUGGESTIONS`: lots of ordinary composition fields set it (Gemini's prompt
     * box, Google Keep's note editor), and the user wants typo fixes there too. We only stay out of
     * non-text fields and the variations where autocorrect would corrupt the value — passwords,
     * email addresses and URLs.
     */
    private fun isCorrectableField(info: EditorInfo): Boolean {
        val type = info.inputType
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = type and InputType.TYPE_MASK_VARIATION
        return variation !in UNCORRECTABLE_VARIATIONS
    }

    private companion object {
        const val CONTEXT_CHARS = 96

        /** Upper bound for the Fix tool, so a pathological field can't stall the fix thread. */
        const val MAX_FIX_CHARS = 12_000

        /** How far back a single word-delete step looks for the word boundary. */
        const val WORD_DELETE_LOOKBACK = 64

        /** Text-field variations where autocorrect would corrupt the value, so it stays off. */
        val UNCORRECTABLE_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI,
        )

        /** Punctuation that hugs the preceding word — never preceded by a space. */
        const val ATTACHING_PUNCT = ",.!?;:)]}…%"

        /** MIME advertised for pasted clipboard images. */
        const val IMAGE_MIME = "image/png"
    }
}
