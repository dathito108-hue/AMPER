# Phase637 — M3 Memory / Paging Budget Governor

Phase637 prevents AMNE2 from treating a model's advertised maximum context as automatically safe on
every phone.

## Portable device budget

The governor derives one execution-session budget from the existing portable hardware snapshot:

- Android memory class;
- low-RAM classification;
- logical hardware identity already used by the Phase636 autotuner.

No phone model name or OEM-specific allowlist is used.

A process headroom reserve is kept outside the AMNE2 session. The remaining session budget is capped
so larger devices do not cause unbounded KV/mmap growth.

## Context admission from real decoder geometry

The governor uses the already-qualified decoder plan:

- per-layer KV widths;
- hidden width;
- maximum query width;
- maximum FFN width;
- advertised model context.

It reuses the existing AMI request-memory estimator and binary-searches the largest context capacity
whose KV + transient working estimate fits the device session budget.

The advertised model context remains an upper bound only.

## Bounded mmap windows

The AMNE2 mmap window is scaled from the same session budget and is always capped by the configured
AMNE2 maximum.

Low-RAM devices use a more conservative window fraction.

The execution view accepts this smaller requested window, and the session executor uses the exact
same admitted window. Large AMI2 weights remain file-backed and windowed.

## Session integration

Session creation now follows:

verified AMI2
-> Phase636 hardware autotune
-> hardware-bounded mmap view
-> decoder semantic binding
-> decoder plan
-> memory-budget admission
-> KV state with safe context capacity
-> AMNE2 execution session

The session exposes its admitted memory budget for diagnostics/telemetry.

Existing callers may still request a smaller context cap. A larger requested cap is clamped to the
safe device capacity rather than overcommitting mobile memory.

## Fail-closed behavior

If even the minimum decoder context cannot fit the calculated session budget, session creation fails
before inference instead of allowing progressive memory exhaustion.

This changes only execution capacity. It does not change:

- foundation identity;
- tokenizer or chat protocol;
- graph semantics;
- weights;
- model routing;
- AMNE2 decoder correctness.

## Phase637 exit criteria

Phase637 is complete when:

- session context is bounded by decoder geometry plus portable device memory budget;
- mmap window size is bounded by the same hardware budget;
- low-RAM devices retain more process headroom;
- larger memory classes never reduce the safe context for identical geometry;
- extremely constrained devices fail closed before execution;
- the single AMNE2 session/foundation invariant remains unchanged.

## Next M3 slice

Phase638 should add explicit context-pressure behavior for long conversations: preserve the exact
single-session semantics while deciding when a hot session must be rebuilt because a new turn exceeds
the admitted context budget. The policy should prefer deterministic conversation compaction/checkpoint
handoff over hidden KV eviction.
