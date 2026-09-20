# Phase639 — M3 Consolidated Runtime Readiness / Telemetry

Phase639 closes Milestone 3 by making the production AMNE2 runtime observable through one
model-specific readiness snapshot.

## Consolidated readiness surface

The snapshot exposes:

- verified AMI2 foundation id;
- foundation semantic SHA-256;
- verified AMI2 artifact SHA-256;
- original source lineage SHA-256;
- model maximum context;
- Phase637 hardware-safe context;
- mmap window budget;
- memory class, reserved process headroom, and AMNE2 session budget;
- required decoder matrix primitives;
- the actual backend selected for each required primitive;
- any required matrix primitive still using deterministic reference kernels;
- current hot-session state without exposing conversation content;
- explicit structured degraded reasons.

This is a telemetry/readiness contract only. It does not retune hardware, open a second session,
modify KV, select another model, or alter execution semantics.

## Model-specific readiness

The production backend now exposes `runtimeReadiness(model)`.

Unlike the older coarse backend health signal, this readiness is tied to the exact prepared model and
therefore can answer whether all matrix primitives required by that model are on admitted accelerated
paths.

If any required matrix primitive resolves to the deterministic reference backend, readiness is
DEGRADED and a stable degraded reason is emitted for that primitive.

A fully qualified matrix path reports READY.

## Hot-session observability

Hot-session telemetry reports only:

- NONE;
- ACTIVE_MATCHING_MODEL;
- ACTIVE_OTHER_MODEL;
- committed token count;
- KV position;
- session context capacity.

KV position and committed token history must match exactly. A mismatch is rejected rather than
reported as healthy telemetry.

No prompt text, generated text, or conversation content is included.

## Memory observability

The readiness snapshot reuses the Phase637 admitted memory budget exactly. It does not recompute or
inflate context capacity.

Therefore diagnostics show both:

- modelMaxContextTokens;
- safeContextTokens.

This makes device-driven context reduction visible instead of silently presenting the model's
advertised maximum as available.

## Phase639 exit criteria

Phase639 is complete when:

- readiness binds to the verified foundation and artifact identity;
- memory/context/mmap budget is exposed from the admitted AMNE2 session budget;
- required matrix primitive dispatch is model-specific and visible;
- reference-only matrix paths generate explicit degraded reasons;
- hot-session state is observable without exposing conversation content;
- telemetry is side-effect free;
- M3 remains one AMI2 foundation and one AMNE2 execution path.

## M3 closure

With Phase639, the M3 AMNE2 runtime milestone is observable end-to-end:

verified AMI2
-> bounded mmap execution view
-> decoder semantic binding
-> AMNE2 execution session
-> conversation hot-state reuse
-> measured hardware autotuning
-> portable memory/context budget
-> explicit context-pressure policy
-> consolidated runtime readiness

The next roadmap milestone is M4 — AMCF Foundation.

## Next slice

Phase640 should begin M4 by introducing the AMCF compute-cycle contract that maps the existing OMEGA
compute modes (FAST/STANDARD/REASON/DEEP/VERIFY) into bounded recurrent reasoning cycles over the same
single AMPER foundation. It must not introduce a second model, second decoder, or hidden remote
reasoning backend.
