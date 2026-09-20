# Phase599 — GGUF to AMI SOURCE_EXACT Compiler

Phase599 turns AMI from a semantic contract into a concrete binary artifact.

## Compiler path

```
user GGUF
   -> existing sovereign GgufInspector admission
   -> AMI compiler scan
   -> AMI manifest
   -> preserved GGUF metadata/tokenizer snapshot
   -> AMI graph identity
   -> AMI-native tensor index
   -> exact tensor-data copy
   -> section SHA-256 validation
   -> model.ami
```

The compiler intentionally reads the source more than once. The first complete pass is the existing
GGUF admission/digest boundary. Later passes create deterministic AMI sections from the already
admitted source. Content-provider sources are reopenable by contract.

## SOURCE_EXACT foundation

Phase599 does **not** dequantize or requantize model tensors.

The complete GGUF tensor-data region is copied byte-for-byte into the AMI
`FOUNDATION_WEIGHTS` section. Tensor offsets in the generated AMI tensor index remain relative to
the beginning of this exact copied region.

Therefore converting a Q4 GGUF does not secretly turn it into another Q4 scheme, and converting
FP16 does not silently lower it to INT4.

Hardware-specific prepacking will be added later as optional `EXECUTION_PROFILE` sections. Those
profiles never become the sole copy of the intelligence.

## Concrete binary layout

AMI v1 reserves the first 8192 bytes for a bounded fixed header and section descriptor table.
Each descriptor is fixed-width and records:

- section type;
- profile id;
- file offset;
- byte length;
- required alignment;
- SHA-256 digest.

Tensor-bearing sections are 4096-byte aligned. Other compiler metadata sections are at least
64-byte aligned.

The compiler writes through `.partial` staging and publishes the final `.ami` only after every
section digest is recomputed successfully.

## Tokenizer preservation

In AMI v1 Phase599, the TOKENIZER section stores the original GGUF metadata table bytes. This
preserves the GGUF tokenizer vocabulary, tokenizer settings, chat template and related metadata
without forcing a lossy intermediate representation.

A later AMI-native tokenizer compiler can normalize this data while maintaining the same lineage.

## Current admission

The first compiler requires:

- supported GGUF v2/v3;
- `general.architecture`;
- `tokenizer.ggml.tokens`;
- at least one tensor-data byte;
- an artifact that already passes the canonical GgufInspector.

Unsupported or incomplete input fails closed and the final AMI file is not published.

## Next

Phase600 adds a bounded AMI binary reader, file identity verification, app-private AMI artifact
storage and mmap-ready section views. That establishes the load boundary needed before ARM64 tensor
kernels can execute AMI directly.
