package io.amper.neuroos.core

/**
 * Phase675 mobile-bounded procedural-memory qualification policy.
 *
 * This object persists nothing. It centralizes read budgets and live qualification predicates used
 * by existing skill/generalization/repair-memory stores so planning guidance cannot drift into an
 * unbounded or stale procedural-memory path.
 */
object ProceduralMemoryPolicy {
    const val MAX_SKILL_RECENT: Int = 64
    const val SKILL_GUIDANCE_LOOKBACK: Int = 16
    const val MAX_SKILL_GUIDANCE: Int = 4
    const val MAX_SKILL_COMPOSITIONS: Int = 3

    const val MAX_GENERALIZATION_RECENT: Int = 64
    const val GENERALIZATION_LOOKBACK: Int = 24
    const val GENERALIZATION_SKILL_LOOKBACK: Int = 24
    const val MAX_GENERALIZATION_GUIDANCE: Int = 4
    const val MAX_GENERALIZATION_CHAINS: Int = 3

    const val MAX_REPAIR_RECENT: Int = 32
    const val MIN_REPAIR_PATTERN_SIMILARITY: Double = 0.75

    fun skillCapabilityQualified(
        skill: SkillContract,
        allowedCapabilities: Set<CapabilityId>,
        liveCapabilities: Set<CapabilityId>
    ): Boolean =
        skill.maturity == SkillMaturity.ACTIVE &&
            !skill.authorityBearing &&
            skill.signature.capabilities.all {
                it in allowedCapabilities && it in liveCapabilities
            }

    fun generalizationQualified(profile: SkillGeneralizationProfile): Boolean =
        !profile.authorityBearing &&
            (
                profile.maturity == SkillGeneralizationMaturity.TRANSFERABLE ||
                    profile.maturity == SkillGeneralizationMaturity.GENERALIZED
                )

    fun repairSupport(
        pattern: GoalRepairStrategyPattern,
        structuralSimilarity: Double,
        liveRequalification: Double
    ): Double {
        require(structuralSimilarity in 0.0..1.0)
        require(liveRequalification in 0.0..1.0)
        if (
            !pattern.active ||
            pattern.authorityBearing ||
            structuralSimilarity < MIN_REPAIR_PATTERN_SIMILARITY ||
            liveRequalification <= 0.0
        ) {
            return 0.0
        }
        return (
            pattern.evidenceConfidence *
                structuralSimilarity *
                liveRequalification
            ).coerceIn(0.0, 1.0)
    }
}
