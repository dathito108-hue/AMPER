# Titan Cortex Phase 3 — backend adapter and runtime policy

Titan remains a sovereign routing layer, never the model itself.

Phase 3 adds a stable production seam between AMPER and native/mobile inference engines. `NativeInferenceAdapter` can be implemented by llama.cpp JNI, an NPU runtime, a Vulkan backend, or another future engine without changing `SovereignKernel`, memory, goals, tools, or identity.

Backend routing is health- and resource-aware. Unavailable engines are fail-closed. Managed backends may declare estimated memory/thread/context cost and are rejected when they exceed the current `ResourceBudget`. Among eligible managed backends, READY and hardware-accelerated engines are preferred.

No engine library or model weight is bundled by this phase. A concrete llama.cpp Android adapter is the next integration boundary; it must implement this contract and remain replaceable.
