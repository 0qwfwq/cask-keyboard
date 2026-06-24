package com.example.cask

import android.content.Context
import android.content.res.Configuration
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/**
 * Derives a per-app colour palette so the keyboard can take on the colour of whatever app the user
 * is typing in.
 *
 * A keyboard cannot read the pixels of the app behind it (that needs screen capture). The practical,
 * permission-light equivalent is the foreground app's *brand* colour: we extract the dominant/vibrant
 * colour from its launcher icon. Whether the keyboard goes light or dark follows the *device's*
 * system night-mode setting (the source of truth), not the app — so the keyboard always matches the
 * phone's theme while still picking up each app's accent hue.
 *
 * The accent is cached per package because icon decoding + palette generation isn't free; the
 * light/dark flag is recomputed on every call so toggling system dark mode is picked up immediately.
 *
 * Reading another package's icon requires package visibility (QUERY_ALL_PACKAGES on Android 11+).
 * Everything is wrapped in try/catch so an unreadable app simply falls back to the Cask navy.
 */
object AppColors {

    /** accent = brand colour (drives the accent + hue); isLight = use a light keyboard. */
    data class AppPalette(val accent: Int, val isLight: Boolean)

    private const val FALLBACK_ACCENT = 0xFF4F8EF7.toInt() // pulseBlue
    private val accentCache = HashMap<String, Int>()

    fun forPackage(context: Context, pkg: String?): AppPalette {
        val light = !isDeviceNight(context)
        if (pkg.isNullOrEmpty() || pkg == context.packageName) return AppPalette(FALLBACK_ACCENT, light)
        val accent = accentCache.getOrPut(pkg) {
            try {
                extractAccent(context, pkg)
            } catch (_: Throwable) {
                FALLBACK_ACCENT
            }
        }
        return AppPalette(accent, light)
    }

    /** True when the device system theme is in dark/night mode. */
    private fun isDeviceNight(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** Pull the brand/accent colour out of the app's launcher icon. */
    private fun extractAccent(context: Context, pkg: String): Int {
        val bitmap = context.packageManager.getApplicationIcon(pkg).toBitmap(width = 108, height = 108)
        val palette = Palette.from(bitmap).clearFilters().generate()
        return palette.vibrantSwatch?.rgb
            ?: palette.lightVibrantSwatch?.rgb
            ?: palette.darkVibrantSwatch?.rgb
            ?: palette.dominantSwatch?.rgb
            ?: FALLBACK_ACCENT
    }
}
