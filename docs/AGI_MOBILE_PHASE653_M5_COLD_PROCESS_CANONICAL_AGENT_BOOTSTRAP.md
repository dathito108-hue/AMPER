# Phase653 — M5 Cold-Process / Reboot Canonical Agent Bootstrap

Phase653 lets Android Agent continuation recover when the Activity process graph is not already
registered, including persisted JobScheduler wakes after process death or reboot.

## One sovereign execution graph

Phase653 extracts `AndroidSovereignAgentExecutionGraphFactory`.

Both MainActivity and cold continuation bootstrap now construct the same:

- canonical assistant capability exposure;
- Android tool registry;
- deny-by-default authority gate;
- audited ToolFabric;
- sovereign action loop;
- Titan inference port over the single AMPER Core;
- sovereign planner;
- persistent sovereign-plan coordinator;
- Phase648 Agent plan adapter;
- Phase649 continuation coordinator;
- Phase652 canonical one-step execution port.

Cold execution does not own a reduced tool set or background-only planner.

## Cold foundation/runtime reconstruction

When no warm execution port is registered, `AndroidAgentColdExecutionBootstrap` reconstructs:

1. canonical app-private storage;
2. installed model catalog;
3. one-slot AMPER foundation registry;
4. selected foundation identity from canonical preference, with legacy preference only as migration
   fallback;
5. encrypted AmperRuntime;
6. the selected single foundation again after runtime placeholder initialization;
7. original content/app-private model artifact resolvers;
8. canonical AMI2 artifact store with legacy AMI1 migration fallback only;
9. Android hardware profile;
10. one AMPER Core inference endpoint;
11. Titan over that one endpoint;
12. the shared sovereign Agent execution graph factory.

If native AMNE qualification fails, the cold host does not enter an infinite job retry loop; the
runtime remains fail-closed to already-qualified/reference execution behavior.

## Wake behavior

Android hosts now resolve execution in this order:

- warm registered canonical execution port, when present;
- WAITING_APPROVAL/terminal no-op directly, without opening a cold model runtime;
- otherwise the canonical cold bootstrap.

A second warm-port check occurs after acquiring the cold bootstrap lock to avoid unnecessary cold
construction during an Activity-start race.

## Duplicate wake safety

`AmperAgentCanonicalContinuationExecutionGate` serializes the complete exact-restore -> one-step
advance transaction inside a process.

A concurrency test sends the same old continuation checkpoint twice at once:

- one wake advances exactly one persistent step;
- the second wake sees the changed durable-plan digest and fails;
- total persistent advances remain exactly one.

The Phase649 semantic digest remains the authoritative stale/replay guard.

## Resource lifecycle

Cold execution is serialized and scoped to one Android wake. After dispatch, Titan unloads its live
runtime state. Durable sovereign memory/plans remain in their existing encrypted/persistent stores.

## Architecture lock

Phase653 adds:

`cold-agent-wakes-rebuild-the-same-sovereign-execution-graph`

M5 now explicitly requires cold process/reboot restoration to use the same sovereign tool, planner,
and inference graph as the interactive host.

## Next M5 slice

Phase654 should persist the Agent task admission envelope itself so cold restoration no longer needs
to reconstruct a narrow admission from plan contents. The durable admission should be identity-bound
to task id, origin, allowed capabilities, background mode and the persistent plan, while remaining
separate from execution authority and preserving exact WAITING_APPROVAL state.
