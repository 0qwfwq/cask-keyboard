package com.example.cask

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Key-press haptics for the keyboard.
 *
 * The on/off toggle and strength (0–100) live in [PREFS] shared preferences, written by the Flutter
 * setup app (via [MainActivity]) and read here. Because the IME service and the setup Activity run
 * in the same process, changes apply immediately — we read the values fresh on each tap (a cheap
 * in-memory lookup) so no restart or listener wiring is needed.
 */
class Haptics(private val context: Context) {

    private val vibrator: Vibrator? = resolveVibrator(context)

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val enabled: Boolean get() = prefs().getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    private val strength: Int get() = prefs().getInt(KEY_STRENGTH, DEFAULT_STRENGTH)

    /** A short, snappy tick on key-down whose amplitude scales with the user's strength setting. */
    fun keyTap() {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val s = strength.coerceIn(0, 100)
        if (s == 0) return
        val durationMs = 8L + s / 12L // ~8–16ms: present but never mushy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = (s * 255 / 100).coerceIn(1, 255)
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private fun resolveVibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    companion object {
        const val PREFS = "cask_prefs"
        const val KEY_ENABLED = "haptics_enabled"
        const val KEY_STRENGTH = "haptics_strength"
        const val DEFAULT_ENABLED = true
        const val DEFAULT_STRENGTH = 40
    }
}
