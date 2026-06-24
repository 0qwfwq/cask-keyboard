package com.example.cask

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Turns a single *source colour* (the brand colour of the app the user is typing in) plus a
 * light/dark flag into the whole keyboard palette: background gradient, key surfaces, accent and
 * text colours. Call [recolor] whenever the foreground app changes and rebuild the keys.
 *
 * Surfaces are built in code (rounded rect + hairline border, no shadow) so the colours can be
 * recomputed at runtime. The keyboard is fully opaque — it no longer shows the app behind it.
 */
class CaskTheme(private val context: Context) {

    private val density = context.resources.displayMetrics.density
    private val pulseBlue = ContextCompat.getColor(context, R.color.pulse_blue)

    /** True when the current app warrants a light keyboard (light theme background). */
    var isLight = false
        private set

    // ---- Computed palette (set by [recolor]) -------------------------------

    var accent = pulseBlue; private set
    private var accentPressed = pulseBlue
    var onAccent = Color.WHITE; private set

    private var bgTop = Color.BLACK
    private var bgBottom = Color.BLACK

    private var charFill = 0
    private var specialFill = 0
    private var pillFill = 0
    private var hairline = 0

    var textPrimary = Color.WHITE; private set
    var textSecondary = Color.WHITE; private set

    init {
        recolor(pulseBlue, light = false)
    }

    /** Recompute every colour from an app's brand [appAccent] and a light/dark choice. */
    fun recolor(appAccent: Int, light: Boolean) {
        isLight = light
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(appAccent, hsl)
        // Near-greyscale icons give no usable hue — fall back to the Cask blue.
        val source = if (hsl[1] < 0.12f) pulseBlue else appAccent
        ColorUtils.colorToHSL(source, hsl)
        val hue = hsl[0]
        val sat = hsl[1]

        accent = ColorUtils.HSLToColor(
            floatArrayOf(hue, sat.coerceIn(0.5f, 0.95f), if (light) 0.50f else 0.60f)
        )
        accentPressed = ColorUtils.blendARGB(accent, if (light) Color.BLACK else Color.WHITE, 0.16f)
        onAccent = if (ColorUtils.calculateLuminance(accent) > 0.5) DARK_INK else Color.WHITE

        if (light) {
            bgTop = ColorUtils.HSLToColor(floatArrayOf(hue, 0.12f, 0.90f))
            bgBottom = ColorUtils.HSLToColor(floatArrayOf(hue, 0.14f, 0.84f))
            charFill = alpha(tintedWhite(0.04f), 0.98f)
            specialFill = ColorUtils.HSLToColor(floatArrayOf(hue, 0.12f, 0.80f)) // light grey, opaque
            pillFill = alpha(tintedWhite(0.04f), 0.85f)
            hairline = 0x1A000000          // black @ 10%
            textPrimary = 0xDE000000.toInt() // ~87% black
            textSecondary = 0x99000000.toInt() // 60% black
        } else {
            bgTop = ColorUtils.HSLToColor(floatArrayOf(hue, 0.32f, 0.12f))
            bgBottom = ColorUtils.HSLToColor(floatArrayOf(hue, 0.34f, 0.07f))
            charFill = alpha(tintedWhite(0.14f), 0.15f)
            specialFill = alpha(tintedWhite(0.14f), 0.24f)
            pillFill = alpha(tintedWhite(0.14f), 0.12f)
            hairline = 0x14FFFFFF          // white @ 8%
            textPrimary = Color.WHITE
            textSecondary = 0xB3FFFFFF.toInt() // 70% white
        }
    }

    /** White tinted [amount] toward the accent (the glass "bleed"). */
    private fun tintedWhite(amount: Float) = ColorUtils.blendARGB(Color.WHITE, accent, amount)

    private fun alpha(color: Int, opacity: Float) =
        ColorUtils.setAlphaComponent(color, (opacity * 255).roundToInt())

    private val pressTint get() = if (isLight) Color.BLACK else Color.WHITE

    // ---- Surfaces ----------------------------------------------------------

    fun keyboardBackground(): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(bgTop, bgBottom))

    fun keySurface(): StateListDrawable = surface(charFill, 16f)
    fun specialSurface(): StateListDrawable = surface(specialFill, 16f)
    fun pillChip(): StateListDrawable = surface(pillFill, 50f)

    fun accentSurface(): StateListDrawable = stateful(accent, accentPressed, 16f, border = false)

    /** Round (oval) surface for the small translate language-select / swap buttons. */
    fun circleSurface(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), oval(ColorUtils.blendARGB(specialFill, pressTint, 0.16f)))
            addState(intArrayOf(), oval(specialFill))
        }

    /** Round (oval) accent surface — the translate-mode "send" circle. */
    fun circleAccent(): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ovalFill(accentPressed, border = false))
            addState(intArrayOf(), ovalFill(accent, border = false))
        }

    /**
     * A readable colour for word #[index] when colour-coding a translation against its source, so a
     * source word and its aligned translation share a hue. Hues are spread around the wheel and their
     * lightness/saturation are tuned for the current light/dark keyboard so the text stays legible on
     * the field's surface.
     */
    fun wordColor(index: Int): Int {
        val hue = WORD_HUES[((index % WORD_HUES.size) + WORD_HUES.size) % WORD_HUES.size]
        return ColorUtils.HSLToColor(floatArrayOf(hue, 0.72f, if (isLight) 0.40f else 0.68f))
    }

    private fun surface(fill: Int, radiusDp: Float): StateListDrawable =
        stateful(fill, ColorUtils.blendARGB(fill, pressTint, 0.16f), radiusDp, border = true)

    private fun stateful(normal: Int, pressed: Int, radiusDp: Float, border: Boolean) =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundedRect(pressed, radiusDp, border))
            addState(intArrayOf(), roundedRect(normal, radiusDp, border))
        }

    private fun roundedRect(fill: Int, radiusDp: Float, border: Boolean) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (border) setStroke(hairlinePx, hairline)
        }

    private fun oval(fill: Int) = ovalFill(fill, border = true)

    private fun ovalFill(fill: Int, border: Boolean) =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (border) setStroke(hairlinePx, hairline)
        }

    // ---- Type --------------------------------------------------------------

    /** Inter SemiBold (600) for key labels, or the closest system font if the asset is missing. */
    val keyTypeface: Typeface = runCatching { ResourcesCompat.getFont(context, R.font.inter_semibold) }
        .getOrNull() ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ---- Helpers -----------------------------------------------------------

    private val hairlinePx = (0.5f * density).roundToInt().coerceAtLeast(1)

    fun dp(value: Float): Int = (value * density).roundToInt()
    fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val DARK_INK = 0xDE000000.toInt() // ~87% black, for labels on a light accent

        /** Hues (degrees) cycled through for translation word-alignment colouring. */
        val WORD_HUES = floatArrayOf(210f, 145f, 0f, 275f, 38f, 185f, 320f, 95f)
    }
}
