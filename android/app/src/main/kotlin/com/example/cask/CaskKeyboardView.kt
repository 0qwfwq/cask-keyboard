package com.example.cask

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.cask.clipboard.ClipboardPanel
import com.example.cask.emoji.EmojiPanel
import com.example.cask.emoji.GifResult
import com.example.cask.engine.KeyGeometry
import com.example.cask.translate.TranslationBar
import com.example.cask.translate.TranslationManager
import com.example.cask.voice.WaveformView
import java.io.File
import kotlin.math.hypot

/**
 * The visible keyboard. Built entirely in code (no deprecated [android.inputmethodservice.KeyboardView])
 * so it is easy to read and extend.
 *
 * The view is a vertical [LinearLayout]: a suggestion strip on top, then rows of keys. Each row is a
 * horizontal [LinearLayout] of keys; keys are plain [TextView]s with weighted widths so the rows
 * always fill the screen evenly. All styling (the dark-navy "liquid glass" look) comes from
 * [CaskTheme]; this class only describes *layout* and *behaviour*.
 *
 * ## Multi-touch input model
 * The keyboard intercepts **all** touch in this parent view and tracks every finger independently
 * (by pointer id), so you can type with two thumbs as fast as you like — a new key registers the
 * instant it goes down even if another finger hasn't lifted yet, and every finger's release reliably
 * stops that finger's own gestures (shift long-press, delete auto-repeat, comma hold). No key has its
 * own touch listener; that single-pointer design dropped the second thumb's keypress and lost
 * release events under fast two-thumb typing.
 *
 * Character keys are resolved by **proximity** (nearest key by squared distance to its rectangle), so
 * near-misses and gaps still hit the intended letter. Press-and-hold keys (shift, delete, comma) and
 * the suggestion chips are resolved by exact bounds first, so the grid never steals them.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class CaskKeyboardView(context: Context) : LinearLayout(context) {

    interface OnKeyboardActionListener {
        /** A character was typed. [touch] is the tap position in KeyGeometry key-units for a single
         *  letter key (used by the spatial autocorrect model), or null for space/punctuation/paste. */
        fun onText(text: CharSequence, touch: PointF? = null)
        fun onDelete()
        fun onEnter()

        /** A suggestion/prediction chip in the top strip was tapped. */
        fun onSuggestionPicked(word: CharSequence)

        /** The "+" chip was tapped: add the word currently being composed to the personal dictionary. */
        fun onAddWord()

        /** Insert literal text (emoji / emoticon / clipboard text) chosen from a panel. */
        fun onPickText(text: CharSequence)

        /** A GIF was chosen from the picker; insert it as rich content (or fall back). */
        fun onPickGif(gif: GifResult)

        /** A clipboard image/screenshot was chosen; insert it as rich content (or fall back). */
        fun onPickImage(file: File)

        /** The Fix tool chip was tapped: clean up the whole field's text with the on-device model. */
        fun onFixText()

        /** Sliding on the spacebar: move the text cursor [delta] characters (±1 per step). */
        fun onCursorMove(delta: Int)

        /** Delete kept held past the per-character phase: remove the whole previous word. */
        fun onDeleteWord()

        /** The spacebar was held: enter voice-to-type mode (the service starts the recognizer). */
        fun onVoiceStart()

        /** Leave voice-to-type mode (the service stops the recognizer). */
        fun onVoiceStop()

        /** The waveform spacebar was tapped while dictating: pause or resume. */
        fun onVoicePauseToggle()

        /** A glide/swipe gesture began (past the first key): drop the letter typed on key-down. */
        fun onGestureStart()

        /** A glide/swipe gesture finished: decode [points] using the live letter-key [keyCenters]. */
        fun onGesture(points: List<PointF>, keyCenters: Map<Char, PointF>, keyRadius: Float)
    }

    var actionListener: OnKeyboardActionListener? = null

    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2 }
    private enum class ShiftState { OFF, SHIFTED, CAPS_LOCK }
    private enum class KeyStyle { CHAR, SPECIAL, ACCENT }

    /** What a key does when pressed. HOLD fires [TouchKey.action] on a quick tap, [TouchKey.holdAction] on long-press. */
    private enum class Kind { TAP, LETTER, SHIFT, DELETE, COMMA, HOLD, SPACE }

    /**
     * A touchable key. [proximity] keys (letters, space, enter, layer toggles) snap to the nearest
     * press; non-proximity keys (shift, delete, comma, chips) require an exact hit. [baseLabel] is the
     * lowercase letter for [Kind.LETTER] keys so they can be re-cased without rebuilding the layout.
     */
    private class TouchKey(
        val view: View,
        val proximity: Boolean,
        val kind: Kind,
        val baseLabel: String? = null,
        val action: (() -> Unit)? = null,
        val holdAction: (() -> Unit)? = null,
    )

    /** Per-finger state while a key is held: its key plus any pending hold/repeat callbacks. */
    private class Pointer(val key: TouchKey, val downX: Float) {
        var longFired = false
        var longRunnable: Runnable? = null
        var repeatRunnable: Runnable? = null

        // Spacebar cursor control: sliding sideways on the spacebar moves the text cursor.
        var cursorMode = false
        var cursorAnchorX = 0f

        // Held delete escalates from characters to whole words after enough repeats.
        var deleteRepeats = 0
    }

    private var mode = Mode.LETTERS
    private var shiftState = ShiftState.OFF

    private val mainHandler = Handler(Looper.getMainLooper())

    private val theme = CaskTheme(context)
    private val haptics = Haptics(context)
    private val basePadding = theme.dp(4)

    // Stable top-level sections (so rebuilds and the picker can show/hide them independently).
    private val stripHolder = FrameLayout(context)
    private val keyRowsContainer = LinearLayout(context).apply { orientation = VERTICAL }
    private var emojiPanel: EmojiPanel? = null
    private var clipboardPanel: ClipboardPanel? = null
    private var pickerOpen = false

    // Translate mode: the suggestion strip is swapped for a two-bar translator and typed keys are
    // routed into its source field. The bar lives as a top-level child (like the panels) so it
    // survives key-layout rebuilds (symbols/shift) the same way the picker does.
    private var translationBar: TranslationBar? = null
    private var translationManager: TranslationManager? = null
    private var translateActive = false
    private var gestureEnabledBeforeTranslate = true

    // While searching in the picker, typed keys are routed here instead of to the text field.
    private var searchSink: ((CharSequence) -> Unit)? = null
    private var searchDeleteSink: (() -> Unit)? = null

    // Key + touch state, rebuilt on every [buildKeyboard].
    private val keys = ArrayList<TouchKey>()
    private val chipKeys = ArrayList<TouchKey>()      // current suggestion chips (subset of keys)
    private var shiftKeyRef: TouchKey? = null
    private val pointers = HashMap<Int, Pointer>()    // pointerId -> finger currently held
    private var suggestionStrip: LinearLayout? = null
    private var currentSuggestions: List<String> = emptyList()
    private var currentAddWord = false                // true => last chip is the "+" add-to-dictionary button
    private var toolsMode = false                     // true => strip shows the fix/translate/clipboard tools
    private val tmpRect = Rect()

    // Floating "key preview" bubble shown above a key while it is held (e.g. comma -> em dash).
    private var keyPreview: PopupWindow? = null

    // Voice-to-type: hold the spacebar to dictate. While active, only the spacebar changes — its
    // label is swapped for an accent-coloured waveform. These refs are rebuilt with the bottom row.
    private var voiceActive = false
    private var spaceKeyRef: TouchKey? = null
    private var spaceLabelRef: TextView? = null
    private var waveformViewRef: WaveformView? = null

    // Glide / swipe typing. A single finger that starts on a letter and slides onto another becomes a
    // gesture: per-key typing stops, the traced path is drawn as an accent-coloured trail, and on
    // release the path is decoded into a word. Two-thumb typing never triggers it (it needs to be the
    // only finger down), so fast tapping is unaffected.
    private var gestureTypingEnabled = true
    private var gestureCandidateId = -1      // a lone finger on a letter that may yet become a glide
    private var gesturePointerId = -1        // the finger currently gliding (-1 = none)
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureStartKey: TouchKey? = null
    private val gesturePoints = ArrayList<PointF>()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = theme.accent
    }

    // Material "fast-out-slow-in" curve via PathInterpolator (API 21+) — no extra dependency.
    private val decelerate: Interpolator = DecelerateInterpolator()
    private val fastOutSlowIn: Interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    // ---- Key layouts -------------------------------------------------------

    private val letterRows = listOf(
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() },
        "zxcvbnm".map { it.toString() },
    )

    private val symbolRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "\$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("*", "\"", "'", ":", ";", "!", "?"),
    )

    private val symbol2Rows = listOf(
        listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "∆"),
        listOf("£", "¢", "€", "¥", "^", "°", "=", "{", "}", "\\"),
        listOf("%", "©", "®", "™", "✓", "[", "]"),
    )

    init {
        orientation = VERTICAL
        background = theme.keyboardBackground()
        // Apply the comfortable floor gap straight away so the space below the bottom row is present
        // from the very first frame. Without this the bottom padding stayed at [basePadding] until the
        // system happened to dispatch insets (which it only did after the first touch), so the gap
        // "popped in" on the first tap. [applyBottomInset] refines it once the real nav inset arrives.
        setPadding(basePadding, basePadding, basePadding, maxOf(basePadding, theme.dp(24)))
        applyBottomInset()
        stripHolder.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        keyRowsContainer.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        addView(stripHolder)
        addView(keyRowsContainer)
        buildKeyboard()
    }

    /**
     * Keep the bottom key row clear of the system's "hide keyboard" down-arrow and IME-switcher
     * icon, which the OS draws in the navigation region. We pad the bottom by the larger of the
     * reported nav/tappable inset and a comfortable floor so there is always a visible gap.
     */
    private fun applyBottomInset() {
        ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
            val bottom = maxOf(nav, tappable, theme.dp(24))
            v.setPadding(basePadding, basePadding, basePadding, bottom)
            insets
        }
        ViewCompat.requestApplyInsets(this)
    }

    /** Recolour the whole keyboard to the foreground app's brand colour + light/dark, then rebuild. */
    fun applyAppColor(appAccent: Int, light: Boolean) {
        theme.recolor(appAccent, light)
        background = theme.keyboardBackground()
        trailPaint.color = theme.accent
        resetState()
    }

    /** Enable/disable glide typing (off for password/numeric fields where we type verbatim). */
    fun setGestureTypingEnabled(enabled: Boolean) {
        gestureTypingEnabled = enabled
        if (!enabled) cancelGesture()
    }

    /** Rebuild every row from the current [mode] / [shiftState] into the stable section holders. */
    private fun buildKeyboard() {
        // A rebuild throws away the spacebar's waveform view, so leave voice mode first.
        if (voiceActive) exitVoiceMode()
        cancelGesture()
        clearAllPointers(cancelled = true)
        keys.clear()
        chipKeys.clear()
        shiftKeyRef = null
        spaceKeyRef = null
        spaceLabelRef = null
        waveformViewRef = null
        stripHolder.removeAllViews()
        keyRowsContainer.removeAllViews()
        stripHolder.addView(buildSuggestionStrip())
        val rows = when (mode) {
            Mode.LETTERS -> letterRows
            Mode.SYMBOLS -> symbolRows
            Mode.SYMBOLS2 -> symbol2Rows
        }
        // Top two rows are just characters. In letter mode the second row is inset by half a key
        // (like a real keyboard) so the staggered look lines up.
        keyRowsContainer.addView(buildCharRow(rows[0], sidePadding = 0f))
        keyRowsContainer.addView(buildCharRow(rows[1], sidePadding = if (mode == Mode.LETTERS) 0.5f else 0f))
        keyRowsContainer.addView(buildThirdRow(rows[2]))
        keyRowsContainer.addView(buildBottomRow())
    }

    // ---- Multi-touch handling ----------------------------------------------

    /**
     * Own touches over the key rows so we can track all fingers ourselves (true multi-touch). When
     * the picker is open we only grab touches over the (possibly visible) key rows and let everything
     * else flow to the panel's scrolling list / buttons.
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // In the picker or translate mode the keyboard only owns touches over the key rows (and, while
        // translating, the tools strip too, since its chips are hit-tested by us); everything else (the
        // panel list, or the translation bar's own buttons) handles its own touches.
        if (!pickerOpen && !translateActive) return true
        if (ev.actionMasked != MotionEvent.ACTION_DOWN && ev.actionMasked != MotionEvent.ACTION_POINTER_DOWN) {
            return false
        }
        val idx = ev.actionIndex
        val x = ev.getX(idx)
        val y = ev.getY(idx)
        if (keyRowsContainer.visibility == View.VISIBLE && pointInKeyRows(x, y)) return true
        // Translate mode (no panel open): the tools strip stays visible above the translator and its
        // chips are ours to hit-test. The translator's circles/mics sit outside the strip and keep
        // their own click listeners, so we don't grab those.
        if (translateActive && !pickerOpen &&
            stripHolder.visibility == View.VISIBLE && pointInView(stripHolder, x, y)
        ) return true
        return false
    }

    private fun pointInKeyRows(x: Float, y: Float): Boolean = pointInView(keyRowsContainer, x, y)

    private fun pointInView(view: View, x: Float, y: Float): Boolean {
        rectInSelf(view, tmpRect)
        return tmpRect.contains(x.toInt(), y.toInt())
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                startPointer(ev.getPointerId(idx), ev.getX(idx), ev.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> onMove(ev)
            MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == gesturePointerId) finishGesture(ev) else releaseTouch(id)
            }
            MotionEvent.ACTION_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == gesturePointerId) {
                    finishGesture(ev)
                } else {
                    releaseTouch(id)
                    if (pointers.isNotEmpty()) clearAllPointers(cancelled = true) // safety
                }
                gestureCandidateId = -1
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelGesture()
                clearAllPointers(cancelled = true)
            }
        }
        return true
    }

    /** Release a non-gliding finger, clearing it as a glide candidate if it was one. */
    private fun releaseTouch(id: Int) {
        if (id == gestureCandidateId) gestureCandidateId = -1
        endPointer(id, cancelled = false)
    }

    private fun startPointer(id: Int, x: Float, y: Float) {
        if (gesturePointerId != -1) return // a glide owns the keyboard; ignore other fingers
        if (pointers.containsKey(id)) return
        // A second finger means two-thumb typing, not a glide: the lone candidate is no longer one.
        val firstFinger = pointers.isEmpty()
        if (!firstFinger) gestureCandidateId = -1
        val key = resolveKey(x, y) ?: return
        // While dictating, touching anything other than the waveform spacebar simply stops voice
        // typing. The tapped key must NOT also act (no character is typed) — the touch is consumed
        // purely to dismiss voice mode, so the next tap types normally.
        if (voiceActive && key !== spaceKeyRef) { exitVoiceMode(); return }
        val p = Pointer(key, x)
        pointers[id] = p
        key.view.isPressed = true
        pressDown(key.view)
        haptics.keyTap()
        when (key.kind) {
            Kind.TAP -> key.action?.invoke()
            Kind.LETTER -> typeLetter(key, x, y)
            Kind.SHIFT -> {
                val r = Runnable { p.longFired = true; setShiftState(ShiftState.CAPS_LOCK) }
                p.longRunnable = r
                mainHandler.postDelayed(r, LONG_PRESS_MS)
            }
            Kind.DELETE -> {
                emitDelete()
                val r = object : Runnable {
                    override fun run() {
                        // After enough per-character repeats a held delete escalates to whole words
                        // (like GBoard), so clearing a long stretch doesn't take forever. Word mode
                        // stays out of the picker-search / translate sinks, which expect characters.
                        p.deleteRepeats++
                        if (p.deleteRepeats >= WORD_DELETE_AFTER && !translateActive && searchDeleteSink == null) {
                            haptics.keyTap()
                            actionListener?.onDeleteWord()
                            mainHandler.postDelayed(this, WORD_DELETE_REPEAT_MS)
                        } else {
                            emitDelete()
                            mainHandler.postDelayed(this, DELETE_REPEAT_MS)
                        }
                    }
                }
                p.repeatRunnable = r
                mainHandler.postDelayed(r, DELETE_INITIAL_MS)
            }
            Kind.COMMA -> {
                val r = Runnable { p.longFired = true; haptics.keyTap(); showKeyPreview(key.view, EM_DASH) }
                p.longRunnable = r
                mainHandler.postDelayed(r, LONG_PRESS_MS)
            }
            Kind.HOLD -> {
                val r = Runnable { p.longFired = true; haptics.keyTap(); key.holdAction?.invoke() }
                p.longRunnable = r
                mainHandler.postDelayed(r, LONG_PRESS_MS)
            }
            Kind.SPACE -> {
                // Tap = space (or pause/resume while dictating); hold = start voice typing.
                val r = Runnable { p.longFired = true; haptics.keyTap(); enterVoiceMode() }
                p.longRunnable = r
                mainHandler.postDelayed(r, LONG_PRESS_MS)
            }
        }

        // A lone finger landing on a letter could be the start of a glide. The letter has already been
        // typed (above) so a plain tap is instant; if it turns into a glide we undo it in onGestureStart.
        if (firstFinger && gestureTypingEnabled && !pickerOpen && key.kind == Kind.LETTER) {
            gestureCandidateId = id
            gestureStartX = x
            gestureStartY = y
            gestureStartKey = key
        }
    }

    private fun endPointer(id: Int, cancelled: Boolean) {
        val p = pointers.remove(id) ?: return
        p.longRunnable?.let { mainHandler.removeCallbacks(it) }
        p.repeatRunnable?.let { mainHandler.removeCallbacks(it) }
        p.key.view.isPressed = false
        release(p.key.view)
        when (p.key.kind) {
            Kind.SHIFT -> if (!cancelled && !p.longFired) toggleShiftTap()
            Kind.COMMA -> {
                dismissKeyPreview()
                if (!cancelled) emitText(if (p.longFired) EM_DASH else ",")
            }
            Kind.HOLD -> if (!cancelled && !p.longFired) p.key.action?.invoke()
            Kind.SPACE -> if (!cancelled && !p.longFired) {
                if (voiceActive) actionListener?.onVoicePauseToggle() else emitText(" ")
            }
            else -> {} // TAP / LETTER / DELETE already acted on the press
        }
    }

    private fun clearAllPointers(cancelled: Boolean) {
        if (pointers.isEmpty()) return
        for (id in pointers.keys.toList()) endPointer(id, cancelled)
    }

    // ---- Glide / swipe typing ----------------------------------------------

    private fun onMove(ev: MotionEvent) {
        // Already gliding: just extend the traced path.
        if (gesturePointerId != -1) {
            val idx = ev.findPointerIndex(gesturePointerId)
            if (idx >= 0) addGesturePoint(ev.getX(idx), ev.getY(idx))
            return
        }
        handleSpaceCursorSlide(ev)
        // Watching a lone finger to see if it slides off its letter into a glide.
        if (gestureCandidateId == -1) return
        val idx = ev.findPointerIndex(gestureCandidateId)
        if (idx < 0) return
        val x = ev.getX(idx)
        val y = ev.getY(idx)
        if (hypot((x - gestureStartX).toDouble(), (y - gestureStartY).toDouble()) < theme.dp(GESTURE_START_DP)) return
        val now = nearestProximityKey(x, y)
        if (now != null && now.kind == Kind.LETTER && now !== gestureStartKey) beginGesture(x, y)
    }

    /**
     * GBoard-style cursor control: a finger that lands on the spacebar and slides sideways moves the
     * text cursor one character per [CURSOR_STEP_DP] of travel instead of typing a space. Entering the
     * slide cancels the voice-typing long-press and marks the touch consumed, so lifting the finger
     * afterwards types nothing. Disabled while dictating (a spacebar tap means pause/resume there).
     */
    private fun handleSpaceCursorSlide(ev: MotionEvent) {
        // Not while dictating (spacebar = pause there), and not while typing is routed into the
        // picker's search box or the translator — the cursor being moved wouldn't be the one visible.
        if (voiceActive || pickerOpen || translateActive || searchSink != null) return
        for (i in 0 until ev.pointerCount) {
            val p = pointers[ev.getPointerId(i)] ?: continue
            if (p.key.kind != Kind.SPACE) continue
            val x = ev.getX(i)
            if (!p.cursorMode) {
                if (kotlin.math.abs(x - p.downX) < theme.dp(CURSOR_START_DP)) continue
                p.cursorMode = true
                p.longFired = true // consumed by the slide: no voice mode, no space on release
                p.longRunnable?.let { mainHandler.removeCallbacks(it) }
                p.cursorAnchorX = x
                haptics.keyTap()
            }
            val step = theme.dp(CURSOR_STEP_DP).toFloat()
            while (x - p.cursorAnchorX >= step) {
                actionListener?.onCursorMove(1)
                p.cursorAnchorX += step
            }
            while (p.cursorAnchorX - x >= step) {
                actionListener?.onCursorMove(-1)
                p.cursorAnchorX -= step
            }
        }
    }

    /** Promote the candidate finger to a glide: stop typing keys, undo the first letter, start the trail. */
    private fun beginGesture(x: Float, y: Float) {
        val id = gestureCandidateId
        gestureCandidateId = -1
        gesturePointerId = id
        // The finger pressed a letter on key-down; release that key cleanly and cancel its hold timer.
        pointers.remove(id)?.let { p ->
            p.longRunnable?.let { mainHandler.removeCallbacks(it) }
            p.repeatRunnable?.let { mainHandler.removeCallbacks(it) }
            p.key.view.isPressed = false
            release(p.key.view)
        }
        dismissKeyPreview()
        gesturePoints.clear()
        gesturePoints.add(PointF(gestureStartX, gestureStartY))
        gesturePoints.add(PointF(x, y))
        haptics.keyTap()
        actionListener?.onGestureStart() // remove the single letter typed on key-down
        invalidate()
    }

    private fun addGesturePoint(x: Float, y: Float) {
        val last = gesturePoints.lastOrNull()
        if (last != null && hypot((x - last.x).toDouble(), (y - last.y).toDouble()) < theme.dp(2)) return
        gesturePoints.add(PointF(x, y))
        invalidate()
    }

    /** The glide finger lifted: decode the traced path into a word, then clear the trail. */
    private fun finishGesture(ev: MotionEvent) {
        val idx = ev.findPointerIndex(gesturePointerId)
        if (idx >= 0) addGesturePoint(ev.getX(idx), ev.getY(idx))
        val points = ArrayList(gesturePoints)
        val centers = letterCenters()
        val radius = keyRadiusPx()
        resetGestureState()
        invalidate()
        if (points.size >= 2 && centers.isNotEmpty()) {
            actionListener?.onGesture(points, centers, radius)
        }
    }

    private fun cancelGesture() {
        if (gesturePointerId == -1 && gestureCandidateId == -1 && gesturePoints.isEmpty()) return
        resetGestureState()
        invalidate()
    }

    private fun resetGestureState() {
        gesturePointerId = -1
        gestureCandidateId = -1
        gestureStartKey = null
        gesturePoints.clear()
    }

    /** Live centres of every visible letter key, in this view's coordinate space (for the decoder). */
    private fun letterCenters(): Map<Char, PointF> {
        val map = HashMap<Char, PointF>()
        for (k in keys) {
            if (k.kind != Kind.LETTER) continue
            val ch = k.baseLabel?.firstOrNull() ?: continue
            val v = k.view
            if (v.width == 0 || v.visibility != View.VISIBLE) continue
            rectInSelf(v, tmpRect)
            map[ch] = PointF(tmpRect.exactCenterX(), tmpRect.exactCenterY())
        }
        return map
    }

    /** Half the width of a letter key, the unit distances are normalised by in the decoder. */
    private fun keyRadiusPx(): Float {
        for (k in keys) if (k.kind == Kind.LETTER && k.view.width > 0) return k.view.width / 2f
        return theme.dp(20).toFloat()
    }

    /** Draw the glide trail on top of the keys, in the keyboard's accent colour, fading toward the tail. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val n = gesturePoints.size
        if (n < 2) return
        val maxWidth = keyRadiusPx() * 0.5f
        val minWidth = maxWidth * 0.3f
        for (i in 1 until n) {
            val t = i.toFloat() / (n - 1) // 0 at the tail, 1 at the finger
            trailPaint.strokeWidth = minWidth + (maxWidth - minWidth) * t
            trailPaint.alpha = (40 + 190 * t).toInt().coerceIn(0, 255)
            val a = gesturePoints[i - 1]
            val b = gesturePoints[i]
            canvas.drawLine(a.x, a.y, b.x, b.y, trailPaint)
        }
    }

    /** Exact hit on a special key, else the nearest proximity (character-grid) key. */
    private fun resolveKey(x: Float, y: Float): TouchKey? {
        for (k in keys) {
            if (k.proximity || k.view.visibility != View.VISIBLE || k.view.width == 0) continue
            rectInSelf(k.view, tmpRect)
            if (tmpRect.contains(x.toInt(), y.toInt())) return k
        }
        return nearestProximityKey(x, y)
    }

    /** The closest proximity key by squared distance to its rectangle (0 when the point is inside). */
    private fun nearestProximityKey(x: Float, y: Float): TouchKey? {
        var best: TouchKey? = null
        var bestDist = Float.MAX_VALUE
        for (k in keys) {
            if (!k.proximity) continue
            val v = k.view
            if (v.visibility != View.VISIBLE || v.width == 0) continue
            rectInSelf(v, tmpRect)
            val dx = when {
                x < tmpRect.left -> tmpRect.left - x
                x > tmpRect.right -> x - tmpRect.right
                else -> 0f
            }
            val dy = when {
                y < tmpRect.top -> tmpRect.top - y
                y > tmpRect.bottom -> y - tmpRect.bottom
                else -> 0f
            }
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = k
            }
        }
        return best
    }

    /** Bounds of a descendant expressed in this keyboard view's own coordinate space. */
    private fun rectInSelf(child: View, out: Rect) {
        out.set(0, 0, child.width, child.height)
        offsetDescendantRectToMyCoords(child, out)
    }

    private fun typeLetter(key: TouchKey, x: Float, y: Float) {
        val letter = key.baseLabel ?: return
        val out = if (mode == Mode.LETTERS && shiftState != ShiftState.OFF) letter.uppercase() else letter
        emitText(out, touchInKeyUnits(key, x, y))
        // A single shift only capitalizes one character (caps-lock stays on).
        if (mode == Mode.LETTERS && shiftState == ShiftState.SHIFTED) setShiftState(ShiftState.OFF)
    }

    /**
     * Convert a raw pixel tap on letter [key] into KeyGeometry key-unit space: the key's canonical grid
     * position plus the sub-key offset of the touch from the key centre, scaled by the key size. This is
     * the per-tap coordinate the engine's spatial autocorrect model consumes. Null if the layout isn't
     * measured yet or the key isn't a known letter.
     */
    private fun touchInKeyUnits(key: TouchKey, x: Float, y: Float): PointF? {
        val ch = key.baseLabel?.firstOrNull() ?: return null
        val base = KeyGeometry.posOf(ch) ?: return null
        val v = key.view
        if (v.width <= 0 || v.height <= 0) return null
        rectInSelf(v, tmpRect)
        return PointF(
            base.x + (x - tmpRect.exactCenterX()) / v.width,
            base.y + (y - tmpRect.exactCenterY()) / v.height,
        )
    }

    /** Route typed text to the translator / picker search box when active, otherwise to the field. */
    private fun emitText(text: CharSequence, touch: PointF? = null) {
        when {
            translateActive -> translationBar?.onSourceChar(text)
            searchSink != null -> searchSink?.invoke(text)
            else -> actionListener?.onText(text, touch)
        }
    }

    private fun emitDelete() {
        when {
            translateActive -> translationBar?.onSourceDelete()
            searchDeleteSink != null -> searchDeleteSink?.invoke()
            else -> actionListener?.onDelete()
        }
    }

    // ---- Shift state (no rebuild, so it's safe while fingers are down) ------

    private fun setShiftState(s: ShiftState) {
        if (shiftState == s) return
        shiftState = s
        updateShiftVisuals()
    }

    private fun toggleShiftTap() {
        setShiftState(if (shiftState == ShiftState.OFF) ShiftState.SHIFTED else ShiftState.OFF)
    }

    /** Re-case the letter keys and restyle the shift key in place (only meaningful in letter mode). */
    private fun updateShiftVisuals() {
        if (mode != Mode.LETTERS) return
        val upper = shiftState != ShiftState.OFF
        for (k in keys) {
            val base = k.baseLabel
            if (k.kind == Kind.LETTER && base != null) {
                (k.view as TextView).text = if (upper) base.uppercase() else base
            }
        }
        shiftKeyRef?.let { sk ->
            val tv = sk.view as TextView
            tv.text = shiftLabel()
            val off = shiftState == ShiftState.OFF
            tv.background = if (off) theme.specialSurface() else theme.accentSurface()
            tv.setTextColor(if (off) theme.textSecondary else theme.onAccent)
        }
    }

    private fun shiftLabel(): String = when (shiftState) {
        ShiftState.OFF -> "⇧"
        ShiftState.SHIFTED -> "⬆"
        ShiftState.CAPS_LOCK -> "⇪"
    }

    // ---- Row builders ------------------------------------------------------

    /** Frosted pill strip across the top. Tapping a chip commits its word via [onSuggestionPicked]. */
    private fun buildSuggestionStrip(): LinearLayout {
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(44))
            val v = theme.dp(4)
            setPadding(theme.dp(2), v, theme.dp(2), v)
        }
        suggestionStrip = strip
        renderChips(strip)
        return strip
    }

    /**
     * Replace the chips in the suggestion strip with [words] (engine predictions/corrections) without
     * rebuilding the whole keyboard, so it can update on every keystroke cheaply. When [addWord] is
     * true the final slot becomes a "+" button that adds the in-progress word to the dictionary.
     *
     * This keeps the latest words ready but does *not* close the tools row: while [toolsMode] is on
     * (the user opened it via the strip's left toggle) the strip keeps showing the tools, and these
     * words only surface once they toggle back. Field entry resets the toggle via [resetTools].
     */
    fun setSuggestions(words: List<String>, addWord: Boolean = false) {
        currentSuggestions = words
        currentAddWord = addWord
        rerenderStrip()
    }

    /**
     * Toggle the tools row (fix / translate / clipboard) on/off in place of the suggestions. Driven by
     * the strip's far-left [toggleChip]: tap once to reveal the tools, tap again to return to the
     * autocorrect suggestions. Tools mode persists across keystrokes (it isn't auto-dismissed) so it
     * stays put while the user reaches for a tool.
     */
    private fun toggleTools() {
        toolsMode = !toolsMode
        rerenderStrip()
    }

    /** Reset the tools toggle back to suggestions. Called when a new input field starts. */
    fun resetTools() { toolsMode = false }

    /** Rebuild the chips/tools in the existing strip without rebuilding the whole keyboard. */
    private fun rerenderStrip() {
        val strip = suggestionStrip ?: return
        keys.removeAll(chipKeys.toSet())
        chipKeys.clear()
        strip.removeAllViews()
        renderChips(strip)
    }

    /**
     * Fill [strip]: a persistent far-left [toggleChip] that flips modes, then either the tools row
     * (fix / translate / clipboard) when [toolsMode], or [currentSuggestions] (+ the "+" chip).
     */
    private fun renderChips(strip: LinearLayout) {
        strip.addView(toggleChip()) // far-left: switch between suggestions and the tools row
        if (toolsMode) {
            strip.addView(toolIconChip(R.drawable.ic_tool_fix) { actionListener?.onFixText() })   // fix the typed text
            strip.addView(toolChip("文A", active = translateActive) { toggleTranslateMode() })      // translate (on/off)
            strip.addView(toolIconChip(R.drawable.ic_tool_clipboard) { openClipboard() })         // clipboard history
            return
        }
        val wordSlots = if (currentAddWord) 2 else 3
        currentSuggestions.take(wordSlots).forEach { strip.addView(suggestionChip(it)) }
        if (currentAddWord) strip.addView(addWordChip())
    }

    /**
     * The strip's far-left mode toggle: a narrow, fixed-width icon (so the suggestions keep most of the
     * width). Tapping it flips between the autocorrect suggestions and the tools row; it takes the
     * accent surface while the tools are showing so its on/off state reads. Hit-tested like any chip.
     */
    private fun toggleChip(): ImageView {
        val on = toolsMode
        val chip = ImageView(context).apply {
            layoutParams = LayoutParams(theme.dp(40), LayoutParams.MATCH_PARENT).also {
                val m = theme.dp(3)
                it.setMargins(m, 0, m, 0)
            }
            val pad = theme.dp(9)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_tools)
            imageTintList = ColorStateList.valueOf(if (on) theme.onAccent else theme.textSecondary)
            background = if (on) theme.accentSurface() else theme.pillChip()
        }
        val tk = TouchKey(chip, proximity = false, Kind.TAP, action = { toggleTools() })
        keys.add(tk)
        chipKeys.add(tk)
        return chip
    }

    /**
     * A tool button in the strip (fix / translate / clipboard), hit-tested like a suggestion chip.
     * When [active] (the translate toggle is on) it takes the accent surface so its on/off state reads.
     */
    private fun toolChip(label: String, active: Boolean = false, fire: () -> Unit): TextView {
        val chip = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).also {
                val m = theme.dp(3)
                it.setMargins(m, 0, m, 0)
            }
            text = label
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 18f
            setTextColor(if (active) theme.onAccent else theme.textSecondary)
            background = if (active) theme.accentSurface() else theme.pillChip()
        }
        val tk = TouchKey(chip, proximity = false, Kind.TAP, action = fire)
        keys.add(tk)
        chipKeys.add(tk)
        return chip
    }

    /**
     * A tool button that shows a minimalistic monochrome line icon (fix / clipboard) instead of an
     * emoji glyph. The vector is tinted to the theme's text colour so it sits flat alongside the
     * translate glyph, hit-tested like any other chip.
     */
    private fun toolIconChip(@DrawableRes iconRes: Int, fire: () -> Unit): ImageView {
        val chip = ImageView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).also {
                val m = theme.dp(3)
                it.setMargins(m, 0, m, 0)
            }
            val pad = theme.dp(9)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(iconRes)
            imageTintList = ColorStateList.valueOf(theme.textSecondary)
            background = theme.pillChip()
        }
        val tk = TouchKey(chip, proximity = false, Kind.TAP, action = fire)
        keys.add(tk)
        chipKeys.add(tk)
        return chip
    }

    /**
     * Engine-driven sentence-start capitalization. Sets a one-shot SHIFTED state (never overrides an
     * explicit caps-lock, and only in letter mode). Cheap in-place relabel — no rebuild.
     */
    fun setAutoShift(on: Boolean) {
        if (mode != Mode.LETTERS || shiftState == ShiftState.CAPS_LOCK) return
        setShiftState(if (on) ShiftState.SHIFTED else ShiftState.OFF)
    }

    private fun suggestionChip(word: String): TextView {
        val chip = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).also {
                val m = theme.dp(3)
                it.setMargins(m, 0, m, 0)
            }
            text = word
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.textSecondary)
            background = theme.pillChip()
        }
        val tk = TouchKey(chip, proximity = false, Kind.TAP, action = { actionListener?.onSuggestionPicked(word) })
        keys.add(tk)
        chipKeys.add(tk)
        return chip
    }

    /** The accent-coloured "+" chip shown in place of the third suggestion for an unrecognised word. */
    private fun addWordChip(): TextView {
        val chip = TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).also {
                val m = theme.dp(3)
                it.setMargins(m, 0, m, 0)
            }
            text = "+ Add"
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 14f
            setTextColor(theme.onAccent)
            background = theme.accentSurface()
        }
        val tk = TouchKey(chip, proximity = false, Kind.TAP, action = { actionListener?.onAddWord() })
        keys.add(tk)
        chipKeys.add(tk)
        return chip
    }

    private fun buildCharRow(keyLabels: List<String>, sidePadding: Float): LinearLayout {
        val row = newRow()
        if (sidePadding > 0f) row.addView(spacer(sidePadding))
        keyLabels.forEach { row.addView(charKey(it)) }
        if (sidePadding > 0f) row.addView(spacer(sidePadding))
        return row
    }

    /** Third row: a special key on the left, characters in the middle, delete on the right. */
    private fun buildThirdRow(keyLabels: List<String>): LinearLayout {
        val row = newRow()
        when (mode) {
            Mode.LETTERS -> row.addView(shiftKey())
            Mode.SYMBOLS -> row.addView(functionKey("=\\<", 1.5f) { mode = Mode.SYMBOLS2; buildKeyboard() })
            Mode.SYMBOLS2 -> row.addView(functionKey("?123", 1.5f) { mode = Mode.SYMBOLS; buildKeyboard() })
        }
        keyLabels.forEach { row.addView(charKey(it)) }
        row.addView(deleteKey())
        return row
    }

    /** Bottom row: layer toggle (hold = emoji/GIF/emoticon picker), comma, space, period, enter. */
    private fun buildBottomRow(): LinearLayout {
        val row = newRow()
        if (mode == Mode.LETTERS) {
            // Tap = symbols layer; hold = open the emoji / GIF / emoticon picker.
            row.addView(holdKey("?123", 1.5f, KeyStyle.SPECIAL,
                tap = { mode = Mode.SYMBOLS; buildKeyboard() },
                hold = { openPicker() }))
        } else {
            row.addView(functionKey("ABC", 1.5f) {
                mode = Mode.LETTERS
                shiftState = ShiftState.OFF
                buildKeyboard()
            })
        }
        row.addView(commaKey())
        row.addView(spaceKey())
        row.addView(charKey("."))

        row.addView(tapKey("⏎", 1.5f, KeyStyle.ACCENT) {
            when {
                translateActive -> translationBar?.onEnter() // send the current translation
                searchSink == null -> actionListener?.onEnter()
                // Searching in the picker: Enter drops the search keyboard (keeping the query +
                // results) so the full-height grid is revealed — same as the down-arrow / back.
                else -> emojiPanel?.exitSearch()
            }
        })
        return row
    }

    // ---- Individual keys ---------------------------------------------------

    private fun charKey(label: String): TextView {
        val isLetter = mode == Mode.LETTERS && label.length == 1 && label[0].isLetter()
        return if (isLetter) {
            val display = if (shiftState != ShiftState.OFF) label.uppercase() else label
            val key = baseKey(display, 1f, KeyStyle.CHAR)
            keys.add(TouchKey(key, proximity = true, Kind.LETTER, baseLabel = label))
            key
        } else {
            tapKey(label, 1f, KeyStyle.CHAR) { emitText(label) }
        }
    }

    private fun functionKey(label: String, weight: Float, onClick: () -> Unit): TextView =
        tapKey(label, weight, KeyStyle.SPECIAL, onClick)

    /** A proximity-resolved key that fires its action the instant it's pressed. */
    private fun tapKey(label: String, weight: Float, style: KeyStyle, fire: () -> Unit): TextView {
        val key = baseKey(label, weight, style)
        keys.add(TouchKey(key, proximity = true, Kind.TAP, action = fire))
        return key
    }

    /** A proximity-resolved key with a tap action and a separate long-press [hold] action. */
    private fun holdKey(label: String, weight: Float, style: KeyStyle, tap: () -> Unit, hold: () -> Unit): TextView {
        val key = baseKey(label, weight, style)
        keys.add(TouchKey(key, proximity = true, Kind.HOLD, action = tap, holdAction = hold))
        return key
    }

    /** Shift: tap toggles, long-press locks caps. Exact-hit (excluded from proximity). */
    private fun shiftKey(): TextView {
        val style = if (shiftState == ShiftState.OFF) KeyStyle.SPECIAL else KeyStyle.ACCENT
        val key = baseKey(shiftLabel(), 1.5f, style)
        val tk = TouchKey(key, proximity = false, Kind.SHIFT)
        keys.add(tk)
        shiftKeyRef = tk
        return key
    }

    /** Delete key with auto-repeat while held down. Exact-hit (excluded from proximity). */
    private fun deleteKey(): TextView {
        val key = baseKey("⌫", 1.5f, KeyStyle.SPECIAL)
        keys.add(TouchKey(key, proximity = false, Kind.DELETE))
        return key
    }

    /**
     * Comma key with a press-and-hold gesture: a quick tap types "," while holding it down past
     * [LONG_PRESS_MS] shows a floating "—" preview and types the em dash when the finger lifts. The
     * comma is *not* replaced — it still types a comma on a normal tap. Exact-hit, and it commits on
     * release so we know whether a hold happened.
     */
    private fun commaKey(): TextView {
        val key = baseKey(",", 1f, KeyStyle.CHAR)
        keys.add(TouchKey(key, proximity = false, Kind.COMMA))
        return key
    }

    /**
     * The spacebar. A quick tap types a space; a long-press starts voice typing. While dictating, the
     * "space" label is hidden and a [WaveformView] (drawn in the keyboard's accent colour) fills the
     * key — and a tap pauses/resumes instead of typing a space. The container itself is the touch
     * target so hit-testing and the press animation behave exactly like any other key.
     */
    private fun spaceKey(): View {
        val container = FrameLayout(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 5f).also {
                val m = theme.dp(3)
                it.setMargins(m, m, m, m)
            }
            background = theme.keySurface()
            isClickable = false
            isFocusable = false
        }
        val label = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            )
            text = "space"
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 16f
            setTextColor(theme.textSecondary)
        }
        val wave = WaveformView(context, theme).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ).also {
                val m = theme.dp(6)
                it.setMargins(m, m, m, m)
            }
            visibility = View.GONE
        }
        container.addView(label)
        container.addView(wave)
        spaceLabelRef = label
        waveformViewRef = wave
        val tk = TouchKey(container, proximity = true, Kind.SPACE)
        keys.add(tk)
        spaceKeyRef = tk
        return container
    }

    /** Show a floating accent-coloured bubble centred above [anchor] containing [text]. */
    private fun showKeyPreview(anchor: View, text: String) {
        dismissKeyPreview()
        val bubble = TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 26f
            setTextColor(theme.onAccent)
            background = theme.accentSurface()
            val padH = theme.dp(20)
            val padV = theme.dp(10)
            setPadding(padH, padV, padH, padV)
        }
        bubble.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popup = PopupWindow(bubble, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isClippingEnabled = false
            isTouchable = false
            isFocusable = false
        }
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + (anchor.width - bubble.measuredWidth) / 2
        val y = loc[1] - bubble.measuredHeight - theme.dp(6)
        runCatching { popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y) }
        keyPreview = popup
    }

    private fun dismissKeyPreview() {
        keyPreview?.let { runCatching { it.dismiss() } }
        keyPreview = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Ask for an insets pass now that we're in the window, so the bottom nav gap is computed
        // before the keyboard is first drawn rather than after the first interaction.
        ViewCompat.requestApplyInsets(this)
    }

    override fun onDetachedFromWindow() {
        if (voiceActive) exitVoiceMode()
        cancelGesture()
        clearAllPointers(cancelled = true)
        dismissKeyPreview()
        translationBar?.release()
        translationManager?.close()
        super.onDetachedFromWindow()
    }

    /** Factory for the look shared by every key. Keys never consume touch themselves (we hit-test). */
    private fun baseKey(label: String, weight: Float, style: KeyStyle): TextView {
        return TextView(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight).also {
                val m = theme.dp(3)
                it.setMargins(m, m, m, m)
            }
            text = label
            gravity = Gravity.CENTER
            typeface = theme.keyTypeface
            textSize = 16f
            letterSpacing = 0.01f
            setTextColor(
                when (style) {
                    KeyStyle.CHAR -> theme.textPrimary
                    KeyStyle.SPECIAL -> theme.textSecondary
                    KeyStyle.ACCENT -> theme.onAccent
                }
            )
            background = when (style) {
                KeyStyle.CHAR -> theme.keySurface()
                KeyStyle.SPECIAL -> theme.specialSurface()
                KeyStyle.ACCENT -> theme.accentSurface()
            }
            isClickable = false
            isFocusable = false
        }
    }

    // ---- Motion ------------------------------------------------------------

    private fun pressDown(v: View) = animateScale(v, PRESS_SCALE, decelerate, 30L)
    private fun release(v: View) = animateScale(v, 1f, fastOutSlowIn, 90L)

    private fun animateScale(v: View, target: Float, interpolator: Interpolator, duration: Long) {
        v.animate().scaleX(target).scaleY(target)
            .setDuration(duration)
            .setInterpolator(interpolator)
            .start()
    }

    // ---- Helpers -----------------------------------------------------------

    private fun newRow(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(54))
        }
    }

    private fun spacer(weight: Float): View {
        return View(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, weight)
        }
    }

    // ---- Emoji / GIF / emoticon picker -------------------------------------

    private fun ensurePanel(): EmojiPanel {
        emojiPanel?.let { return it }
        val panel = EmojiPanel(context, theme, panelHost)
        panel.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(PANEL_BROWSE_DP))
        addView(panel, 0) // index 0 = top, so search results sit above the revealed keys
        emojiPanel = panel
        return panel
    }

    private fun openPicker() {
        if (voiceActive) exitVoiceMode()
        cancelGesture()
        pickerOpen = true
        clearAllPointers(cancelled = true)
        val panel = ensurePanel()
        setPanelHeight(panel, PANEL_BROWSE_DP)
        panel.visibility = View.VISIBLE
        stripHolder.visibility = View.GONE
        keyRowsContainer.visibility = View.GONE
        searchSink = null
        searchDeleteSink = null
        panel.applyTheme() // the cached panel may predate the current app's colours
        panel.onShown()
    }

    private fun closePicker() {
        pickerOpen = false
        searchSink = null
        searchDeleteSink = null
        emojiPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        stripHolder.visibility = View.VISIBLE
        keyRowsContainer.visibility = View.VISIBLE
    }

    /**
     * Handle the nav-bar "down-arrow" / back press. While searching inside the picker it only dismisses
     * the search *keyboard* — collapsing back to the full browse panel (keeping the typed query and its
     * results) rather than hiding the whole IME. While translating it leaves translate mode. Returns
     * true when it consumed the press; the service then skips the normal hide. Otherwise false, so the
     * keyboard hides as usual.
     */
    fun onBackPressed(): Boolean {
        if (pickerOpen && searchSink != null) {
            emojiPanel?.exitSearch()
            return true
        }
        // Clipboard opened on top of the translator: back returns to the translate screen.
        if (pickerOpen && translateActive) {
            closePicker()
            return true
        }
        if (translateActive) {
            exitTranslateMode()
            return true
        }
        return false
    }

    // ---- Translate mode ----------------------------------------------------

    private fun ensureTranslationBar(): TranslationBar {
        translationBar?.let { return it }
        val manager = translationManager ?: TranslationManager().also { translationManager = it }
        val bar = TranslationBar(context, theme, manager, translateHost)
        addView(bar, indexOfChild(stripHolder) + 1) // directly below the tools strip (which stays shown)
        translationBar = bar
        return bar
    }

    /** The translate tool chip is an on/off toggle for the translation screen. */
    private fun toggleTranslateMode() {
        if (translateActive) exitTranslateMode() else enterTranslateMode()
    }

    /**
     * Enter translate mode: drop the two-bar translator in below the tools strip (which stays visible,
     * so the translate toggle / clipboard remain reachable) and route typing into its source field.
     */
    private fun enterTranslateMode() {
        if (translateActive) return
        if (voiceActive) exitVoiceMode()
        if (pickerOpen) closePicker()
        cancelGesture()
        clearAllPointers(cancelled = true)
        translateActive = true
        // Typed keys feed the translator's source field; glide typing would bypass it, so suspend it.
        gestureEnabledBeforeTranslate = gestureTypingEnabled
        setGestureTypingEnabled(false)
        val bar = ensureTranslationBar()
        bar.applyTheme()
        bar.reset()
        bar.visibility = View.VISIBLE
        // Keep the tools row visible (it's the toggle's home) and re-render so the chip shows as "on".
        toolsMode = true
        currentSuggestions = emptyList()
        currentAddWord = false
        rerenderStrip()
    }

    private fun exitTranslateMode() {
        if (!translateActive) return
        translateActive = false
        translationBar?.dismissPopups()
        translationBar?.visibility = View.GONE
        setGestureTypingEnabled(gestureEnabledBeforeTranslate)
        if (toolsMode) rerenderStrip() // un-highlight the translate chip
    }

    private val translateHost = object : TranslationBar.Host {
        override fun onSendTranslation(text: String) {
            actionListener?.onPickText(text)
            exitTranslateMode()
        }

        override fun onCloseTranslate() {
            exitTranslateMode()
        }
    }

    // ---- Clipboard panel ---------------------------------------------------

    private fun ensureClipboardPanel(): ClipboardPanel {
        clipboardPanel?.let { return it }
        val panel = ClipboardPanel(context, theme, clipboardHost)
        panel.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, theme.dp(PANEL_BROWSE_DP))
        addView(panel, 0) // index 0 = top, above the (hidden) keys
        clipboardPanel = panel
        return panel
    }

    /** Open the clipboard history panel (reuses the picker's hide-keys-and-strip machinery). */
    private fun openClipboard() {
        if (voiceActive) exitVoiceMode()
        cancelGesture()
        pickerOpen = true
        clearAllPointers(cancelled = true)
        val panel = ensureClipboardPanel()
        panel.visibility = View.VISIBLE
        emojiPanel?.visibility = View.GONE
        stripHolder.visibility = View.GONE
        keyRowsContainer.visibility = View.GONE
        searchSink = null
        searchDeleteSink = null
        panel.applyTheme() // the cached panel may predate the current app's colours
        panel.onShown()
    }

    private val clipboardHost = object : ClipboardPanel.Host {
        override fun onPickText(text: String) {
            // While translating, the clipboard pastes into the "translating from" field instead of the
            // real text field, so you can translate text you copied from elsewhere.
            if (translateActive) translationBar?.onSourceChar(text) else actionListener?.onPickText(text)
            closePicker()
        }

        override fun onPickImage(file: File) {
            actionListener?.onPickImage(file)
            closePicker()
        }

        override fun onClosePicker() {
            closePicker()
        }
    }

    private fun setPanelHeight(panel: EmojiPanel, dp: Int) {
        val lp = panel.layoutParams
        lp.height = theme.dp(dp)
        panel.layoutParams = lp
    }

    private val panelHost = object : EmojiPanel.Host {
        override fun onPickText(text: String) {
            actionListener?.onPickText(text)
        }

        override fun onPickGif(gif: GifResult) {
            actionListener?.onPickGif(gif)
        }

        override fun onBackspace() {
            actionListener?.onDelete()
        }

        override fun onClosePicker() {
            closePicker()
        }

        override fun onSearchActive(active: Boolean) {
            val panel = emojiPanel ?: return
            if (active) {
                // Reveal the keys (so the user can type a query) and route typing into the panel.
                keyRowsContainer.visibility = View.VISIBLE
                setPanelHeight(panel, PANEL_SEARCH_DP)
                searchSink = { panel.onSearchChar(it) }
                searchDeleteSink = { panel.onSearchDelete() }
            } else {
                keyRowsContainer.visibility = View.GONE
                setPanelHeight(panel, PANEL_BROWSE_DP)
                searchSink = null
                searchDeleteSink = null
            }
        }
    }

    // ---- Voice-to-type -----------------------------------------------------

    private fun enterVoiceMode() {
        if (voiceActive) return
        voiceActive = true
        cancelGesture()
        clearAllPointers(cancelled = true) // release the holding finger; the waveform takes over
        showVoiceUi(true)
        waveformViewRef?.start()
        actionListener?.onVoiceStart()
    }

    /** Leave voice mode and tell the service to stop the recognizer (user-initiated). */
    private fun exitVoiceMode() {
        if (!voiceActive) return
        resetVoiceUi()
        actionListener?.onVoiceStop()
    }

    /** Leave voice mode without a callback — for the service to call when *it* ended dictation. */
    fun cancelVoiceMode() {
        if (voiceActive) resetVoiceUi()
    }

    private fun resetVoiceUi() {
        voiceActive = false
        waveformViewRef?.stop()
        showVoiceUi(false)
    }

    private fun showVoiceUi(on: Boolean) {
        spaceLabelRef?.visibility = if (on) View.GONE else View.VISIBLE
        waveformViewRef?.visibility = if (on) View.VISIBLE else View.GONE
    }

    fun isVoiceActive(): Boolean = voiceActive

    /** Feed a microphone level (RMS dB) from the recognizer to the waveform. */
    fun updateVoiceLevel(rmsDb: Float) {
        waveformViewRef?.submitLevel(rmsDb)
    }

    /** Reflect the recognizer's paused state in the visualizer (service is the source of truth). */
    fun setVoicePaused(paused: Boolean) {
        waveformViewRef?.setPaused(paused)
    }

    /** Called by the service when a new text field gains focus. */
    fun resetState() {
        closePicker()
        exitTranslateMode()
        mode = Mode.LETTERS
        shiftState = ShiftState.OFF
        buildKeyboard()
    }

    private companion object {
        const val PRESS_SCALE = 0.93f
        const val LONG_PRESS_MS = 300L
        const val GESTURE_START_DP = 14 // finger travel (dp) before a key press becomes a glide
        const val DELETE_INITIAL_MS = 400L
        const val DELETE_REPEAT_MS = 50L
        const val WORD_DELETE_AFTER = 18    // char repeats before a held delete jumps to whole words
        const val WORD_DELETE_REPEAT_MS = 160L
        const val CURSOR_START_DP = 12      // sideways travel on the spacebar before cursor mode engages
        const val CURSOR_STEP_DP = 13       // travel per one-character cursor step
        const val EM_DASH = "—" // —
        const val PANEL_BROWSE_DP = 280 // picker height when browsing (no keys shown)
        const val PANEL_SEARCH_DP = 190 // picker results height when keys are revealed for search
    }
}
