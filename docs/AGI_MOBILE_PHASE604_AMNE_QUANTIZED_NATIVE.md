# Phase604 — Native Q4_0/Q8_0 AMNE Kernels

Phase604 extends the AMPER Mobile Neural Engine native runtime from F32 primitives to the first
quantized matrix paths used by imported GGUF/AMI weights.

## Native ABI v2

AMNE native ABI advances from v1 to v2.

The Java/Kotlin bridge requires ABI v2 before any Phase604 kernel can be used. An older
`libamper_amne.so` therefore fails closed instead of being paired with a newer bridge.

## Q4_0

The native runtime implements the canonical ggml Q4_0 block used by AMI source encoding type 2:

- block size: 32 logical weights;
- block bytes: 18;
- first two bytes: little-endian FP16 scale;
- next 16 bytes: packed 4-bit values;
- low nibble maps to elements 0..15;
- high nibble maps to elements 16..31;
- decoded quantized value is nibble minus 8.

Phase604 preserves the exact Phase602 reference semantics and does not requantize weights.

## Q8_0

The native runtime implements AMI source encoding type 8:

- block size: 32 logical weights;
- block bytes: 34;
- first two bytes: little-endian FP16 scale;
- next 32 bytes: signed int8 weights.

## Qualification

The single canonical `AmneNativeRuntimeProbe` now checks nine primitives:

- DOT_F32
- MATVEC_F32
- MATVEC_Q4_0
- MATVEC_Q8_0
- RMS_NORM_F32
- SILU_F32
- SWIGLU_F32
- SOFTMAX_F32
- ROPE_F32

Q4_0 and Q8_0 use deterministic encoded blocks and are compared directly against
`AmneReferenceCpuKernels`.

Native routing still remains disabled unless every declared primitive passes the numerical tolerance.

## CI

The isolated AMNE ARM64 job remains the only native job required for this phase. It builds the AMNE
library together with the existing llama AAR fallback and verifies both are packaged.

## Next

Phase605 adds device microbenchmarks and qualified dispatch admission. Performance is measured only
after numerical qualification; a native kernel that is slower than the reference path for a workload
is not selected merely because it is native.

Phase606 begins AMI tensor-window execution for the first decoder block.
