# Phase670 — M5 Canonical Closure Audit

Phase670 closes the M5 Agent Core / Always-On milestone by auditing the canonical implementation
built continuously from Phase647 through Phase669.

It adds no runtime execution path.

## Closure result

The canonical chain is complete for the current M5 scope:

```
Phase647 canonical task contract
  -> Phase648 passive task / persistent sovereign plan
  -> Phase649 durable execution continuation
  -> Phase650 verified Android handoff
  -> Phase651 dedicated Android hosts
  -> Phase652 canonical continuation execution port
  -> Phase653 cold-process / reboot canonical bootstrap
  -> Phase654 proactive task / persistent plan
  -> Phase655 verified EVENT_WAKE handoff
  -> Phase656 one-step canonical EVENT_WAKE consumer
  -> Phase657 governed Android EVENT_WAKE scheduler
  -> Phase658 bounded user-configured trigger source
  -> Phase659 encrypted persistent trigger registry
  -> Phase660 Android trigger-source reconciliation
  -> Phase661 durable trigger FIFO / deterministic dispatch
  -> Phase662 canonical lifecycle provenance surface
  -> Phase663 proactive attention discoverability
  -> Phase664 durable revision-scoped attention acknowledgement
  -> Phase665 read-only history + canonical receipt presentation
  -> Phase666 exact canonical recovery navigation
  -> Phase667 read-only controls + status refresh
  -> Phase668 bounded optimistic snapshot coherence
  -> Phase669 ON_RESUME foreground visibility
```

No slice introduces another planner, model backend, ToolFabric, AuthorityGate, mutable task database,
receipt database, or execution scheduler.

## Closure manifest

`OmegaM5CanonicalClosure` is metadata/test-only. It enumerates every build slice from
Phase647 through Phase669 with:

- one capability label;
- one canonical owner;
- one representative architecture invariant.

The manifest requires exact contiguous phase coverage `647..669`.

It does not instantiate any owner and does not participate in runtime dispatch.

## Foundational constraints

The closure audit additionally requires:

- one AMPER foundation runtime;
- Agent Core never owns tool authority;
- duplicate wake execution remains serialized against replay;
- GGUF remains import-source weights only;
- internet remains a governed tool, not a model/backend;
- background work remains checkpointed;
- foreground work survives UI exit;
- agent actions remain audited.

This prevents M5 from being declared complete only because individual Android features exist while
the global OMEGA architecture has drifted.

## M5 exit criteria

The audit requires representative milestone criteria spanning:

- canonical USER_REQUEST + PROACTIVE_TRIGGER task contract;
- cold persisted JobService bootstrap;
- canonical proactive EVENT_WAKE;
- fresh-checkpoint-only chaining;
- bounded user-configured trigger sources;
- encrypted trigger FIFO;
- provenance-only lifecycle metadata;
- discoverability-only attention;
- revision-scoped attention acknowledgement;
- read-only history/receipt projection;
- reuse of canonical Recovery Console;
- refresh/navigation-only proactive controls;
- bounded optimistic history coherence;
- lifecycle-driven foreground visibility;
- passive tool execution;
- proactive goals/triggers;
- foreground continuation;
- persisted reboot restoration;
- internet observation + checkpointing.

The readiness result is computed from existing architecture locks and M5 exit criteria. No mutable
"complete" flag is persisted.

## Failure semantics

The closure audit fails when:

- Phase647..669 coverage is not contiguous;
- any representative slice invariant disappears;
- any foundational single-foundation/authority invariant disappears;
- any required M5 exit criterion disappears.

Tests explicitly remove one slice invariant, one foundational invariant, and one exit criterion to
prove the closure cannot silently remain green after architectural regression.

## Architecture locks

Phase670 adds:

- `m5-always-on-canonical-chain-has-closure-audit`

M5 also gains the exit criterion:

`M5 canonical closure audit covers every Phase647 through Phase669 slice plus foundational single-foundation authority background and internet constraints`

## Architecture audit

Base:

`main@623fb99a9b89297dab963633ba31a1de505a6a19`

Phase670 changes only closure metadata, unit tests, Omega locks, and this document.

It does not modify:

- `app/src/amneNative/**`;
- `app/build.gradle.kts`;
- Android JobService/foreground-service execution;
- Phase657 EVENT_WAKE scheduling;
- trigger observation or FIFO persistence;
- plan/receipt/lifecycle/attention persistence;
- AMI2/AMNE2;
- Titan;
- ToolFabric;
- `DenyByDefaultAuthorityGate`;
- `SovereignPlanCoordinator`;
- `PersistentSovereignPlanCoordinator`.

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

## M5 decision

With the canonical Phase647..669 implementation and Phase670 closure audit green, M5 Agent Core /
Always-On is ready to close.

The next roadmap milestone is M6 Cognitive Memory. Phase671 should begin with a canonical cognitive
memory contract over the existing working / episodic / semantic / procedural memory foundations,
without creating a second MemoryOs or a model/backend-specific memory silo.

## CI gate

Use one pull-request-triggered Android CI run.

Required merge gate:

- `verify` PASS;
- unit tests PASS;
- `Build canonical debug APK` PASS;
- `amper-core-arm64` SKIP because no native/build-impact file is changed.

Merge only when the verified CI head equals the PR head.
