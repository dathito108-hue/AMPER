# Phase616 — Native K-Quant AMPER Core

Phase616 completes the matrix-format coverage needed for common mobile GGUF-derived AMI foundations
inside the single AMPER inference core.

## One core, more native tensor encodings

The production architecture remains:

```
imported GGUF bytes
  -> SOURCE_EXACT AMI
  -> one active AMPER foundation
  -> one AMI decoder
  -> one AMNE runtime
  -> AMPER output
```

No second model runtime or fallback model engine is introduced.

## Native matrix primitives

`libamper_amne.so` ABI v3 now declares and implements:

- MATVEC_Q4_K
- MATVEC_Q5_K
- MATVEC_Q6_K

alongside the existing F32, Q4_0 and Q8_0 matrix paths.

The native K-quant implementations consume the preserved SOURCE_EXACT AMI bytes directly. They do
not rewrite, dequantize-to-disk, or requantize the foundation weights.

## Correctness qualification

Every new native K-quant primitive is compared on-device against the exact Phase609 reference codec
before admission.

Qualification covers all AMNE primitives declared by the native backend. Any numerical mismatch
keeps the whole native backend out of the process-local admission registry.

## Device performance admission

The microbenchmark now includes Q4_K, Q5_K and Q6_K matrix workloads.

Direct AMI interactive execution remains fail-closed: if a matrix encoding required by the active
foundation is not both numerically qualified and measurably faster than the reference path, the
foundation is not considered interactive-ready.

Because Phase615 removed alternative production model engines, such a foundation reports its AMPER
Core admission failure rather than silently invoking another AI runtime.

## UI

AMPER exposes one qualification control:

`Qualify + benchmark AMPER Core`

The result summarizes native matrix admission for F32/Q4_0/Q8_0/Q4_K/Q5_K/Q6_K.

## Next

Phase617 removes obsolete legacy llama/MTMD build toggles and CI jobs from the canonical project so
the repository itself mirrors the single-core runtime invariant, not only the production app path.
