package io.amper.neuroos.core

/**
 * Canonical structural similarity for bounded strategy signatures.
 *
 * The score combines set overlap with ordered-prefix agreement. It is deliberately independent
 * of authority, tool ids, raw goals, inputs and outputs so every repair/transfer layer evaluates
 * structural compatibility with exactly the same semantics.
 */
object StrategyStructuralSimilarity {
    private const val CAPABILITY_OVERLAP_WEIGHT = 0.65
    private const val ORDERED_PREFIX_WEIGHT = 0.35

    fun score(
        source: StrategySignature,
        target: StrategySignature
    ): Double {
        val sourceSet = source.capabilities.toSet()
        val targetSet = target.capabilities.toSet()
        val union = sourceSet union targetSet
        val jaccard = if (union.isEmpty()) {
            0.0
        } else {
            sourceSet.intersect(targetSet).size.toDouble() / union.size.toDouble()
        }

        val longest = maxOf(source.capabilities.size, target.capabilities.size)
            .coerceAtLeast(1)
        val prefix = source.capabilities.zip(target.capabilities)
            .takeWhile { (left, right) -> left == right }
            .size.toDouble() / longest.toDouble()

        return (
            CAPABILITY_OVERLAP_WEIGHT * jaccard +
                ORDERED_PREFIX_WEIGHT * prefix
            ).coerceIn(0.0, 1.0)
    }
}
