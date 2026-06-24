package com.example.cask.voice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.example.cask.CaskTheme
import kotlin.math.sin

/**
 * The audio-level visualizer that replaces the "space" label while the user is dictating. It is a row
 * of mirrored bars that scroll right-to-left as new microphone levels arrive (fed by
 * [submitLevel] from the speech recognizer's RMS callback), drawn in the keyboard's fetched brand
 * [CaskTheme.accent] colour so it always matches the rest of the keyboard.
 *
 * It self-animates on the [android.view.Choreographer] frame clock between RMS samples (which only
 * land a few times a second) so motion stays smooth, and shows a calm, dim flat line while [paused].
 * Sized to sit entirely inside the spacebar's bounds — nothing else on the keyboard changes.
 */
@SuppressLint("ViewConstructor")
class WaveformView(context: Context, private val theme: CaskTheme) : View(context) {

    private val barCount = 26
    private val displayed = FloatArray(barCount)   // smoothed heights actually drawn (0..1)
    private val targets = FloatArray(barCount)     // heights we ease toward
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barRect = RectF()

    private var running = false
    private var paused = false
    private var phase = 0f

    private val frame = object : Runnable {
        override fun run() {
            step()
            invalidate()
            if (running) postOnAnimation(this)
        }
    }

    /** Begin animating from a calm baseline. */
    fun start() {
        paused = false
        targets.fill(0f)
        displayed.fill(0f)
        if (!running) {
            running = true
            postOnAnimation(frame)
        }
    }

    /** Stop animating and clear the bars. */
    fun stop() {
        running = false
        removeCallbacks(frame)
        targets.fill(0f)
        displayed.fill(0f)
        invalidate()
    }

    /** Pause/resume the reaction to audio; paused settles into a faint, steady line. */
    fun setPaused(value: Boolean) {
        paused = value
    }

    /**
     * Push one microphone level from `RecognitionListener.onRmsChanged` (a rough dB value, typically
     * about -2..12). Scrolls the history left and appends the new normalized level on the right.
     */
    fun submitLevel(rmsDb: Float) {
        if (paused || !running) return
        val norm = ((rmsDb + 2f) / 14f).coerceIn(0f, 1f)
        System.arraycopy(targets, 1, targets, 0, barCount - 1)
        targets[barCount - 1] = norm
    }

    private fun step() {
        phase += 0.16f
        for (i in 0 until barCount) {
            val base = if (paused) 0f else targets[i]
            // A whisper of idle motion so an active-but-quiet mic still looks alive.
            val idle = if (paused) 0f else 0.04f * (0.5f + 0.5f * sin(phase + i * 0.45f))
            val target = (base + idle).coerceIn(0f, 1f)
            displayed[i] += (target - displayed[i]) * if (paused) 0.18f else 0.4f
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val slot = w / barCount
        val barWidth = slot * 0.5f
        val radius = barWidth / 2f
        val centerY = h / 2f
        // Leave a little breathing room top/bottom; a paused line is short and dim.
        val maxBar = h * if (paused) 0.10f else 0.74f
        val minBar = theme.dp(2f).toFloat()

        paint.color = theme.accent
        for (i in 0 until barCount) {
            val cx = slot * i + slot / 2f
            val half = (minBar + displayed[i] * maxBar).coerceAtLeast(minBar) / 2f
            // Fade the bars toward the edges so the waveform reads as centered in the spacebar.
            val edge = 1f - kotlin.math.abs(i - (barCount - 1) / 2f) / (barCount / 2f)
            paint.alpha = (255 * (0.35f + 0.65f * edge) * (if (paused) 0.5f else 1f)).toInt().coerceIn(0, 255)
            barRect.set(cx - barWidth / 2f, centerY - half, cx + barWidth / 2f, centerY + half)
            canvas.drawRoundRect(barRect, radius, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
