package com.example.cask.translate

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.cask.CaskTheme
import com.example.cask.R

/**
 * Translate-mode UI that drops in below the suggestion strip. Two stacked bars:
 *
 *  - **top bar** — the translated output: a round language button (translate *to*) on the left, a
 *    round **send** button on the right (commits the translation into the real text field), and a
 *    microphone inside the box that reads the translation aloud in its language.
 *  - **bottom bar** — what you type, in your language: a round language button (translate *from*) on
 *    the left, a round **swap** button on the right (flips the two languages), and a microphone inside
 *    the box that reads your text aloud.
 *
 * Typing is routed here by the keyboard (via [onSourceChar] / [onSourceDelete]); output updates live
 * through [TranslationManager] (on-device neural MT — natural, not word-for-word). Each word of the
 * source and its aligned word in the translation are tinted the same colour, so language learners can
 * see which output word came from which input word.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class TranslationBar(
    context: Context,
    private val theme: CaskTheme,
    private val manager: TranslationManager,
    private val host: Host,
) : LinearLayout(context) {

    interface Host {
        /** Commit the finished translation into the focused text field. */
        fun onSendTranslation(text: String)
        /** Leave translate mode (back to normal typing). */
        fun onCloseTranslate()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    private var source = ""
    private var translated = ""

    // Per-word colours from the latest alignment: linked source/translated words share a colour. Null
    // entries (and a null array, before alignment is computed) fall back to per-position colours on the
    // source side and to neutral on the translated side.
    private var sourceColors: Array<Int?>? = null
    private var targetColors: Array<Int?>? = null
    private val diacritics = Regex("\\p{M}+")

    private lateinit var sourceLangButton: TextView
    private lateinit var targetLangButton: TextView
    private lateinit var sourceField: TextView
    private lateinit var targetField: TextView
    private lateinit var sendButton: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var swapButton: FrameLayout
    private lateinit var swapIcon: ImageView
    private lateinit var sourceMic: ImageView
    private lateinit var targetMic: ImageView

    private var languagePopup: PopupWindow? = null
    private var speaker: TtsSpeaker? = null

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(BAR_HEIGHT_DP))
        val pad = theme.dp(4)
        setPadding(pad, pad, pad, pad)
        // Consume touches that land on the bar but not on a control (e.g. tapping the text fields), so
        // they don't fall through to the keyboard underneath and get typed as the nearest key.
        isClickable = true

        targetLangButton = langButton { showLanguagePicker(targetLangButton, isSource = false) }
        sourceLangButton = langButton { showLanguagePicker(sourceLangButton, isSource = true) }
        sendButton = iconCircle(R.drawable.ic_tool_send, CIRCLE_DP, accent = true, theme.onAccent) { send() }
            .also { sendIcon = it.getChildAt(0) as ImageView }
        swapButton = iconCircle(R.drawable.ic_translate_swap, CIRCLE_DP, accent = false, theme.textPrimary) { swapLanguages() }
            .also { swapIcon = it.getChildAt(0) as ImageView }

        val targetWrap = buildField(isSource = false)
        val sourceWrap = buildField(isSource = true)

        addView(bar(targetLangButton, targetWrap, sendButton)) // top: translated output + send
        addView(spacerV())
        addView(bar(sourceLangButton, sourceWrap, swapButton)) // bottom: what you type + swap

        updateLangButtons()
        renderSource()
        renderTarget()
    }

    // ---- Keyboard-driven input --------------------------------------------

    fun onSourceChar(text: CharSequence) {
        source += text
        renderSource()
        scheduleTranslate()
    }

    fun onSourceDelete() {
        if (source.isEmpty()) return
        source = source.dropLast(1)
        renderSource()
        scheduleTranslate()
    }

    /** Enter while translating sends the current translation (mirrors the send button). */
    fun onEnter() = send()

    /** Clear everything for a fresh translate session. */
    fun reset() {
        cancelPending()
        speaker?.stop()
        source = ""
        translated = ""
        sourceColors = null
        targetColors = null
        updateLangButtons()
        renderSource()
        renderTarget()
    }

    fun dismissPopups() {
        languagePopup?.let { runCatching { it.dismiss() } }
        languagePopup = null
    }

    /** Stop speech + popups and free the TTS engine (the keyboard window is going away). */
    fun release() {
        dismissPopups()
        speaker?.release()
        speaker = null
    }

    /** Re-apply the live theme (colours follow the foreground app like the rest of the keyboard). */
    fun applyTheme() {
        background = theme.keyboardBackground()
        sourceLangButton.background = theme.circleSurface()
        targetLangButton.background = theme.circleSurface()
        sourceLangButton.setTextColor(theme.textPrimary)
        targetLangButton.setTextColor(theme.textPrimary)
        sourceField.background = theme.pillChip()
        targetField.background = theme.pillChip()
        sendButton.background = theme.circleAccent()
        sendIcon.imageTintList = ColorStateList.valueOf(theme.onAccent)
        swapButton.background = theme.circleSurface()
        swapIcon.imageTintList = ColorStateList.valueOf(theme.textPrimary)
        sourceMic.background = theme.circleSurface()
        targetMic.background = theme.circleSurface()
        sourceMic.imageTintList = ColorStateList.valueOf(theme.textSecondary)
        targetMic.imageTintList = ColorStateList.valueOf(theme.textSecondary)
        renderSource()
        renderTarget()
    }

    // ---- Translation -------------------------------------------------------

    private fun scheduleTranslate() {
        cancelPending()
        val current = source
        if (current.isBlank()) { translated = ""; renderTarget(); return }
        val r = Runnable { requestTranslate(current) }
        pending = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    private fun requestTranslate(requested: String) {
        manager.translate(requested) { state ->
            if (requested != source) return@translate // a newer keystroke superseded this request
            when (state) {
                is TranslationManager.State.Downloading -> setStatus("Downloading language…")
                is TranslationManager.State.Failed -> setStatus(state.message)
                is TranslationManager.State.Done -> {
                    translated = state.text
                    // Clear the old alignment: source falls back to per-position colours, translated to
                    // neutral, until the new word links come back.
                    sourceColors = null
                    targetColors = null
                    renderSource()
                    renderTarget()
                    computeAlignment()
                }
            }
        }
    }

    private fun send() {
        val out = translated
        if (out.isBlank()) return
        host.onSendTranslation(out)
    }

    /** Flip the two languages; the current translation becomes the new input (like Google Translate). */
    private fun swapLanguages() {
        cancelPending()
        val carried = translated
        manager.swap()
        source = carried
        translated = ""
        updateLangButtons()
        renderSource()
        renderTarget()
        scheduleTranslate()
        dismissPopups()
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    // ---- Read-aloud (TTS) --------------------------------------------------

    private fun speakField(isSource: Boolean) {
        val text = if (isSource) source else translated
        if (text.isBlank()) return
        val lang = if (isSource) manager.sourceLang else manager.targetLang
        val spk = speaker ?: TtsSpeaker(context).also { speaker = it }
        spk.speak(text, lang) {
            // Runs on the main thread; surface a hint when the device has no voice for this language.
            Toast.makeText(context, "No ${manager.displayName(lang)} voice installed", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Rendering ---------------------------------------------------------

    private fun renderSource() {
        if (source.isEmpty()) {
            sourceField.text = "Type in ${manager.displayName(manager.sourceLang)}"
            sourceField.setTextColor(theme.textSecondary)
        } else {
            sourceField.setTextColor(theme.textPrimary)
            // Linked source words share their group's colour; until alignment is in (or for a word with
            // no partner) fall back to a per-position colour so every input word stays coloured.
            val colors = sourceColors
            sourceField.text = colorize(source) { i -> colors?.getOrNull(i) ?: theme.wordColor(i) }
        }
        setMicEnabled(sourceMic, source.isNotBlank())
    }

    private fun renderTarget() {
        if (translated.isEmpty()) {
            targetField.text = manager.displayName(manager.targetLang)
            targetField.setTextColor(theme.textSecondary)
        } else {
            targetField.setTextColor(theme.textPrimary)
            // Each translated word takes its linked group's colour; unmatched words (and everything
            // before alignment is computed) stay the plain primary text colour.
            val colors = targetColors
            targetField.text = colorize(translated) { j -> colors?.getOrNull(j) }
        }
        setMicEnabled(targetMic, translated.isNotBlank())
    }

    /** Apply [colorForWord] to each whitespace-separated word (null = leave it the base colour). */
    private fun colorize(text: String, colorForWord: (Int) -> Int?): CharSequence {
        val sb = SpannableStringBuilder(text)
        var i = 0
        var word = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            colorForWord(word)?.let {
                sb.setSpan(ForegroundColorSpan(it), start, i, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            word++
        }
        return sb
    }

    /**
     * Link input and output words so connected ones share a colour (e.g. "are", "you" and "estás" all
     * one colour; "today" and "hoy" another). On-device MT gives no alignment, so we translate each
     * source word into the target language *and* each target word back into the source language, then
     * draw a link wherever a single-word translation matches (accent/punctuation-insensitive, including
     * the multi-word case like "estás" → "you are"). Linked words form groups (a union-find over the two
     * sides); each group gets one colour. Best-effort and async — re-renders both fields when ready.
     */
    private fun computeAlignment() {
        val capS = source
        val capT = translated
        val srcWords = wordsOf(capS)
        val tgtWords = wordsOf(capT)
        if (srcWords.isEmpty() || tgtWords.isEmpty()) return
        var fwd: Map<String, String>? = null
        var bwd: Map<String, String>? = null
        val combine = {
            val f = fwd
            val b = bwd
            if (f != null && b != null && source == capS && translated == capT) {
                applyAlignment(srcWords, tgtWords, f, b)
            }
        }
        manager.translateWords(srcWords) { if (source == capS && translated == capT) { fwd = it; combine() } }
        manager.translateWordsReverse(tgtWords) { if (source == capS && translated == capT) { bwd = it; combine() } }
    }

    /** Build word groups from the two single-word translation maps and colour each group. */
    private fun applyAlignment(
        src: List<String>,
        tgt: List<String>,
        fwd: Map<String, String>,
        bwd: Map<String, String>,
    ) {
        val m = src.size
        val n = tgt.size
        val parent = IntArray(m + n) { it } // 0 until m = source, m until m+n = target
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var cur = x
            while (parent[cur] != cur) { val next = parent[cur]; parent[cur] = root; cur = next }
            return root
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        val srcNorm = src.map { normalize(it) }
        val tgtNorm = tgt.map { normalize(it) }
        val fwdWords = src.map { wordsNorm(fwd[it.lowercase()]) } // source word i -> its target words
        val bwdWords = tgt.map { wordsNorm(bwd[it.lowercase()]) } // target word j -> its source words
        for (i in 0 until m) {
            for (j in 0 until n) {
                val linked = matchesAny(fwdWords[i], tgtNorm[j]) || matchesAny(bwdWords[j], srcNorm[i])
                if (linked) union(i, m + j)
            }
        }

        // One colour per group, handed out in order of the first source word in the group; a target
        // word with no source link (its own group) stays neutral.
        val groupColor = HashMap<Int, Int>()
        var counter = 0
        val sColors = arrayOfNulls<Int>(m)
        for (i in 0 until m) {
            sColors[i] = groupColor.getOrPut(find(i)) { theme.wordColor(counter++) }
        }
        val tColors = arrayOfNulls<Int>(n)
        for (j in 0 until n) {
            tColors[j] = groupColor[find(m + j)] // null unless the group already has a source word
        }
        sourceColors = sColors
        targetColors = tColors
        renderSource()
        renderTarget()
    }

    /** True if any word in [candidates] matches [word] (equal, or contains with a length guard). */
    private fun matchesAny(candidates: List<String>, word: String): Boolean {
        if (word.isEmpty()) return false
        return candidates.any { c ->
            c.isNotEmpty() && (c == word ||
                (minOf(c.length, word.length) >= 4 && (c.contains(word) || word.contains(c))))
        }
    }

    /** Split on whitespace into words, matching [colorize]'s word indexing exactly. */
    private fun wordsOf(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            val s = i
            while (i < text.length && !text[i].isWhitespace()) i++
            out.add(text.substring(s, i))
        }
        return out
    }

    /** Split a (possibly multi-word) phrase into normalized words. */
    private fun wordsNorm(phrase: String?): List<String> =
        phrase?.let { wordsOf(it).map(::normalize).filter { w -> w.isNotEmpty() } } ?: emptyList()

    /** Lowercase, strip accents and punctuation — so "¿Cómo?" and "como" compare equal. */
    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(diacritics, "")
            .lowercase()
            .filter { it.isLetterOrDigit() }

    /** Transient status (downloading / error) shown in the output bar without clearing [translated]. */
    private fun setStatus(message: String) {
        targetField.text = message
        targetField.setTextColor(theme.textSecondary)
        setMicEnabled(targetMic, false)
    }

    private fun setMicEnabled(mic: ImageView, on: Boolean) {
        mic.isEnabled = on
        mic.alpha = if (on) 1f else 0.3f
    }

    private fun updateLangButtons() {
        sourceLangButton.text = manager.shortLabel(manager.sourceLang)
        targetLangButton.text = manager.shortLabel(manager.targetLang)
    }

    // ---- Building blocks ---------------------------------------------------

    /** A bar row: round button on the left, the text field in the middle, round button on the right. */
    private fun bar(leftCircle: View, field: View, rightCircle: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            gravity = Gravity.CENTER_VERTICAL
            addView(leftCircle)
            addView(field)
            addView(rightCircle)
        }

    private fun langButton(onClick: () -> Unit): TextView {
        val size = theme.dp(CIRCLE_DP)
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = theme.dp(6) }
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 12f
            setTextColor(theme.textPrimary)
            background = theme.circleSurface()
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** A round icon button (send / swap), sized to match the language circles, on the right of a bar. */
    private fun iconCircle(iconRes: Int, sizeDp: Int, accent: Boolean, tint: Int, onClick: () -> Unit): FrameLayout {
        val size = theme.dp(sizeDp)
        val frame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginStart = theme.dp(6) }
            background = if (accent) theme.circleAccent() else theme.circleSurface()
            isClickable = true
            setOnClickListener { onClick() }
        }
        val icon = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(theme.dp(22), theme.dp(22), Gravity.CENTER)
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(tint)
        }
        frame.addView(icon)
        return frame
    }

    /** The text field plus its read-aloud microphone, as one pill. Stores the field + mic refs. */
    private fun buildField(isSource: Boolean): FrameLayout {
        val wrap = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).also {
                val v = theme.dp(3)
                it.topMargin = v; it.bottomMargin = v
            }
        }
        val field = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            gravity = Gravity.CENTER_VERTICAL
            typeface = theme.keyTypeface
            textSize = 15f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            background = theme.pillChip()
            // Leave room on the right for the mic so text never runs under it.
            setPadding(theme.dp(12), 0, theme.dp(MIC_DP + 8), 0)
        }
        val mic = ImageView(context).apply {
            val s = theme.dp(MIC_DP)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.CENTER_VERTICAL)
                .also { it.marginEnd = theme.dp(4) }
            val p = theme.dp(6)
            setPadding(p, p, p, p)
            setImageResource(R.drawable.ic_translate_mic)
            imageTintList = ColorStateList.valueOf(theme.textSecondary)
            background = theme.circleSurface()
            isClickable = true
            setOnClickListener { speakField(isSource) }
        }
        wrap.addView(field)
        wrap.addView(mic)
        if (isSource) { sourceField = field; sourceMic = mic } else { targetField = field; targetMic = mic }
        return wrap
    }

    private fun spacerV(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(4))
    }

    // ---- Language picker popup --------------------------------------------

    private fun showLanguagePicker(anchor: View, isSource: Boolean) {
        dismissPopups()
        val current = if (isSource) manager.sourceLang else manager.targetLang

        val list = LinearLayout(context).apply { orientation = VERTICAL }
        for (lang in manager.languages) {
            list.addView(languageRow(lang, selected = lang.code == current) {
                if (isSource) manager.setSource(lang.code) else manager.setTarget(lang.code)
                updateLangButtons()
                renderSource()
                renderTarget()
                scheduleTranslate()
                dismissPopups()
            })
        }
        val scroller = ScrollView(context).apply {
            background = theme.keyboardBackground()
            val p = theme.dp(6)
            setPadding(p, p, p, p)
            addView(list)
        }
        // Non-focusable: a focusable popup steals window focus from the IME, which makes the system
        // hide the whole keyboard (and the popup with it). Outside-touchable + a background drawable so
        // tapping elsewhere still dismisses it. Touches inside (scroll + row taps) work either way.
        val popup = PopupWindow(scroller, theme.dp(POPUP_WIDTH_DP), theme.dp(POPUP_HEIGHT_DP), false).apply {
            elevation = theme.dp(8).toFloat()
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        languagePopup = popup
        // Place it above the anchor (the keyboard sits at the bottom of the screen).
        runCatching { popup.showAsDropDown(anchor, 0, -(theme.dp(POPUP_HEIGHT_DP) + anchor.height)) }
    }

    private fun languageRow(lang: TranslationManager.Lang, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(40))
            gravity = Gravity.CENTER_VERTICAL
            text = lang.name
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(if (selected) theme.accent else theme.textPrimary)
            val h = theme.dp(10)
            setPadding(h, 0, h, 0)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private companion object {
        const val BAR_HEIGHT_DP = 104
        const val CIRCLE_DP = 38
        const val MIC_DP = 30
        const val POPUP_WIDTH_DP = 220
        const val POPUP_HEIGHT_DP = 260
        const val DEBOUNCE_MS = 250L
    }
}
