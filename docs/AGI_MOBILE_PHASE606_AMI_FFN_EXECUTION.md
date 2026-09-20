# Phase606 — First AMI Decoder FFN Execution

Phase606 is the first time AMPER executes neural-network math directly from `.ami`
FOUNDATION_WEIGHTS.

## Execution path

```
verified .ami
  -> AMI tensor graph
  -> preserved GGUF scalar model metadata
  -> decoder FFN tensor binding
  -> bounded read-only mmap windows
  -> Phase605 qualified AMNE dispatch
  -> RMSNorm
  -> Gate / Up matrix-vector
  -> SwiGLU
  -> Down matrix-vector
  -> residual add
```

The active llama backend remains available for normal assistant inference. Phase606 is an independent
AMI execution probe and does not replace production routing yet.

## Preserved model metadata

The SOURCE_EXACT compiler already stores the original GGUF metadata table in the AMI TOKENIZER
section. Phase606 adds a bounded scalar reader for that preserved table.

Large tokenizer arrays are skipped without being materialized. The exact metadata count comes from
AMI GRAPH_IR, preventing an unbounded scan.

The first decoder path requires the architecture-specific
`<architecture>.attention.layer_norm_rms_epsilon` metadata. AMPER does not invent a default epsilon
when it is absent.

## Tensor binding

For layer N the first executor binds canonical GGUF decoder FFN names:

- `blk.N.ffn_norm.weight`
- `blk.N.ffn_gate.weight`
- `blk.N.ffn_up.weight`
- `blk.N.ffn_down.weight`

Shape relations are verified before execution:

- norm width = hidden size;
- gate/up input width = hidden size;
- gate/up output widths match;
- down input width = FFN width;
- down output width = hidden size.

## Bounded mobile memory

Matrices are never copied wholesale into the JVM.

`AmiTensorWindowExecutor` processes matrices in row tiles with an 8 MiB default maximum mapped
window. Each tile is mmap'd read-only from FOUNDATION_WEIGHTS and immediately sent to the AMNE
primitive selected for that exact tensor encoding.

Current matrix execution supports the canonical AMNE v2 encodings:

- F32;
- Q4_0;
- Q8_0.

Norm vectors support F32 and F16.

Unsupported encodings fail closed. Phase606 does not reinterpret Q4_K/Q5_K/Q6_K or another future
format as Q4_0.

## Qualified dispatch

Every operation uses `AmneProcessKernelRuntime`.

If Phase605 benchmark admission has not run, the deterministic reference backend executes the
primitive. If a primitive was numerically qualified and benchmark-admitted, the native AMNE backend
may execute that primitive. Admission is still per primitive.

## Physical Android probe

For a selected GGUF that has already been compiled to AMI, the app exposes:

`Run AMI layer-0 FFN probe`

The probe uses a deterministic synthetic hidden state so it tests the imported model weights without
depending on tokenizer or generation state.

Success reports:

- hidden width;
- FFN width;
- total mapped bytes;
- mmap window count;
- actual backend IDs used;
- output L1 checksum;
- wall time.

## Next

Phase607 adds the attention side of decoder layer 0:

- attention RMSNorm;
- Q/K/V tensor-window projections;
- per-head RoPE;
- GQA head mapping;
- causal KV cache;
- attention output projection;
- residual composition with the Phase606 FFN path.

That produces the first complete AMI decoder layer.
