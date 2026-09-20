# Phase612 — AMI Tokenizer Encoders

Phase612 completes the first UTF-8 input -> token-id boundary for direct AMI execution.

## Family-specific encoders

AMPER does not use one heuristic tokenizer for every GGUF model.

### GPT-2 byte-level BPE

The GPT-2 path uses:

- the preserved `tokenizer.ggml.merges` rank order;
- the canonical GPT-2 byte-to-unicode mapping;
- Unicode-aware GPT-2 pre-token segmentation;
- deterministic iterative BPE merges;
- exact vocabulary lookup after every merged symbol.

A merge result not present in the vocabulary fails closed.

### SentencePiece/Llama unigram

The SentencePiece path uses:

- preserved vocabulary pieces;
- preserved per-token scores;
- score-maximizing dynamic programming;
- SentencePiece `▁` word-boundary normalization;
- explicit `<0xXX>` byte fallback;
- unknown-token fallback only when the lexicon declares one.

CONTROL/UNUSED/BYTE entries are excluded from normal unigram candidates and byte tokens are used only
as fallback edges.

## BOS/EOS policy

The encoder uses the GGUF-preserved add-BOS/add-EOS flags by default.

A caller may override those flags explicitly, but insertion still fails if the matching special token
ID is absent.

## Round-trip qualification

Unit fixtures now assert:

- GPT-2 merge-rank encoding -> detokenization;
- SentencePiece score-based segmentation + byte fallback -> detokenization;
- BOS/EOS override semantics;
- fail-closed GPT-2 behavior when merge data is missing.

## Direct AMI path after Phase612

```
UTF-8 prompt
  -> AMI tokenizer
  -> token ids
  -> AMI embedding
  -> decoder stack + KV
  -> output head
  -> logits + sampler
  -> generated token ids
  -> AMI detokenizer
  -> UTF-8 response
```

The individual pieces now exist. The next phase wires them into one streaming inference backend,
adds cancellation and resource/session boundaries, and keeps the current llama backend as fallback
until physical-device AMNE qualification passes.
