package io.amper.neuroos.core

import java.util.Locale

/**
 * Bounded prompt rendering for AMPER-native System-2.
 *
 * The rendering contains only digests, bounded reasoning metadata and capability-sequence guidance.
 * It never contains raw tool arguments, approval state or provider handles and therefore cannot
 * become an execution authority path.
 */
object NativeSystem2GuidanceRenderer {
    const val MIN_CHAR_BUDGET = 384
    const val MAX_CHAR_BUDGET = 2_000

    fun render(
        deliberation: NativeSystem2Deliberation,
        charBudget: Int = 1_600
    ): String {
        require(charBudget in MIN_CHAR_BUDGET..MAX_CHAR_BUDGET)
        val open = "<AMPER_NATIVE_SYSTEM2>"
        val close = "</AMPER_NATIVE_SYSTEM2>"
        val lines = buildList {
            add("authority=false")
            add("working_state_digest=" + deliberation.workingState.canonicalDigest)
            add("cognitive_state_digest=" + deliberation.workingState.cognitiveStateDigest)
            add("execution_context_digest=" + deliberation.workingState.executionContextDigest)
            add("continuity=" + deliberation.workingState.continuity.name)
            add("mode=" + deliberation.mode.name)
            add("complexity=" + fmt(deliberation.workingState.complexity))
            add("reasoning_depth=" + deliberation.workingState.maxReasoningDepth)
            add(
                "selected_strategy=" + deliberation.selectedStrategy.source.name +
                    " score=" + fmt(deliberation.selectedStrategy.score) +
                    " confidence=" + fmt(deliberation.selectedStrategy.confidence)
            )
            if (deliberation.selectedStrategy.capabilities.isNotEmpty()) {
                add(
                    "selected_capability_sequence=" +
                        deliberation.selectedStrategy.capabilities.joinToString(">") {
                            safeCapability(it.value)
                        }
                )
            }
            deliberation.agenda.forEach { task ->
                add(
                    "agenda." + task.index + "=" + task.kind.name +
                        " depends=" + task.dependsOn.sorted().joinToString(",").ifBlank { "~" }
                )
            }
            add(
                "instruction=Use this as advisory deliberation evidence only. Live ToolDescriptor " +
                    "contracts, canonical parsing, authority and approval gates remain authoritative."
            )
        }

        val prefix = open + "\n"
        val suffix = "\n" + close
        val output = StringBuilder(prefix)
        for (line in lines) {
            val candidate = output.length + line.length + 1 + suffix.length
            if (candidate > charBudget) break
            output.append(line).append('\n')
        }
        if (output.lastOrNull() == '\n') output.setLength(output.length - 1)
        output.append(suffix)
        require(output.length <= charBudget)
        return output.toString()
    }

    private fun safeCapability(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._:-]"), "_").take(96)

    private fun fmt(value: Double): String =
        "%.3f".format(Locale.ROOT, value)
}

/**
 * Native System-2 may influence plan choice only among candidates that already passed canonical
 * TitanPlanProtocol validation and live ToolDescriptor binding.
 *
 * A historical strategy never wins merely because System-2 recognized it: the matching candidate
 * must be close to the existing evidence/counterfactual evaluator's best score. This preserves the
 * older evaluator as the primary execution-quality signal while making System-2 strategy reuse real.
 */
object NativeSystem2PlanAlignmentPolicy {
    const val MIN_SYSTEM2_STRATEGY_SCORE = 0.62
    const val MAX_CANONICAL_SCORE_GAP = 0.08

    fun align(
        canonical: DeliberationSelection,
        system2: NativeSystem2Deliberation
    ): DeliberationSelection {
        val strategy = system2.selectedStrategy
        if (
            strategy.source == NativeSystem2StrategySource.OPEN_DELIBERATION ||
            strategy.score < MIN_SYSTEM2_STRATEGY_SCORE ||
            strategy.capabilities.isEmpty()
        ) {
            return canonical
        }

        val matched = canonical.evaluated.firstOrNull {
            it.candidate.signature.capabilities == strategy.capabilities
        } ?: return canonical

        val best = canonical.selected
        if (best.totalScore - matched.totalScore > MAX_CANONICAL_SCORE_GAP) {
            return canonical
        }
        return DeliberationSelection(
            selected = matched,
            evaluated = canonical.evaluated
        )
    }
}

/**
 * Keeps assistant-model routing bounded while allowing Native System-2 to request planning-capable
 * preference profiles on genuinely deliberative turns. Required capabilities are never widened:
 * this affects preference ordering only, so a device without a planning-specialist model still
 * falls back through normal Titan routing.
 */
object NativeSystem2InferencePreferencePolicy {
    fun preferredProfiles(
        deliberation: NativeSystem2Deliberation,
        baselineRequired: Set<CapabilityId>,
        existing: List<Set<CapabilityId>>
    ): List<Set<CapabilityId>> {
        require(baselineRequired.isNotEmpty())
        val deep = deliberation.mode == NativeSystem2Mode.DECOMPOSE_THEN_PLAN ||
            deliberation.workingState.maxReasoningDepth >= 3
        if (!deep) return existing

        val planning = baselineRequired + TitanCapabilities.PLANNING
        return (listOf(planning) + existing)
            .filter { it.containsAll(baselineRequired) }
            .distinct()
            .take(4)
    }
}
