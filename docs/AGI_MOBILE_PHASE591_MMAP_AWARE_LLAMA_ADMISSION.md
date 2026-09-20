# Phase591 — mmap-aware llama.cpp admission

## Physical finding

The Phase590 operational APK successfully packaged and attached `llama.cpp-aar`, and the
user-imported GGUF was accepted into the model catalog. Titan still rejected the route with:

```
llama.cpp-aar:memory-budget
```

This proves backend discovery and model installation are working. The remaining blocker was the
memory estimate used by governed route admission.

## Root cause

The llama AAR backend previously estimated:

```
full GGUF file size + max(384 MiB, 20% of file size)
```

and compared that value with AndroidResourceGovernor's already-conservative live memory budget.

llama.cpp opens GGUF weights through a native path and uses mmap. Charging the complete file as
non-reclaimable process memory is therefore too conservative on mobile and can reject feasible local
inference before model loading even starts.

## Phase591

- introduce `MmapGgufMemoryEstimator`;
- budget a conservative hot mapped weight window (45% of GGUF bytes);
- add explicit context/KV and native/runtime overhead;
- keep unknown-size models as unknown rather than inventing headroom;
- keep Android thermal/low-memory governance and Titan execution admission unchanged;
- use the same estimate for resident-session resource reconciliation;
- add deterministic regression tests.

This does not guarantee that every GGUF fits. Larger models can still fail the governed memory gate,
and Android low-memory/thermal conditions remain authoritative.
