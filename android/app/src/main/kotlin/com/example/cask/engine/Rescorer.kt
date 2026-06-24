package com.example.cask.engine

/**
 * A pluggable second-pass scorer. The statistical engine produces a small, high-quality candidate
 * list; a [Rescorer] can then nudge those candidates using a stronger context model (e.g. a neural
 * LM) without the rest of the pipeline knowing or caring how.
 *
 * Contract: [rescore] returns one additive log-probability adjustment per candidate (same order,
 * same length), to be summed into the candidate's language-model term. An adjustment of 0 means "no
 * opinion". Implementations must be cheap enough to call on every keystroke or return early.
 */
interface Rescorer {
    /** Whether this rescorer is actually able to contribute (model loaded, etc.). */
    val isReady: Boolean

    /**
     * @param context the lowercased preceding words, oldest→newest (may be empty).
     * @param candidates the candidate words being ranked, lowercased.
     * @return additive log-prob adjustments, one per candidate, same order.
     */
    fun rescore(context: List<String>, candidates: List<String>): DoubleArray
}

/** The default no-op rescorer used when no neural model is installed. */
object IdentityRescorer : Rescorer {
    override val isReady: Boolean = false
    override fun rescore(context: List<String>, candidates: List<String>): DoubleArray =
        DoubleArray(candidates.size)
}
