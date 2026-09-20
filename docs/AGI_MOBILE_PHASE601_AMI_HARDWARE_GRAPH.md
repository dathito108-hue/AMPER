# Phase601 — AMI Tensor Graph + Android Hardware Profile

Phase601 moves AMI from verified storage toward executable mobile inference.

## AMI tensor graph

The AMI-native TENSOR_INDEX is now decoded into typed tensor descriptors containing:

- tensor name;
- dimensions;
- source encoding type;
- byte offset inside FOUNDATION_WEIGHTS;
- known encoded storage footprint.

The graph reader verifies:

- tensor count matches the AMI manifest;
- names are canonical UTF-8 and unique;
- dimensions are bounded and positive;
- tensor offsets remain inside FOUNDATION_WEIGHTS;
- known footprints do not extend beyond the foundation;
- known footprints do not overlap.

Unknown source encodings are retained as unknown rather than being assigned guessed byte geometry.

## Android hardware profile

AMPER now has a conservative hardware profiler for AMI/AMNE execution.

The profile can expose:

- ARM64;
- NEON/Advanced SIMD;
- DOTPROD when CPU feature text reports it;
- I8MM when CPU feature text reports it;
- FP16 when CPU feature text reports half-precision support;
- Vulkan when Android publishes a Vulkan hardware feature.

ARM optional instructions are never inferred merely from device marketing names.

The snapshot also records logical processor count, Android memory class and low-RAM status.

## Execution profile selection

The initial selector:

1. removes profiles requiring unsupported hardware;
2. removes profiles whose context tier cannot satisfy the request;
3. chooses the smallest adequate context tier;
4. within that tier prefers the more hardware-specific compatible profile.

This is execution policy only. It cannot change foundation weights, declared model capabilities or
AMPER authority.

## Next

Phase602 will introduce the AMNE portable ARM64 kernel ABI and a reference CPU implementation for
the first tensor primitives. Optimized NEON/DOTPROD/I8MM implementations can then replace individual
kernels without changing Titan or AMI semantics.
