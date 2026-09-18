# Titan warm-session runtime

Phase 8 keeps at most one native GGUF model warm inside an optional inference backend.

## Invariants

- Sovereign identity, memory, goals and workspace do not live inside the model session.
- Canonical AMPER still compiles and runs with zero native backend present.
- Only one llama.cpp AAR model is retained at a time to bound mobile native memory.
- The backend serializes inference because the AAR's `LlamaModel` is not thread-safe.
- A changed model hash, larger context requirement, CPU-thread configuration or temperature causes the old model to be released before the replacement is loaded.
- Native inference failure releases the active session rather than retaining potentially corrupted state.
- Titan exposes explicit `unload(modelId)` and `unloadAll()` lifecycle operations.
- Activity disposal queues a best-effort unload before shutting down the inference executor.

## Context semantics

The selected llama AAR clears its native KV cache at the beginning of each completion. Warm model reuse therefore avoids model reload cost without implicitly carrying the previous prompt into the next prompt. Persistent conversational continuity belongs to AMPER's Memory OS / Global Cognitive Workspace, not to backend-local hidden state.

## Telemetry

`InferenceResponse` can now report whether the native model session was reused plus tokens/second and prompt/generation timing when the backend exposes them.
