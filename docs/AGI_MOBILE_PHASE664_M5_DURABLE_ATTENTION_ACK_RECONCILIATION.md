# Phase664 — M5 Durable Attention Acknowledgement / Reconciliation

Phase664 hardens the Phase663 notification surface so a user can acknowledge one exact proactive
attention revision without creating any new execution, approval, scheduling, or task-state authority.

Canonical execution ownership remains unchanged:

```
Phase658 bounded trigger source
  -> Phase659/660 encrypted source record + FIFO
  -> Phase661 deterministic canonical persistent-plan binding
  -> Phase662 immutable lifecycle provenance + canonical plan-derived state
  -> Phase655 verified EVENT_WAKE handoff
  -> Phase657 Android EVENT_WAKE scheduler
  -> Phase656 one-step canonical consumer
```

Phase663 derives discoverability from that chain. Phase664 stores only which exact discoverability
revision the user has already opened.

## Revision identity

`AmperAgentProactiveAttentionRevision` hashes only:

- full canonical `PlanId`;
- attention kind;
- waiting approval step index, or `-1`;
- accepted observation epoch.

It deliberately excludes goal text, source id, trigger payload, tool input, plan contents, and user
prompt.

A change from approval-required to completed/failed, a different approval step, another observation,
or another plan therefore produces a different revision.

## Durable acknowledgement ledger

`MemoryBackedAmperAgentProactiveAttentionAcknowledgementLedger` lives in the existing sovereign
`MemoryOs`, which is already encrypted in Android production through
`AmperRuntime.persistentEncrypted`.

Each entry stores only:

```
PlanId
revisionSha256
acknowledgedAtEpochMs
```

It stores no task state, planner state, approval decision, scheduler state, tool receipt, goal,
trigger payload, or tool input.

The ledger is bounded to 64 PlanIds. A new entry never silently evicts another acknowledgement.
Same-PlanId acknowledgement replaces only the old revision for that same plan. Full lifecycle
reconciliation prunes acknowledgement entries only after the corresponding PlanId is no longer in
the Phase662 lifecycle set.

## Exact notification acknowledgement

Phase664 adds the revision SHA-256 as an internal immutable PendingIntent extra next to the already
validated canonical PlanId.

On notification open:

1. MainActivity validates the PlanId and revision syntax.
2. The Phase663 lifecycle view is re-read from canonical persistent plan state.
3. The current attention decision is derived again.
4. Acknowledgement is persisted only if the notification revision still exactly matches the current
   canonical attention revision.
5. The notification is cancelled only after the acknowledgement record is durable.

A stale notification can therefore open the plan but cannot acknowledge a newer state.

Acknowledgement never approves or rejects a governed step. Approval remains exclusively on the
existing governed plan surface.

## Reconciliation semantics

Before posting an attention notification, the Android controller checks the acknowledgement ledger.

- matching current revision -> cancel/suppress that repeated notification;
- different or absent revision -> normal Phase663 surface policy applies;
- corrupt/unreadable acknowledgement state -> fail visible and continue surfacing attention.

This affects only discoverability. It does not alter canonical plan state, Phase655 handoffs,
Phase657 jobs, Phase656 one-step execution, Phase660 FIFO acknowledgement, ToolFabric, or
DenyByDefaultAuthorityGate.

## Architecture locks

Phase664 adds:

- `proactive-attention-ack-is-revision-scoped-ui-state-only`
- `proactive-attention-ack-lives-in-existing-encrypted-sovereign-memory`
- `stale-attention-revision-cannot-ack-new-canonical-state`
- `acknowledged-attention-never-mutates-plan-approval-scheduler-or-fifo`
- `attention-ack-corruption-fails-visible`
- `attention-ack-ledger-is-bounded-without-silent-eviction`
- `attention-ack-pruning-follows-lifecycle-membership-only`

M5 exit criteria require exact revision matching, encrypted bounded persistence, fail-visible
corruption semantics, no silent eviction, lifecycle-only pruning, and zero execution authority.

## Tests

Phase664 tests cover:

- revision stability when only source/goal text changes;
- revision changes for kind, approval step, or observation changes;
- stale revision rejection against newer canonical attention;
- idempotent acknowledgement of one revision;
- replacement by a newer revision for the same PlanId;
- bounded 64-entry capacity with no silent eviction;
- pruning only untracked PlanIds;
- corrupt acknowledgement ledger fails visible;
- strict lowercase 64-hex revision intent parsing;
- Omega architecture locks and M5 exit criteria.

## Architecture audit

Base:

`main@fc5c4cbc69eec5258392dd6273fd0118af1653e2`

Phase664 does not touch:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- AMI2/AMNE2 execution semantics;
- Titan routing;
- ToolFabric or authority policy;
- persistent sovereign plan semantics;
- trigger FIFO semantics;
- Phase655/656/657 execution contracts.

The canonical product architecture remains:

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

Phase664 must use one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because no native/build-impact file is changed.

Merge only after those gates are confirmed.

## Next M5 slice

Phase665 should move from discoverability durability to bounded proactive task history/receipt
presentation without duplicating canonical plan receipts or creating a second task database.
