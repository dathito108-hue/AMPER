# Phase605 — Device Microbenchmark + Qualified AMNE Dispatch

Phase605 changes AMNE dispatch from hardware-only preference to a fail-closed device admission model.

## Admission rule

A non-reference AMNE backend is routable for a primitive only when all of these are true:

1. the backend is numerically qualified against the deterministic Phase602 reference;
2. the current device exposes every required hardware feature;
3. the primitive was benchmarked on the current process/device state;
4. candidate median latency is lower than reference median latency;
5. speedup is at least 1.05x.

No admission is persisted across app restarts. Thermal state, OS scheduling and runtime versions can
change, so each process starts from the deterministic reference path until a fresh benchmark grants
admission.

## Representative mobile workloads

The benchmark uses decoder-like tensor sizes instead of tiny correctness vectors:

- DOT_F32: 4096 elements;
- MATVEC_F32: 128 x 1024;
- MATVEC_Q4_0: 128 x 1024 source blocks;
- MATVEC_Q8_0: 128 x 1024 source blocks;
- RMS_NORM_F32: hidden width 4096;
- SILU_F32 / SWIGLU_F32: width 11008;
- SOFTMAX_F32: width 1024;
- ROPE_F32: width 128.

Five samples are measured per primitive after two warmup rounds. Candidate/reference measurement
order alternates to reduce systematic order and thermal bias. Dispatch uses the median.

## Process-local routing

`AmneProcessKernelRuntime` owns the currently admitted immutable registry.

Before benchmark admission:

`all primitives -> amne-reference-cpu-v1`

After admission:

`primitive -> native only if that exact primitive passed qualification + speed threshold`

A native backend can therefore win Q4_0/Q8_0 matrix multiplication while RMSNorm or Softmax remain on
the reference backend if JNI/native overhead makes those paths slower.

## Physical Android control

AMNE-enabled builds expose:

`Benchmark & admit AMNE`

Status reports:

- numerical qualification PASS/FAIL;
- admitted primitive count;
- F32/Q4_0/Q8_0 matrix speedups;
- an asterisk for each matrix path admitted to native routing;
- total qualification + benchmark wall time.

The existing `Run AMNE native qualification` remains available as a correctness-only check and does
not itself grant routing admission.

## Safety / correctness

Hardware presence alone no longer enables optimized dispatch.

If qualification fails, benchmark admission is empty and the process registry is reset to reference.
If a primitive fails the speed threshold, only that primitive remains on reference.

## Next

Phase606 begins AMI tensor-window execution for the first decoder block. It consumes the
`AmneProcessKernelRuntime` registry so real AMI execution automatically uses only device-admitted
native primitives and falls back per primitive when appropriate.
