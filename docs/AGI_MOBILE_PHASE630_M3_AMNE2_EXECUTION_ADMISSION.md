# Phase630 — M3 AMNE2 Execution Admission Foundation

Phase630 begins Milestone 3 without creating a second decoder or kernel stack.

## Reuse boundary

AMPER already has:

- one AMNE math ABI;
- deterministic reference kernels;
- ARM64/native kernel descriptors;
- numerical qualification;
- benchmark-based primitive admission;
- one live process kernel registry.

AMNE2 reuses those mechanisms. Phase630 adds only the verified AMI2 execution-admission boundary
that was missing between the M2 canonical format and the existing kernel system.

## Admission flow

`Amne2ExecutionAdmission`:

1. reads the candidate AMI2 with full section-digest verification;
2. requires exactly the canonical AMI2 foundation artifact set;
3. rechecks canonical foundation id <-> semantic SHA-256 binding;
4. requires ARM64 + NEON for the mobile execution profile;
5. maps the existing Android hardware snapshot into AMI2 hardware features;
6. requires the deterministic AMNE reference backend in the live registry;
7. derives accelerated primitive claims only from admissions embedded in that same registry;
8. returns one `Amne2AdmittedFoundation` bound to foundation semantic identity and AMI2 file hash.

A tampered AMI2 never reaches kernel admission.

## Execution tiers

Phase630 intentionally distinguishes:

- `REFERENCE_VALIDATION`: correctness bring-up only; valid AMI2 and deterministic kernels exist;
- `QUALIFIED_ACCELERATED`: at least one optimized primitive has passed numerical qualification and
  benchmark admission in the live registry.

REFERENCE_VALIDATION is not claimed as mobile performance readiness.

## Architecture invariants preserved

- exactly one AMPER foundation;
- AMI2 remains the canonical model format;
- AMNE2 remains the sole target execution engine;
- no GGUF runtime path is introduced;
- no AMI1 decoder/kernel fork is created;
- device packs cannot create another foundation identity.

## M3 status after Phase630

Established:

- verified AMI2 -> AMNE2 admission boundary;
- ARM64/NEON hardware floor;
- reuse of the existing qualified AMNE dispatch table.

Still required before M3 completion:

- AMI2 section/mmap execution access;
- decoder binding to AMI2 canonical sections;
- KV memory/paging integration;
- DOTPROD/I8MM/FP16 device qualification;
- device autotuner;
- Vulkan admission only when physical benchmark wins.

## Next slice

Phase631 should add AMI2 bounded mmap section access and an AMNE2 execution view so the existing
decoder/tensor readers can consume canonical AMI2 sections without copying multi-gigabyte weights
and without reintroducing AMI1 as the semantic runtime format.
