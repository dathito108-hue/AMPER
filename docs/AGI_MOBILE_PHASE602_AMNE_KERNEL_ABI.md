# Phase602 — AMNE Kernel ABI + Reference CPU Primitives

Phase602 defines the stable execution primitive contract for the AMPER Mobile Neural Engine (AMNE).

## Why a reference layer first

Optimized mobile kernels are difficult to debug if graph semantics, tensor layouts and arithmetic
contracts change at the same time. Phase602 therefore freezes a simple correctness oracle before
introducing NEON/DOTPROD/I8MM or Vulkan acceleration.

This phase intentionally does **not** replace the active llama backend and does not trigger an
expensive NDK build.

## Stable AMNE primitive ABI

The first ABI exposes:

- F32 dot product;
- F32 dense row-major matrix-vector multiplication;
- F32 RMSNorm;
- F32 SiLU;
- F32 RoPE.

The interface is model-agnostic. A later Llama/Qwen/Mistral graph executor composes these primitives
rather than embedding model-specific behavior into the kernels.

## Reference implementation

`AmneReferenceKernels` is an auditable portable implementation used as the mathematical oracle.

It favors deterministic, high-precision accumulation in JVM doubles where practical and converts the
final result back to F32. It is not intended as the final performance path.

## Qualification gate

`AmneKernelQualifier` runs deterministic vectors through a candidate backend and compares the
results against the reference implementation.

A candidate accelerated backend is eligible only when:

- all required primitive IDs are declared;
- F32 is declared supported;
- all outputs are finite;
- maximum absolute error is within the configured tolerance.

This gives future native kernels a fail-closed correctness gate independent of benchmark speed.

## Tensor ABI

`AmneTensorView` defines the minimum tensor view needed by execution backends:

- stable name;
- scalar encoding;
- shape;
- byte offset;
- byte length;
- overflow-checked element count.

The initial scalar ABI reserves common mobile/model encodings including F32/F16/BF16/Q4/Q5/Q6/Q8.

## Next

Phase603 will add the first native ARM64 AMNE library with NEON F32 primitives and JNI qualification.
The native implementation will remain optional and will only become routable after it passes the
Phase602 qualifier.

Later phases add quantized Q4/Q5/Q6/Q8 kernels, DOTPROD/I8MM, FP16, Vulkan, KV-cache primitives and
the first AMNE decoder graph.
