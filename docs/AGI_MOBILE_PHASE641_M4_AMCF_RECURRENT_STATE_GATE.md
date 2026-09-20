# Phase641 — M4 AMCF Recurrent State / Early-Exit Gate

Phase641 adds the structured state passed between AMCF cognitive cycles.

## No raw hidden reasoning state

AMCF recurrent control state stores only:

- immutable foundation identity;
- completed cycle index/type;
- candidate-state SHA-256 digest;
- evidence-state SHA-256 digest;
- confidence;
- uncertainty;
- evidence sufficiency;
- verification count;
- revision count.

It does not require storing free-form hidden reasoning or chain-of-thought.

A deterministic state digest binds those structured fields for checkpointing and diagnostics.

## Immutable recurrent transitions

Each recorded cycle must advance the cycle index and retain the exact same AMCF foundation binding.

VERIFY increments the verification count.

REVISE is accepted only when there is an unmatched prior VERIFY and increments the revision count.
A revision count can never exceed the verification count.

This preserves the Phase640 VERIFY -> REVISE pairing even if optional DELIBERATE cycles are skipped.

## Early-exit gate

Early exit is evaluated only on a DELIBERATE cycle marked eligible by the Phase640 plan.

The gate uses explicit mode-specific thresholds over:

- confidence;
- uncertainty;
- evidence sufficiency.

If evidence is insufficient, AMCF continues recurrent deliberation.

If evidence is sufficient:

- when scheduled verification remains, control advances to VERIFY;
- otherwise control advances to FINALIZE.

Early exit never skips a scheduled VERIFY, REVISE, or FINALIZE cycle.

VERIFY mode remains non-early-exit by construction.

## Separation of concerns

Phase641 is data/control state only.

It does not:

- execute inference;
- expose raw internal reasoning text;
- create a new memory system;
- create another model/decoder/backend;
- change tool authority;
- change AMNE2 execution semantics.

Later M4 phases can populate this state from the existing cognitive/runtime components.

## Phase641 exit criteria

Phase641 is complete when:

- recurrent state is immutable and foundation-bound;
- state identity is digestable without raw hidden reasoning;
- confidence, uncertainty, and evidence sufficiency are explicit;
- cycle indices advance monotonically;
- REVISE requires unmatched VERIFY;
- early exit is limited to eligible DELIBERATE cycles;
- strong evidence advances to mandatory verification when required;
- VERIFY mode cannot early-exit.

## Next M4 slice

Phase642 should add the AMCF cycle orchestrator that executes the Phase640 plan and Phase641 control
state through injected existing inference/cognitive ports. The orchestrator must remain bounded,
cancellation-aware, same-foundation, and transactional: failed/cancelled cycles must not commit a new
recurrent state.
