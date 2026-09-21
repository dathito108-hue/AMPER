# Phase653 — Cold-Process/Reboot Canonical Agent Bootstrap

Phase653 removes the remaining warm-process dependency from persisted Agent continuation. A
JobScheduler wake may now resume one verified persistent sovereign-plan step even when the AMPER
activity/process was previously absent or the device rebooted.

## Audit result

Phase652 already persisted continuation handoffs and scheduled `AgentContinuationJobService` with
`JobInfo.setPersisted(true)`, while the manifest already declared
`android.permission.RECEIVE_BOOT_COMPLETED`. The missing link was runtime construction:
`AmperAgentCanonicalContinuationExecutionPort` existed only inside `MainActivity` and the
process registry was therefore empty in a cold process.

Adding a reduced background planner/tool executor would have duplicated authority and was rejected.

## One canonical Android sovereign graph

`AndroidCanonicalSovereignRuntimeBootstrap` now owns the process-shared construction used by both
foreground and cold persisted-job execution:

`AmperRuntime.persistentEncrypted`
→ one `AmperSingleCoreModelRegistry` foundation
→ canonical AMI2 artifact lookup with legacy AMI migration only as an import/migration bridge
→ AMNE-backed `AmperCoreInferencePort`
→ `TitanCortexRuntime`
→ one Android tool registry
→ `AuditedToolFabric`
→ `DenyByDefaultAuthorityGate`
→ `SovereignPlanCoordinator`
→ `PersistentSovereignPlanCoordinator`
→ `PersistentSovereignAgentPlanPort`
→ `AmperAgentCanonicalContinuationExecutionPort`.

`MainActivity` now acquires this graph instead of rebuilding those layers itself. A cold JobService
acquires the same bootstrap, so there is no background-only model endpoint, planner, ToolFabric,
AuthorityGate, plan store, or executor.

## Verified-ready cold fallback

The Phase651 dispatcher now accepts an optional execution fallback. It is evaluated only after the
handoff has passed Phase650 verification and only for
`READY_FOR_EXPLICIT_ADVANCE`.

Therefore:

- `WAITING_GOVERNED_APPROVAL` never constructs the cold execution graph and never calls a tool;
- terminal handoffs remain no-op;
- tampered handoffs fail before the fallback is evaluated;
- a ready persisted job can acquire the canonical graph when no warm process-registry port exists.

## One wake remains one step

Cold bootstrap does not change Phase652 execution semantics. The same continuation execution port:

1. restores the exact hash-bound Phase649 checkpoint;
2. reconstructs only the narrow admission already represented by the durable plan when RAM state is
   absent;
3. calls the persistent passive-plan advance exactly once;
4. returns a fresh verified checkpoint handoff only when additional work remains.

`WAITING_APPROVAL` returns no next handoff, so the JobService cannot chain across approval.

## Reboot behavior

No new boot receiver or replay queue is introduced. Persisted Android jobs already use
`JobInfo.setPersisted(true)`, and the app already holds `RECEIVE_BOOT_COMPLETED`. Android restores
the persisted JobScheduler entry after reboot; when it fires, the JobService cold-bootstraps the same
canonical runtime graph and executes at most one verified step.

This avoids a second boot-time scheduling path and preserves the existing stable job-id/dedupe
contract.

## Architecture lock

Phase653 adds the invariant:

`cold-persisted-agent-wakes-rebuild-the-same-canonical-runtime-graph`

M5 now explicitly records that persisted JobService continuation cold-starts the same
single-foundation runtime graph after process death or reboot.

## Next M5 slice

After Phase653, the remaining M5 work should continue from the canonical Agent task contract rather
than adding another Android execution stack. Proactive triggers and longer-lived task orchestration
must emit the same governed admissions, persistent sovereign plans, verified checkpoints, and
one-step Android handoffs established by Phases647–653.
