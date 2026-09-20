# Phase602 — AMNE Kernel ABI and Correctness Reference

Phase602 freezes the first executable math contract for the AMPER Mobile Neural Engine (AMNE).

## Why a correctness reference first

Optimized mobile kernels are difficult to validate by visual output alone. A single wrong nibble
order, FP16 conversion, normalization factor or RoPE rotation can produce fluent but incorrect model
behavior.

AMPER therefore establishes a deterministic reference backend before enabling ARM64 intrinsics.

Every future NEON, DOTPROD, I8MM, FP16 or Vulkan kernel must qualify numerically against this
reference implementation before it can be selected by AMNE.

## Stable kernel ABI

The initial AMNE primitive set is:

- DOT F32
- MATVEC F32
- MATVEC Q4_0
- MATVEC Q8_0
- RMSNorm F32
- SiLU F32
- SwiGLU F32
- stable Softmax F32
- RoPE F32

This set covers the essential arithmetic shape needed by Llama-like decoder blocks while keeping
the optimized implementation boundary small.

## Quantized source preservation

Q4_0 and Q8_0 kernels consume the source GGML block representation directly from the AMI
FOUNDATION_WEIGHTS section.

Phase602 does not rewrite or requantize those blocks.

Q4_0 uses the canonical 32-element / 18-byte layout:

- FP16 scale;
- 16 packed bytes;
- low nibble maps the first 16 elements;
- high nibble maps the second 16 elements;
- integer value is nibble minus 8.

Q8_0 uses the canonical 32-element / 34-byte layout:

- FP16 scale;
- 32 signed int8 values.

## Dispatch

`AmneKernelRegistry` admits a backend only when all of its declared hardware requirements are
present. Among compatible implementations, more hardware-specific backends are preferred.

The deterministic reference backend is always retained as the final correctness fallback.

Unknown tensor encodings are reported as unsupported. AMNE never guesses a quantized byte layout.

## Numerical qualification

`AmneKernelNumerics` provides a common maximum-absolute-error comparator. Optimized kernels will
publish an explicit tolerance per primitive rather than silently changing numerical behavior.

## Next

Phase603 introduces an optional native `libamne` backend for Android ARM64 with a minimal C ABI and
JNI bridge. The first native implementation will cover F32/RMSNorm/SiLU/Softmax/RoPE and Q4_0/Q8_0
matvec, then qualify each result against this Phase602 reference before AMNE enables the optimized
route.
