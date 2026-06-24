package com.example.cask.engine

import android.graphics.PointF
import kotlin.math.hypot

/**
 * The physical QWERTY layout, used to make the error model *spatial*: a substitution between two
 * keys that sit next to each other on the keyboard (g→h, n→m, e→r) is cheap, while a substitution
 * between keys on opposite sides (q→p) is expensive. This is what lets the engine prefer "hello"
 * over "jello" for the typed string "jello" — j and h are neighbours of the intended key — instead
 * of treating every single-letter change as equally likely.
 *
 * Coordinates are in "key units": one key step horizontally or vertically is distance 1.0. The home
 * row is offset half a key and the bottom row a key and a half, matching the staggered layout drawn
 * in [com.example.cask.CaskKeyboardView].
 *
 * All values are *costs* (penalties), lower = more likely. [com.example.cask.engine.EditModel]
 * sums them; the noisy-channel scorer turns the total into a log-probability.
 */
object KeyGeometry {

    private val rows = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )
    private val rowOffset = doubleArrayOf(0.0, 0.5, 1.5)

    private data class Pt(val x: Double, val y: Double)

    private val pos: Map<Char, Pt> = buildMap {
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                put(ch, Pt(c + rowOffset[r], r.toDouble()))
            }
        }
    }

    /** Euclidean distance between two letter keys in key units, or `null` if either isn't a letter key. */
    fun distance(a: Char, b: Char): Double? {
        val pa = pos[a.lowercaseChar()] ?: return null
        val pb = pos[b.lowercaseChar()] ?: return null
        return hypot(pa.x - pb.x, pa.y - pb.y)
    }

    /** Canonical key-unit position of letter [c] (column+offset, row), or `null` if not a letter key. */
    fun posOf(c: Char): PointF? = pos[c.lowercaseChar()]?.let { PointF(it.x.toFloat(), it.y.toFloat()) }

    /**
     * Spatial substitution cost of a *continuous touch* at key-unit ([tx], [ty]) against [intended]:
     * the probabilistic analogue of [substitutionCost]. 0 right on the key centre, growing linearly
     * with key-unit distance (a neighbour, ~1.0 away, lands near a discrete neighbour slip), clamped so
     * a far touch stays a possible-but-unlikely explanation. This is what lets a tap that fell between
     * `g` and `h` be priced by *where the finger actually was* rather than a flat neighbour penalty.
     */
    fun spatialSubCost(tx: Float, ty: Float, intended: Char): Double {
        val p = pos[intended.lowercaseChar()] ?: return UNKNOWN_SUB_COST
        val d = hypot(tx - p.x, ty - p.y)
        return (SPATIAL_DIST_SCALE * d).coerceAtMost(MAX_SUB_COST)
    }

    /** True when [a] and [b] are immediate keyboard neighbours (a very common slip). */
    fun areNeighbors(a: Char, b: Char): Boolean {
        val d = distance(a, b) ?: return false
        return d > 0.0 && d <= NEIGHBOR_RADIUS
    }

    /**
     * Cost of having typed [typed] when [intended] was meant. 0 for an exact match; for letters it
     * scales with keyboard distance (neighbours are cheap, far keys are expensive); for anything the
     * layout doesn't know about (digits, symbols, accents) it falls back to a flat mismatch cost.
     */
    fun substitutionCost(typed: Char, intended: Char): Double {
        if (typed == intended) return 0.0
        if (typed.lowercaseChar() == intended.lowercaseChar()) return CASE_ONLY_COST
        val d = distance(typed, intended) ?: return UNKNOWN_SUB_COST
        // Smoothly grow with distance, clamped so even far substitutions stay a *possible* (just
        // unlikely) explanation. Neighbours (~1.0) land around NEIGHBOR_COST.
        return (MIN_SUB_COST + DIST_SCALE * d).coerceAtMost(MAX_SUB_COST)
    }

    const val NEIGHBOR_RADIUS = 1.35
    const val CASE_ONLY_COST = 0.05
    const val MIN_SUB_COST = 0.20
    const val DIST_SCALE = 0.55
    const val MAX_SUB_COST = 1.85
    const val UNKNOWN_SUB_COST = 1.30
    const val SPATIAL_DIST_SCALE = 0.75 // touch-model slope: a neighbour (~1 key away) ≈ a discrete neighbour slip

    // Costs for the other edit operations, tuned relative to substitution above.
    const val INSERT_COST = 1.05        // a stray extra key
    const val DELETE_COST = 1.05        // a missed key
    const val TRANSPOSE_COST = 0.65     // "teh" -> "the": extremely common, so cheap
    const val DOUBLE_LETTER_COST = 0.55 // dropped/added a doubled letter ("hapy"/"happpy")
}
