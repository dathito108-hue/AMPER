# Phase590 — Operational Text Runtime APK

## Why

Physical Hotfix589 proved the Android app can reach `Kernel: READY`, but the FAST qualification
APK intentionally omits optional inference engines. The UI therefore reports that the text backend
pack and native MTMD engine are not packaged.

That APK is useful for startup/resource qualification, but it cannot prove real GGUF inference.

## Operational artifact

Phase590 adds a tightly scoped main-branch trigger that builds an additional APK with:

```
-PwithLlamaAar=true
```

The workflow verifies the arm64 package contains the llama JNI/runtime and GGML libraries before
uploading the APK.

This is deliberately text-only. It does not enable MTMD or Vulkan, so it avoids the expensive
native toolchain while providing a real physical-device path for:

- GGUF import;
- descriptor-bound model verification;
- local llama.cpp text inference;
- Native System-2 / planning qualification with a user-selected model;
- offline sovereignty experiments before the final multimodal/full release gate.

The existing canonical FAST APK and native/full CI behavior are unchanged.
