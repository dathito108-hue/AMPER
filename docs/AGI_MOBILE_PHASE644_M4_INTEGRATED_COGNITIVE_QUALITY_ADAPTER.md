# Phase644 — M4 Integrated Cognitive Quality Adapter

Phase644 replaces the conservative placeholder quality source with a deterministic adapter over the
existing integrated cognitive readiness packet. It does not create a judge model, critic backend, or
parallel reasoning runtime.

## Existing cognitive state only

`AmcfIntegratedCognitiveQualitySignals.from(packet)` consumes the already-computed
`IntegratedCognitiveStatePacket` and binds the adapter to its canonical digest.

The signals are limited to structured non-authority data:

- overall readiness;
- epistemic confidence;
- world confidence;
- skill confidence;
- competence confidence;
- uncertainty;
- learning pressure;
- perceptual freshness;
- stale-percept fraction.

No generated answer text is scored.

## Deterministic quality mapping

`AmcfIntegratedCognitiveQualityEvaluator` derives the Phase641 AMCF quality triplet:

- confidence is a weighted readiness score reduced by learning pressure;
- uncertainty is the worse of integrated cognitive uncertainty and a stale-perception penalty;
- evidence sufficiency combines epistemic, world, competence and perceptual freshness, then reduces
  the result under learning pressure.

The same signal snapshot always produces the same result regardless of whether generated prose sounds
short, long, confident or persuasive.

## Conservative evidence rules

Stale or planning-ineligible perceptual evidence can only increase uncertainty.

Learning pressure can only lower confidence/evidence sufficiency.

When no perceptual evidence exists, the adapter uses an explicit neutral freshness value of 0.80
rather than treating missing perception as either perfect or failed evidence.

This means early exit is available only when the existing grounded cognitive state is already strong
enough to satisfy the Phase641 mode threshold.

## Architecture result

The M4 control path is now:

`Integrated Cognitive State -> deterministic AMCF quality -> Phase641 early-exit gate -> Phase642 orchestrator -> Phase643 production AMI2/AMNE2 port`

There is still one foundation and one inference backend.

## Phase644 exit criteria

Phase644 is complete when:

- AMCF confidence comes from existing structured cognitive readiness, not generated prose;
- high epistemic/world readiness can satisfy existing early-exit thresholds;
- stale perception raises uncertainty and can block early exit;
- learning pressure lowers confidence/evidence sufficiency;
- mapping is deterministic and authority-free;
- no judge model, second critic runtime, backend, or tool authority path is added.

## Next M4 slice

Phase645 should bind one captured integrated cognitive packet to a complete AMCF run so every cycle
uses the same cognitive-state snapshot. It should prevent recapture/drift inside a run and expose the
snapshot digest in bounded diagnostics. After this, M4 can be evaluated for closure against the
roadmap exit criteria.
