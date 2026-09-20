# Phase603 — Native ARM64/NEON AMNE Kernels

Phase603 is the first native execution layer for AMPER Mobile Neural Engine.

## Runtime packaging

A new optional Gradle mode is available:

`-PwithAmneNative=true`

It builds `libamper_amne.so` for `arm64-v8a`.

AMNE native is independent of the MTMD native runtime and may coexist with the prebuilt
`llama.cpp-aar` text backend. This lets AMPER qualify its own execution kernels while preserving
the currently working assistant fallback.

## Phase603 primitive set

The native backend declares:

- DOT_F32
- MATVEC_F32
- RMS_NORM_F32
- SILU_F32
- SWIGLU_F32
- SOFTMAX_F32
- ROPE_F32

DOT and dense MATVEC use ARM NEON vector intrinsics. The remaining Phase603 primitives execute in the
same native library with correctness-first scalar math.

Q4_0 and Q8_0 are deliberately not declared by the native backend yet. Calls on the class itself
fall back to the Phase602 deterministic reference implementation, while registry dispatch cannot
select the native backend for those quantized primitives.

## Fail-closed device qualification

The AMNE-enabled APK exposes:

`Run AMNE native qualification`

The qualification compares every declared Phase603 primitive against the Phase602 reference backend.

The native backend is considered qualified only when:

- the JNI/native ABI version matches;
- every expected primitive is declared;
- every result is finite;
- maximum absolute error stays within the Phase603 tolerance.

A failed qualification does not enable native routing.

## CI isolation

Native impact detection is split between AMNE and MTMD.

AMNE changes run one dedicated ARM64 job that builds:

`AMNE native + llama.cpp-aar fallback`

The job verifies both `libamper_amne.so` and the packaged llama fallback. MTMD CPU/Vulkan jobs are
not run for AMNE-only changes, conserving GitHub Actions minutes.

## Next

Phase604 adds quantized ARM64 kernels for Q4_0/Q8_0 and a device microbenchmark used by the AMNE
dispatcher to decide whether native or reference execution is faster for each primitive.

Phase605 then begins the first AMI decoder block execution over verified tensor mmap windows.
