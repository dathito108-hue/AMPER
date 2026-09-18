# Persistent Plan OS — Phase 18

Phase 18 makes bounded sovereign plans survive Android activity/process recreation without creating a second persistence silo.

## Storage model

`MemoryBackedSovereignPlanStore` writes immutable `SovereignPlan` snapshots into AMPER's existing `MemoryOs` using stable memory ids (`sovereign-plan:<plan-id>`). Android production already constructs that memory through `EncryptedFileMemoryJournal` + `AndroidKeystoreMemoryLineCipher`, so plan goals, reasons, inputs, step state and tool outcomes share the same AES-GCM encrypted journal and non-exported AndroidKeyStore key as sovereign memory.

The plan codec persists:
- plan id, conversation id, goal, planning backend and creation time
- stable per-step `ActionRequestId`
- capability, reason and typed input
- current step status
- bounded action outcome/tool/output/detail when present

Restoring a plan never executes a tool. A `REQUIRES_CONFIRMATION` step remains blocked after restart with the same request id.

## Execution invariant

`PersistentSovereignPlanCoordinator` wraps the Phase 17 bounded planner. It persists after exactly one delegate operation:
- `create()` performs planning inference, zero tool calls, then stores the plan
- `advance()` processes at most one step, then stores the resulting snapshot
- `approve()` executes exactly one previously blocked step through `SovereignActionLoop -> AuditedToolFabric -> AuthorityGate`, then stores it
- `reject()` invokes no tool and stores the rejected state

There is intentionally no `runAll`, background continuation, recursive planner loop or automatic side-effect approval.

## Android UI

The main screen now restores the latest encrypted plan at startup and exposes explicit controls:
- Create governed plan
- Restore latest plan
- Advance one step
- Approve plan step
- Reject plan step

Each step's persisted status is visible. The screen is scrollable so plan controls coexist with the existing model import, assistant and audit status UI.
