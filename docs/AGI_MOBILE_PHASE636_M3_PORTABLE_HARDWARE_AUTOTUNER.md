# Phase636 — M3 Portable Hardware Autotuner

Phase636 activates measured, fail-closed hardware selection for AMNE2 without adding another model
runtime or decoder.

## Process-local hardware fingerprint

AMNE2 fingerprints the current device by:

- ARM64/NEON/DOTPROD/I8MM/FP16/Vulkan capability flags already exposed by the canonical hardware
  profiler;
- logical processor count;
- Android memory class;
- low-RAM classification.

The fingerprint is portable and never depends on phone model names.

## Reference-first correctness

Before tuning a new fingerprint, AMNE2 resets dispatch to the deterministic reference CPU kernels.

The optional packaged ARM64 native backend is eligible only when:

1. ARM64 + NEON are actually present;
2. the optional native backend is packaged;
3. numerical qualification passes against the deterministic reference;
4. device-local microbenchmarks show the required speedup for each primitive.

Primitive admission remains individual. Any primitive that does not beat the reference path stays on
the reference backend.

If discovery, qualification or benchmarking fails, AMNE2 keeps the reference path and caches that
safe decision for the same process-local hardware fingerprint.

## Session integration

`Amne2ExecutionSessionFactory` invokes the process-local autotuner before opening the verified AMI2
execution view. The existing decoder already resolves kernels from
`AmneProcessKernelRuntime.registry()`, so attention and FFN execution consume the qualified registry
without introducing a second decoder or dispatch architecture.

The same fingerprint is not benchmarked again for every chat turn/session.

## Vulkan gate

Phase636 does not pretend that a Vulkan execution backend exists when it does not.

`Amne2VulkanAdmissionPolicy` permits Vulkan only when all of the following are true:

- the device advertises Vulkan;
- evidence was measured on a physical device;
- numerical qualification passed;
- Vulkan is faster than the qualified CPU reference;
- speedup meets the configured minimum margin.

Until a real Vulkan AMNE2 backend supplies that evidence, Vulkan remains non-routable.

## Phase636 exit criteria

Phase636 is complete when:

- AMNE2 autotuning runs before execution-session creation;
- hardware identity is portable rather than device-name based;
- deterministic reference kernels remain the correctness baseline;
- optional native execution is admitted only by numerical + measured performance evidence;
- failed tuning falls back to reference without failing inference;
- repeated sessions on the same hardware fingerprint do not repeat benchmarks;
- Vulkan has an explicit physical-device benchmark gate.

## Next M3 slice

Phase637 should harden AMNE2 memory/paging policy around real device memory pressure: derive bounded
mmap/KV budgets from the portable hardware snapshot, prevent context requests from overcommitting
mobile memory, and preserve the single-session foundation invariant.
