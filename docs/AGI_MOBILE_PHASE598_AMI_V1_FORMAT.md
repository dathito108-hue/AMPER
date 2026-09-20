# Phase598 — AMPER Mobile Intelligence Format (AMI v1)

## Decision

AMPER now has a canonical mobile-model standard of its own: **AMPER Mobile Intelligence (AMI)**,
file extension `.ami`.

GGUF is an import/source format. It is not the long-term AMPER runtime contract.

The goal is a mobile-first representation that is:

- Android ARM64 native;
- mmap-friendly;
- fast to validate and load;
- capable of storing CPU/Vulkan prepacked execution profiles;
- explicit about precision and conversion fidelity;
- able to preserve imported foundation intelligence while AMPER learning remains separate.

## Intelligence preservation

An AMI v1 file must contain exactly one `FOUNDATION_WEIGHTS` section.

That section is canonical and hardware-independent. It cannot be replaced by a device-specific
execution profile.

The default conversion mode is `SOURCE_EXACT`:

```
GGUF source weights
    -> canonical AMI foundation weights (same represented numerical weights)
    -> optional layout/prepack execution profiles
```

Lossless repacking may change tensor ordering, block ordering, alignment, padding or pretranspose
layout, but it must not silently requantize the canonical foundation.

If a future user explicitly chooses a smaller lossy mobile profile, that choice is represented as a
different precision policy. It never rewrites the provenance claim of the canonical foundation.

## One mobile container, multiple execution profiles

AMI remains one standard and one container. Optional `EXECUTION_PROFILE` sections can be embedded
for different mobile hardware paths, for example:

- ARM64 + NEON baseline;
- ARM64 + DOTPROD;
- ARM64 + I8MM;
- ARM64 FP16;
- Vulkan FP16;
- Vulkan INT8.

The runtime selects only a profile whose required hardware features were actually detected on the
device. If no profile matches, AMPER can use the canonical foundation representation through a
portable kernel path.

Execution profiles are acceleration representations. They are rebuildable and disposable. They are
not the sole copy of the model's intelligence.

## Android-oriented layout rules

Runtime tensor sections are page aligned (minimum 4096 bytes) for direct mmap-friendly access.
Tensor payload alignment is at least 64 bytes for common ARM cache-line/SIMD-friendly access.

AMI v1 defines bounded section/profile counts so corrupted input cannot manufacture an unbounded
loader allocation before validation.

Every section has a SHA-256 digest. The original source format, source digest and source byte length
are retained as immutable lineage metadata.

## Mobile context tiers

AMI v1 carries explicit supported context tiers rather than assuming one permanent maximum context.
The default tiers are:

`512 / 1024 / 2048 / 4096`

AMNE will choose a tier from live memory/thermal/workload state. A short assistant turn therefore
does not need to reserve the same KV/context resources as a large planning or code task.

## Learning separation

AMPER-owned learning is a separate `ADAPTATION_DELTA` section/layer.

Conceptually:

```
effective intelligence
  = immutable imported foundation
  + AMPER adaptation delta
  + long-term memory / learned skills / Reflex
```

This prevents on-device learning from destructively overwriting the imported foundation and enables
rollback, comparison and later consolidation.

## Accuracy statement

A file format by itself cannot make a model more intelligent or mathematically more accurate than
its source weights. AMI improves **execution fidelity and mobile efficiency** by avoiding unnecessary
conversion/requantization, keeping sensitive tensors at appropriate precision, and allowing AMNE to
use a hardware-appropriate layout.

The canonical default therefore prioritizes preservation first, then speed through lossless
prepacking. Lossy quantization must always be explicit.

## Roadmap

Phase598 freezes the AMI semantic container contract and validator.

Next implementation stages:

1. GGUF -> AMI architecture/metadata/tensor compiler.
2. Native AMI reader with descriptor validation and mmap.
3. ARM64 baseline kernels.
4. Hardware profiler + profile selector.
5. DOTPROD/I8MM/FP16 kernels.
6. Vulkan execution profile.
7. AMNE Titan backend.
8. Adaptation-delta training/consolidation.
