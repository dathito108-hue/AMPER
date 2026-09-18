# Titan Cortex — Android Model Import

Phase 2 connects the Phase 1 Titan Cortex contracts to Android's Storage Access Framework (SAF) without making AMPER own or bundle user model weights.

## Storage invariant

- The user selects a GGUF with `ACTION_OPEN_DOCUMENT`.
- AMPER persists read permission to that document URI.
- GGUF bytes remain at the user-selected storage provider.
- The sovereign private store persists only model metadata, capabilities, URI locator, GGUF header information and SHA-256.
- Import inspection/hashing runs away from the UI thread.

## Restart behavior

`FileInstalledModelCatalog` reloads installed descriptors from `filesDir/amper-sovereign/models.catalog`. `InstalledModelRegistryBootstrap` republishes those descriptors into the model registry at startup. Real GGUF models are routed ahead of the `contract-only` placeholder.

## Backend boundary

SAF solves artifact access only. Inference still requires a separately registered `InferenceBackend` (for example a future llama.cpp/AriLLM/native accelerator adapter). No fake production inference is introduced by this phase.
