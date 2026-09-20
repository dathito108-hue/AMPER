# Phase591 — Physical Local Inference Smoke Test

## Physical evidence entering this phase

Phase590 operational APK was confirmed on-device with:

- Kernel READY
- installed GGUF visible in the model catalog
- text backend pack attached
- `Titan backend ready: llama.cpp-aar`

The remaining gap was that no inference route had yet been executed in the current process.

## Phase591

Adds a direct, bounded local inference smoke test in the app UI.

The test:

- uses the existing `PreferredModelInferencePort`;
- routes through canonical Titan selection and resource admission;
- uses a fixed short prompt and 32-token output cap;
- does not invoke tools;
- does not mutate conversation history;
- reports selected model id and backend id;
- reports output-token count, token/s, generation time, and wall time when available;
- refreshes Titan Route Observatory after execution;
- surfaces the exact exception if model loading, routing, resource admission, or inference fails.

A PASS is physical evidence that the installed GGUF was actually selected and executed by a real packaged backend. It is not by itself a claim that the model is AMPER-owned or that all AGI-mobile qualification domains are complete.
