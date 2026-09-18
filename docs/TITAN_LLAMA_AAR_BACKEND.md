# Optional prebuilt llama.cpp AAR backend

Phase 6 replaces the failed direct-source native integration with an optional prebuilt backend pack.

## Canonical build

`gradle :app:assembleDebug`

The canonical APK contains no llama.cpp AAR and requires no NDK or CMake. Titan starts normally; inference fails closed until a compatible backend pack is present.

## Local GGUF build

`gradle :app:assembleDebug -PwithLlamaAar=true`

This opt-in build adds `dev.ffmpegkit-maintained:llama-android:0.1.1` from Maven Central and compiles only `app/src/llamaAar/java`. `OptionalBackendPackLoader` discovers the pack by class name, so canonical source code does not import vendor classes.

The adapter probes the packaged native runtime, registers an `InferenceBackend`, and executes user-selected GGUF through the existing `NativeModelPathSource` boundary. No model weights are bundled and SAF-selected model bytes are not copied into the sovereign store.

The first implementation deliberately loads/releases the model for each request. This is slower but bounds native lifetime and keeps failure recovery simple; a resource-governed model cache can be added after device validation.

## CI isolation

Canonical tests and APK build are mandatory. The optional AAR APK is a separate `continue-on-error` job, so a third-party package outage or ABI regression cannot block AMPER core releases.
