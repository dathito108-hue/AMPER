# Titan Cortex Runtime — Phase 1

Titan Cortex is an orchestration boundary, **not** AMPER's identity and not a bundled model.

## User-owned GGUF flow

1. User selects a model artifact.
2. A storage adapter exposes it through `ModelArtifactSource`.
3. `GgufInspector` validates magic/header/version and streams SHA-256 without loading the whole model into RAM.
4. `LocalModelInstallService` records immutable inspection metadata and capabilities in `InstalledModelCatalog`, then registers the model in `ModelRegistry`.
5. `TitanCortexRuntime` routes a request by required capabilities.
6. A `ModelArtifactResolver` re-opens the user-owned bytes.
7. A compatible `InferenceBackend` performs inference.

No model weights are copied into the sovereign kernel and no GGUF becomes the sovereign identity.

## Backend boundary

`InferenceBackend` is deliberately generic. A llama.cpp JNI backend, AriLLM backend, vendor NPU backend, GPU backend, or remote provider can implement this contract. The current phase does not fake inference: if no real backend is registered, Titan Cortex fails closed with a descriptive error.

## GGUF compatibility

The foundation recognizes GGUF header structure and currently marks GGUF v2/v3 as supported contracts. Unknown versions can be probed but cannot be installed until a compatible runtime backend explicitly supports them.
