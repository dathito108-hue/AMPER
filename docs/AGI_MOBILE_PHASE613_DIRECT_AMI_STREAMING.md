# Phase613 — Direct AMI Streaming Inference Backend

Phase613 connects the AMI execution stack to Titan as a real cancellable streaming text backend.

## End-to-end direct AMI path

```
UTF-8 prompt
  -> AMI tokenizer encoder
  -> verified AMI tensor graph
  -> direct AMI decoder stack
  -> AMNE kernel dispatch
  -> output head + sampler
  -> AMI detokenizer
  -> stable incremental UTF-8 chunks
  -> Titan streaming contract
```

This path does not call llama.cpp for model execution.

The installed GGUF remains the user's source/model identity and capability declaration. Direct AMI
execution is eligible only when a verified app-private AMI artifact with matching source SHA-256
already exists.

## Fallback remains intact

The llama.cpp backend is not removed. If no verified AMI artifact exists, if the AMI graph/tokenizer
is unsupported, if the request contains unsupported attachments, or if Titan resource/capability
policy rejects the AMI route, normal backend routing can fall through to the existing runtime.

## True incremental streaming

The autoregressive generator now accepts:

- cooperative cancellation;
- per-token callbacks.

The direct backend detokenizes cumulative generated token ids and uses a one-token stable-prefix
emitter. This prevents a temporary UTF-8 replacement character from being emitted when a byte
fallback character spans multiple tokenizer tokens.

The terminal chunk is emitted only after the authoritative final detokenization succeeds.

## Context and resource admission

The backend exposes exact prompt-token counting through the AMI tokenizer.

Titan therefore evaluates:

`exact prompt tokens + requested output tokens <= AMI decoder context`

before execution.

Memory admission includes a bounded working-memory reserve plus the calculated per-layer KV-cache
capacity. Model weights remain mmap-backed and are not counted as one eagerly resident heap copy.

## Session semantics

Phase613 starts each inference with a fresh AMI KV state, so `sessionReused=false` is reported.
Cross-turn prefix/KV reuse will be introduced only after prompt-prefix identity can be proven.

## Next

Phase614 will add a persistent AMI session cache keyed by model identity + prompt-prefix identity and
will expose direct-AMI physical latency telemetry. Phase615 can then make AMI the preferred text
route only after physical qualification demonstrates correctness and useful latency on-device.
