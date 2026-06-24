package com.example.cask.engine

import android.graphics.PointF
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Decodes a **glide / swipe gesture** (a finger traced continuously from letter to letter, like
 * GBoard's gesture typing) into the word the user most likely meant.
 *
 * The approach is the classic gesture-keyboard template match: for every plausible dictionary word we
 * build its *ideal path* — the polyline through the centres of each of its letter keys — then compare
 * the user's traced path against it on two channels:
 *
 *  - **Location** — how close the two paths sit on the keyboard once both are resampled to the same
 *    number of equidistant points (absolute screen position matters: the keys you crossed *are* the
 *    word's keys).
 *  - **Shape** — how similar the two paths look after translating and scaling out position/size
 *    (catches the same gesture drawn a little high, low, big or small).
 *
 * Endpoints are weighted heavily (the first and last key of a swipe are the most reliable), and the
 * language model breaks ties toward the more likely word. Candidates are pruned to words that *start*
 * near where the gesture started and *end* near where it ended, so only a few hundred words are ever
 * scored. All distances are normalised by the key radius so the weights are layout-independent.
 *
 * Coordinates are whatever space the keyboard view supplies (its own pixels); [centers] are the live
 * letter-key centres in that same space, so the decoder never needs to know the on-screen layout.
 */
class GestureDecoder(
    private val dict: Dictionary,
    private val lm: LanguageModel,
) {

    private class Scored(val word: String, val score: Double)

    /**
     * Best-guess words for the traced [points] (most likely first), using the live letter-key
     * [centers] and [keyRadius] (half a key's width). [prev1]/[prev2] are the preceding words for the
     * language-model tie-break. Returns at most [TOP_K] words, lowercase.
     */
    fun decode(
        points: List<PointF>,
        centers: Map<Char, PointF>,
        keyRadius: Float,
        prev1: String?,
        prev2: String?,
    ): List<String> {
        if (points.size < 2 || centers.isEmpty() || keyRadius <= 0f) return emptyList()

        val path = resample(points, SAMPLES)
        if (path.size < SAMPLES) return emptyList()
        val start = points.first()
        val end = points.last()

        val startLetters = nearestLetters(start, centers, keyRadius)
        val endLetters = nearestLetters(end, centers, keyRadius)
        if (startLetters.isEmpty() || endLetters.isEmpty()) return emptyList()
        val endSet = endLetters.toHashSet()

        val scored = ArrayList<Scored>()
        val seen = HashSet<String>()
        for (s in startLetters) {
            for (cand in dict.trie.prefixMatches(s.toString(), PREFIX_CAP)) {
                val word = cand.word
                if (word.length < 2 || word.last() !in endSet) continue
                if (!seen.add(word)) continue
                val template = template(word, centers) ?: continue
                scored.add(Scored(word, score(path, start, end, template, word, keyRadius, prev1, prev2)))
            }
        }
        scored.sortByDescending { it.score }
        return scored.take(TOP_K).map { it.word }
    }

    // ---- Scoring -----------------------------------------------------------

    private fun score(
        path: List<PointF>,
        start: PointF,
        end: PointF,
        template: List<PointF>,
        word: String,
        keyRadius: Float,
        prev1: String?,
        prev2: String?,
    ): Double {
        var location = 0.0
        for (i in path.indices) location += dist(path[i], template[i])
        location /= path.size * keyRadius

        val shape = shapeDistance(path, template)

        val ends = (dist(start, template.first()) + dist(end, template.last())) / keyRadius
        val lmLog = lm.contextLogProb(word, prev1, prev2)

        return -(W_LOCATION * location + W_SHAPE * shape + W_ENDS * ends) + W_LM * lmLog
    }

    /** Mean point distance after both paths are centred and scaled to unit size (position/size-free). */
    private fun shapeDistance(a: List<PointF>, b: List<PointF>): Double {
        val na = normalizeShape(a)
        val nb = normalizeShape(b)
        var sum = 0.0
        for (i in na.indices) sum += dist(na[i], nb[i])
        return sum / na.size
    }

    private fun normalizeShape(pts: List<PointF>): List<PointF> {
        var cx = 0.0
        var cy = 0.0
        for (p in pts) { cx += p.x; cy += p.y }
        cx /= pts.size; cy /= pts.size
        var ss = 0.0
        for (p in pts) {
            val dx = p.x - cx; val dy = p.y - cy
            ss += dx * dx + dy * dy
        }
        val scale = sqrt(ss / pts.size).coerceAtLeast(1e-3)
        return pts.map { PointF(((it.x - cx) / scale).toFloat(), ((it.y - cy) / scale).toFloat()) }
    }

    // ---- Templates & candidate anchors -------------------------------------

    /** The ideal path of [word]: its letter-key centres, resampled to [SAMPLES] points (null if a letter has no key). */
    private fun template(word: String, centers: Map<Char, PointF>): List<PointF>? {
        val pts = ArrayList<PointF>(word.length)
        for (ch in word) pts.add(centers[ch] ?: return null)
        return resample(pts, SAMPLES)
    }

    /** Letter keys whose centre lies within reach of [p] (closest first); at least the single nearest. */
    private fun nearestLetters(p: PointF, centers: Map<Char, PointF>, keyRadius: Float): List<Char> {
        val limit = keyRadius * ANCHOR_RADIUS
        val ranked = centers.entries
            .map { it.key to dist(p, it.value) }
            .sortedBy { it.second }
        val within = ranked.takeWhile { it.second <= limit }.take(MAX_ANCHORS)
        val chosen = if (within.isEmpty()) ranked.take(1) else within
        return chosen.map { it.first }
    }

    // ---- Geometry helpers --------------------------------------------------

    private fun dist(a: PointF, b: PointF): Double =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    private fun pathLength(pts: List<PointF>): Double {
        var sum = 0.0
        for (i in 1 until pts.size) sum += dist(pts[i - 1], pts[i])
        return sum
    }

    /** Resample a polyline into [n] points spaced equally along its arc length (endpoints preserved). */
    private fun resample(pts: List<PointF>, n: Int): List<PointF> {
        if (pts.isEmpty()) return emptyList()
        val first = pts.first()
        if (pts.size == 1) return List(n) { PointF(first.x, first.y) }
        val total = pathLength(pts)
        if (total <= 1e-3) return List(n) { PointF(first.x, first.y) }

        val step = total / (n - 1)
        val out = ArrayList<PointF>(n)
        out.add(PointF(first.x, first.y))
        var prevX = first.x
        var prevY = first.y
        var acc = 0.0
        var i = 1
        while (out.size < n - 1 && i < pts.size) {
            val cx = pts[i].x
            val cy = pts[i].y
            val seg = hypot((cx - prevX).toDouble(), (cy - prevY).toDouble())
            if (seg <= 1e-9) { i++; continue }
            if (acc + seg >= step) {
                val t = ((step - acc) / seg).toFloat()
                val nx = prevX + t * (cx - prevX)
                val ny = prevY + t * (cy - prevY)
                out.add(PointF(nx, ny))
                prevX = nx
                prevY = ny
                acc = 0.0
            } else {
                acc += seg
                prevX = cx
                prevY = cy
                i++
            }
        }
        val last = pts.last()
        while (out.size < n) out.add(PointF(last.x, last.y))
        return out
    }

    private companion object {
        const val SAMPLES = 48          // points each path is resampled to before comparison
        const val PREFIX_CAP = 2500     // heaviest words pulled per candidate start letter
        const val ANCHOR_RADIUS = 1.5f  // how far (in key radii) an endpoint can be from a letter key
        const val MAX_ANCHORS = 4       // candidate start/end letters considered per endpoint
        const val TOP_K = 3

        // Relative weights of the three matching channels plus the language-model tie-break.
        const val W_LOCATION = 1.6
        const val W_SHAPE = 1.1
        const val W_ENDS = 1.3
        const val W_LM = 0.45
    }
}
