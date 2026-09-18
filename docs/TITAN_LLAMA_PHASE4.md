# Titan Cortex Phase 4 — llama.cpp adapter + streaming contract

This phase introduces the concrete canonical adapter surface intended for the official llama.cpp Android/JNI engine while preserving backend replaceability.

`LlamaCppEngine` owns engine-specific model/session/token mechanics. `LlamaCppAdapter` translates it into AMPER's `StreamingNativeInferenceAdapter`. Token streaming is represented by deterministic `InferenceChunk` events and non-streaming callers can aggregate the same stream without a second inference path.

Native loading is fail-closed through `OptionalNativeLibraryProbe`: absence of a bundled JNI library marks the backend UNAVAILABLE and never fabricates output or destabilizes the sovereign runtime.

The actual llama.cpp native library remains a separately integrated engine artifact. Model weights remain user-selected GGUF artifacts and are not sovereign identity.
