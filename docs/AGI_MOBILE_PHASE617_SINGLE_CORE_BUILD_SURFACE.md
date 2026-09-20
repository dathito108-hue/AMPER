# Phase617 — Canonical Single-Core Build Surface

Phase617 removes foreign model runtimes from the canonical AMPER project build surface.

## Removed build modes

The Android app no longer exposes:

- `withLlamaAar`;
- `withMtmdNative`;
- `withMtmdVulkan`;
- llama Android dependency/version BuildConfig metadata;
- llama/MTMD CMake or source-set wiring.

The only native inference build switch is:

`-PwithAmneNative=true`

## Removed runtime sources

The legacy llama AAR backend pack and MTMD native runtime implementation are removed from the
canonical repository source tree.

GGUF remains supported only as imported weight source data. Its tensors/tokenizer/config are compiled
into AMI before entering the AMPER inference core.

## CI invariant

Android CI now has only two jobs:

1. canonical JVM/Android verification;
2. AMPER Core ARM64 build when AMNE native code changes or a physical APK is requested.

The AMPER Core packaging check fails when any of these foreign runtime libraries appear:

- `libllama*`;
- `libmtmd*`;
- `libggml*`.

This makes the repository-level invariant match the production runtime invariant.

## Canonical inference architecture

```
weight source import
       |
       v
      AMI
       |
       v
AMPER Core
 tokenizer
 decoder
 KV state
 sampler
       |
       v
     AMNE
 ARM64 / quantized kernels
```

There is no production backend competition between external model engines.

## Next

Phase618 collapses Titan's generic multi-backend registry at the production boundary into a
single-core inference port. Generic registry abstractions may remain for tests/internal modules, but
the Android canonical runtime will own exactly one AMPER Core inference endpoint.
