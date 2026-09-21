# Phase666 — M5 Proactive History Integrity / Recovery Navigation

Phase666 connects the Phase665 read-only proactive history projection to the existing Recovery
Console without creating any new recovery authority.

The canonical execution and recovery ownership remain unchanged:

```
proactive history
  -> Phase662 lifecycle-derived plan state
  -> existing canonical SovereignPlanReceiptLedger evidence
  -> existing SovereignRecoveryConsole
  -> explicit human reconciliation only
```

No provider is invoked by navigation.

## Exact recovery target

Phase666 introduces `RecoveryClaimTarget` with exactly:

- canonical `PlanId`;
- canonical step index;
- canonical `ActionRequestId`.

A Phase665 receipt projection carries this target only when
`durabilityEvidence == CLAIMED_UNRESOLVED`.

Terminal receipts, reconciled evidence, ordinary pending approval, completed steps, and missing-plan
history entries do not expose a recovery target.

## Canonical revalidation

Selecting "Open exact recovery claim" never trusts the history snapshot as recovery authority.

`SovereignRecoveryConsole.locate(target)` re-reads the current unresolved canonical recovery set and
matches all three target dimensions:

```
PlanId + stepIndex + requestId
```

The matched item has already passed the existing Recovery Console checks that bind the durable claim
to the persisted plan snapshot, step status, capability, request id, and tool id.

If the target is stale, already reconciled, points to another step, another request, or another plan,
the locator returns no claim. It never falls through to the first unresolved item.

Corrupt/orphan recovery evidence remains fail-closed through the existing `pending()` validation.

## UI focus semantics

The proactive history surface can request focus on one exact unresolved claim.

MainActivity first calls the canonical locator. When the claim is current it:

- opens the already-persisted canonical plan for context;
- stores only the transient in-process `RecoveryClaimTarget` for Recovery Console focus;
- performs no planner advance, approval, rejection, reconciliation, scheduling, or tool invocation.

The Recovery Console panel then revalidates the same target and selects only that matching request.

A stale target leaves no claim selected and displays a stale/reconciled status instead of selecting a
different claim.

## Recovery authority remains unchanged

The only mutation path is still the existing Recovery Console manual flow:

1. user inspects the exact claim;
2. user verifies device/real-world state;
3. user enters a required note;
4. user explicitly chooses confirmed executed or confirmed not executed;
5. `PersistentSovereignPlanCoordinator.reconcile` persists the canonical reconciliation and terminal
   receipt without replaying the provider.

Phase666 adds no automatic reconcile action and no retry/replay control.

## Architecture locks

Phase666 adds:

- `proactive-recovery-target-exists-only-for-unresolved-canonical-claim`
- `proactive-recovery-target-binds-plan-step-and-request-identity`
- `proactive-recovery-navigation-revalidates-current-canonical-recovery-state`
- `stale-proactive-recovery-target-never-falls-through-to-another-claim`
- `proactive-recovery-navigation-reuses-existing-sovereign-recovery-console`
- `proactive-recovery-navigation-never-reconciles-or-replays-provider`

M5 exit criteria now require exact target identity, current-state revalidation, fail-closed stale
handling, focus-only navigation, and continued reuse of the canonical Recovery Console.

## Tests

Phase666 covers:

- exact matching target resolves the current claim;
- step mismatch resolves no claim;
- request-id mismatch resolves no claim;
- plan-id mismatch resolves no claim;
- navigation/lookup does not invoke a provider;
- terminal receipt history has no recovery target;
- unresolved claim history carries the exact canonical target;
- Omega architecture locks and M5 exit criteria.

## Architecture audit

Base:

`main@87a66035de2e9ca9f2784c82a571510b38fdf105`

Phase666 changes only recovery navigation/focus semantics, projection metadata, tests, architecture
locks, and this document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2;
- Titan;
- ToolFabric or authority policy;
- receipt persistence format;
- lifecycle persistence;
- trigger/FIFO persistence;
- Phase655/656/657 execution.

The product architecture remains:

```
one AMPER foundation
  -> AMI2 / AMNE2
  -> Titan
  -> audited ToolFabric
  -> DenyByDefaultAuthorityGate
  -> SovereignPlanCoordinator
  -> PersistentSovereignPlanCoordinator
```

GGUF remains import-source weights only.

## CI gate

Use one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because no native/build-impact file is changed.

Merge only when the verified CI head equals the PR head.

## Next M5 slice

Phase667 should harden proactive user-facing task controls and status refresh around lifecycle/history
transitions without adding a second task mutation path. Canonical approval, cancellation, recovery,
EVENT_WAKE scheduling, and execution ownership must remain unchanged.
