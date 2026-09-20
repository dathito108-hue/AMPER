# Phase640 — M4 AMCF Compute-Cycle Contract

Phase640 starts Milestone 4 without introducing a second model, decoder, or reasoning backend.

## One foundation, variable compute depth

AMCF receives the existing OMEGA adaptive-compute budget and translates it into a bounded sequence
of cognitive cycles.

Every cycle is bound to the exact same:

- AMPER foundation id;
- foundation semantic SHA-256;
- canonical AMNE2 execution engine.

Changing reasoning depth therefore changes compute spent, not model identity.

## Cycle types

The initial AMCF cycle contract defines:

- DELIBERATE — bounded recurrent reasoning over the same foundation;
- VERIFY — evaluate the current candidate answer/reasoning state;
- REVISE — one paired revision opportunity after each verification pass;
- FINALIZE — emit the final answer state.

VERIFY, REVISE, and FINALIZE are mandatory once scheduled.

## OMEGA mode mapping

The existing OMEGA budget remains the source of truth:

- FAST: zero recurrent deliberation cycles, then FINALIZE;
- STANDARD: one recurrent cycle;
- REASON: bounded recurrent depth with early exit after a minimum reasoning floor;
- DEEP: deeper bounded recurrence with a larger minimum floor;
- VERIFY: all scheduled recurrent cycles are mandatory, followed by every VERIFY/REVISE pair.

No AMCF plan may exceed 16 total cycles.

## Early exit

Early exit is allowed only after a DELIBERATE cycle and only after the mode-specific minimum
deliberation depth has been satisfied.

It is never allowed to skip:

- a scheduled VERIFY;
- its paired REVISE opportunity;
- FINALIZE.

This creates a deterministic fast path without allowing high-consequence VERIFY work to collapse into
a shallow response.

## Separation from existing cognition

The repository already contains NativeSystem2, metacognitive control, cognitive continuity,
hierarchical deliberation, and related reasoning structures.

Phase640 does not duplicate or replace them.

It establishes the compute-cycle envelope that later M4 phases can use to orchestrate those existing
cognitive components through the one production AMI2/AMNE2 foundation.

## Phase640 exit criteria

Phase640 is complete when:

- every AMCF cycle carries one immutable foundation identity;
- AMNE2 is the only execution engine accepted by the AMCF binding;
- OMEGA recurrentCycles maps deterministically to DELIBERATE cycles;
- every verify pass maps to a VERIFY/REVISE pair;
- VERIFY mode cannot early-exit;
- FINALIZE is always mandatory;
- total cognitive cycles are hard bounded;
- no new model/backend/runtime path is introduced.

## Next M4 slice

Phase641 should add the AMCF recurrent-state contract: an immutable per-cycle reasoning state passed
from DELIBERATE -> VERIFY -> REVISE -> FINALIZE, with explicit confidence/uncertainty and an early-exit
gate. It should remain data/control state only and should not create a separate memory system or
parallel inference engine.
