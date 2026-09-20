# Phase595 — Blocking Backend Interactive Latency Budget

## Physical finding

On-device local GGUF inference is proven working through the operational llama AAR backend:

- Titan route selected the imported GGUF;
- `llama.cpp-aar` backend;
- local smoke test PASS;
- about 4.7 tok/s generation.

Ask AMPER could still appear unresponsive.

## Root cause

`llama.cpp-aar` is a blocking `InferenceBackend`, not a `StreamingInferenceBackend`.

Titan's governed streaming path correctly refuses to fake realtime streaming for blocking engines:
it waits for `backend.infer(...)` to finish and then emits one completed text chunk.

The assistant's default output allowance is 384 tokens. At roughly 4.7 tok/s, a blocking backend can
therefore spend more than 80 seconds generating before the UI receives the first text chunk.

## Phase595

When a caller requests Titan streaming:

- true streaming backends keep the full requested output budget;
- blocking fallback backends receive a maximum output budget of 64 tokens;
- direct non-streaming `infer(...)` semantics are unchanged;
- route/capability/resource/authority admission is unchanged;
- Titan still emits only completed output for a blocking backend and does not present synthetic
  token streaming.

At the observed physical speed, 64 tokens bounds the worst-case blocking generation interval to
roughly the low-teens of seconds plus prompt evaluation/model-load overhead instead of allowing a
multi-minute silent response.

Regression tests prove both that true streaming budgets are preserved and that blocking fallback
requests are capped before reaching the backend.
