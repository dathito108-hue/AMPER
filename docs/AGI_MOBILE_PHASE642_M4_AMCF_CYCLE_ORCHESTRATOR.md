# Phase642 — M4 AMCF Bounded Transactional Cycle Orchestrator

Phase642 adds the bounded controller that sequences the Phase640 AMCF plan and commits the Phase641
structured recurrent state.

## Existing foundation only

The orchestrator does not own a model, decoder, runtime, ToolFabric, or AuthorityGate.

It receives an injected `AmcfCycleExecutionPort`. A later integration phase can implement that port
using the existing AMPER cognitive/inference stack over the single AMI2/AMNE2 foundation.

The orchestrator only controls cycle order and recurrent-state commit.

## Transaction boundary

For each cycle:

1. Check cancellation.
2. Execute the injected cognitive cycle.
3. Check cancellation again.
4. Build the structured recurrent state.
5. Check cancellation again.
6. Commit the new recurrent state.

Therefore a failed or cancelled cycle cannot publish a state representing work that did not complete.

Previously committed successful cycles remain valid; the failed cycle itself is absent.

## Bounded execution

The orchestrator can never execute more cycles than the Phase640 plan, which is capped at 16.

Cycle order comes from the canonical AMCF plan.

Phase641 early-exit decisions may skip only optional DELIBERATE cycles:

- ADVANCE_TO_VERIFY jumps to the next scheduled VERIFY;
- ADVANCE_TO_FINALIZE jumps to the mandatory FINALIZE;
- CONTINUE_DELIBERATION advances normally.

Scheduled VERIFY/REVISE pairs are preserved.

A successful run must terminate with a committed FINALIZE state.

## Recurrent state ownership

The cycle execution port returns only structured observation:

- candidate digest;
- evidence digest;
- confidence;
- uncertainty;
- evidence sufficiency.

The orchestrator converts that observation into the canonical recurrent state through
`AmcfRecurrentStateTransition`.

The execution port does not commit recurrent state itself.

## Cancellation

Phase642 reuses the existing `InferenceCancellationSignal`; no second cancellation system is added.

Cancellation is checked both before executing a cycle and at the commit boundary.

## Authority separation

`AmcfCycleExecutionPort` is explicitly a cognitive/inference port.

It must not directly execute tools or authority-bearing actions. Tool/action authority remains in the existing
governed agent/action path outside AMCF reasoning-state control.

## Phase642 exit criteria

Phase642 is complete when:

- cycle execution is hard bounded by the AMCF plan;
- every request remains on the plan's single foundation;
- cancellation before commit prevents that cycle state from being published;
- failed cycles do not commit state;
- prior successful cycle commits remain ordered and valid;
- early exit skips only optional deliberation;
- scheduled VERIFY/REVISE/FINALIZE work remains ordered;
- successful runs terminate with FINALIZE;
- no second runtime/tool-authority path is introduced.

## Next M4 slice

Phase643 should add the production AMCF foundation inference port: translate each AMCF cycle into a
bounded request to the existing AMI2/AMNE2 production endpoint while passing only structured
candidate/evidence state between cycles. It should reuse NativeSystem2/metacognitive components where
appropriate rather than creating another reasoning stack.
