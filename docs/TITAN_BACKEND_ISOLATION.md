# Titan backend isolation

AMPER no longer makes a vendor/native inference runtime a build-time dependency of the sovereign application.

## Rule

The Android app, Sovereign Kernel, model catalog, SAF GGUF import, and Titan Cortex must build and start with **zero native inference backends installed**. Inference backends are replaceable capability packs attached through `TitanBackendPack` and `InferenceBackendRegistry`.

A backend pack owns its JNI/NDK/vendor dependencies. Failure to compile, load, initialize, or execute one backend must not corrupt or prevent startup of the sovereign runtime.

## Why the direct llama.cpp build was removed

The previous Phase 5 attempt compiled the pinned upstream llama.cpp Android example directly inside the app build. That coupled the APK to upstream CMake targets, NDK details, Android example sources, and submodule layout. A native build regression therefore blocked all AMPER CI even though the canonical Kotlin runtime was healthy.

That dependency direction is now forbidden.

## Replacement path

1. AMPER core defines only stable inference contracts.
2. User-owned GGUF artifacts remain outside the sovereign identity and memory stores.
3. A backend pack adapts a concrete engine (llama.cpp, AriLLM, vendor NPU/GPU runtime, or another implementation) to `InferenceBackend`.
4. Titan attaches the pack and routes compatible models to it.
5. If the pack is absent or unhealthy, Titan fails inference closed with a backend-unavailable result while the rest of AMPER remains operational.
6. Native backend CI is separate from the canonical Android CI and cannot gate the base APK.

## Native llama.cpp follow-up

A llama.cpp backend can still be used, but it must live behind the backend-pack boundary (preferably as its own Android library/module or prebuilt AAR/JNI package). It must not add `externalNativeBuild`, upstream source sets, or recursive submodule checkout to the base `:app` module.
