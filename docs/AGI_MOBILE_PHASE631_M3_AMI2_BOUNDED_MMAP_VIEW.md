# Phase631 — M3 AMI2 Bounded mmap Execution View

Phase631 gives AMNE2 a verified, bounded-memory view over canonical AMI2 sections without copying or
mapping the entire model.

## Single verification path

`Amne2ExecutionViewFactory`:

1. reads and verifies the AMI2 artifact once;
2. passes that verified object into the Phase630 AMNE2 admission contract;
3. binds the admitted foundation id, semantic SHA-256, and physical AMI2 file SHA-256;
4. returns one immutable execution view.

Phase631 refactors Phase630 admission with an internal `admitVerified` path so opening an execution
view does not hash a multi-gigabyte AMI2 file twice.

## Bounded mmap

`Amne2ExecutionView.mapWindow`:

- accepts Long relative offsets;
- maps only an Int-sized bounded read-only window;
- defaults to at most 8 MiB per window;
- verifies every requested window remains inside the already-verified section descriptor;
- supports model sections whose total length is greater than 2 GiB.

The view never requests a whole-model mmap.

## Bounded control reads

Small graph/index/control slices may use `readBytes`, which applies the same configured window bound
and verified section range checks.

Large FOUNDATION_WEIGHTS access is intended to use mmap windows.

## Security and identity

An execution view cannot be opened when:

- AMI2 section digests fail;
- semantic identity fails;
- AMNE2 hardware admission fails;
- the requested mmap/read window escapes its verified section;
- the requested window exceeds the configured memory bound.

The execution identity always includes:

- foundation id;
- foundation semantic SHA-256;
- complete AMI2 artifact SHA-256.

## M3 status

Established after Phase631:

- verified AMI2 -> AMNE2 admission;
- bounded canonical AMI2 section access;
- ARM64/NEON mobile hardware floor;
- reuse of existing AMNE kernel registry.

Still required:

- decoder/tensor semantic binding directly onto the AMI2 view;
- request KV paging/management on AMNE2;
- DOTPROD/I8MM/FP16 qualification coverage;
- device autotuning;
- Vulkan physical-benchmark gate.

## Next slice

Phase632 should bind the existing AMI tokenizer/graph/tensor decoder semantics to the AMI2 execution
view through adapters over canonical TOKENIZER, LOGICAL_GRAPH, TENSOR_INDEX and
FOUNDATION_WEIGHTS sections. It must reuse the current decoder implementation rather than fork it.
