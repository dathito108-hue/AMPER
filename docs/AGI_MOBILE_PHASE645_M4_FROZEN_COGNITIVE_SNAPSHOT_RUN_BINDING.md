# Phase645 — M4 Frozen Cognitive Snapshot Run Binding

Phase645 binds one immutable integrated cognitive snapshot to one complete AMCF run.

## One capture per run

`AmcfFrozenCognitiveRunCoordinator` captures the integrated cognitive state exactly once before the
first cycle executes. The snapshot is immutable for the lifetime of the run and is reused by every
DELIBERATE, VERIFY, REVISE, and FINALIZE cycle.

This prevents mutable world, memory, perception, or readiness state from being recaptured between
cycles and changing the quality basis mid-run.

## Frozen quality and bounded guidance

The snapshot contains:

- canonical cognitive-state digest;
- query digest;
- capture timestamp;
- deterministic Phase644 quality signals;
- bounded rendered cognitive guidance.

The production AMCF port receives the same frozen snapshot for every cycle. Prompts expose only the
snapshot digest, query digest, and bounded rendered guidance; no tool authority or approval state is
embedded.

## Diagnostics

Successful runs expose bounded diagnostics only:

- foundation id and semantic SHA-256;
- cognitive snapshot digest and query digest;
- compute mode;
- planned/committed cycle counts;
- early-exit decision count;
- terminal recurrent-state digest.

Diagnostics do not expose hidden reasoning, candidate prose, tool handles, or authority.

## Architecture lock

Phase645 adds the invariant:

`amcf-run-cognitive-snapshot-is-frozen`

and records the M4 exit criterion that one immutable integrated cognitive snapshot is bound to the
complete AMCF run.

## Exit criteria

Phase645 is complete when:

- foundation mismatch fails before cognitive capture;
- one run captures cognitive state exactly once;
- every production cycle sees the same cognitive-state digest and bounded guidance;
- Phase644 quality signals remain identical across the run;
- early exit can still operate from the frozen snapshot;
- diagnostics remain bounded and digest/count based;
- no new model, judge, runtime, backend, tool-authority, or persistent memory path is added.

## Next M4 slice

Phase646 should evaluate M4 against the complete roadmap exit criteria and close the milestone only if
FAST/REASON/DEEP/VERIFY plans, recurrent state, early exit, verification/revision, production
AMI2/AMNE2 execution, and frozen integrated cognitive quality are all demonstrated by one
consolidated qualification surface.
