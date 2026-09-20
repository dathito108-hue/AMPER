# Phase600 — Verified AMI Reader, App-Private Storage and On-Device Compilation

Phase600 establishes the first Android load boundary for AMPER Mobile Intelligence.

## Verified reader

`AmiBinaryReader` now:

- validates AMI magic/version/header size;
- validates fixed-width section descriptors;
- validates file length and section boundaries;
- reconstructs the AMI manifest;
- reuses the Phase598 container invariants;
- verifies SHA-256 for every section before runtime admission;
- computes the complete AMI artifact SHA-256.

A modified FOUNDATION_WEIGHTS byte therefore fails before any future native kernel sees it.

## Windowed mmap

`AmiMappedSectionAccess` maps bounded read-only windows rather than requiring one Java mapping for
the entire model.

This matters on Android because an AMI can be larger than a single practical Java ByteBuffer and
because mobile virtual-memory/RSS pressure should be controlled at the tensor/window level.

Default maximum mapping window: 256 MiB.

## App-private AMI ownership

`AndroidAmiCompilationService` stores generated AMI files under AMPER's sovereign app-private
storage.

The output name is content-addressed from the complete installed GGUF SHA-256:

`source-sha256-<64 hex>.ami`

The original GGUF is never deleted or rewritten.

If the same AMI already exists, AMPER re-verifies every section and reuses it only when the embedded
source lineage matches the installed GGUF identity.

## Physical Android UI

For an installed GGUF, the model profile now exposes:

`Compile selected GGUF -> AMI`

The work runs off the UI thread. Status reports:

- PASS/failure;
- AMI architecture;
- SOURCE_EXACT mode;
- output size;
- foundation SHA-256 prefix;
- full section verification;
- total conversion wall time.

Phase600 still does not route inference through AMI. The active llama backend remains untouched while
AMI execution kernels are developed.

## Next

Phase601: decode the AMI tensor index into a native execution graph and add hardware profiling for
ARM64/NEON/DOTPROD/I8MM/FP16/Vulkan.

Phase602+: baseline ARM64 kernels and the AMNE Titan backend.
