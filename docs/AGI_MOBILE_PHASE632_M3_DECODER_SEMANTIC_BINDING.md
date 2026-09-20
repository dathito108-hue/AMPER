# Phase632 — M3 AMI2 Decoder Semantic Binding

Phase632 connects the verified AMI2 execution view to the existing qualified AMI decoder planners
and executors without creating a second decoder or kernel stack.

## Semantic projection, not an AMI1 runtime

AMI2 already preserves the semantic payload encodings used by the existing decoder stack:

- TOKENIZER
- LOGICAL_GRAPH
- TENSOR_INDEX
- FOUNDATION_WEIGHTS

`Amne2DecoderSemanticBindingFactory` builds an in-memory compatibility index over the exact verified
AMI2 section offsets. It does not rewrite the file, copy model weights, create an AMI1 container, or
select AMI1 as a production backend.

The underlying artifact remains canonical AMI2.

## Identity binding

The binding requires the Phase631 execution view identity to match:

- AMI2 foundation id
- semantic SHA-256
- full AMI2 artifact SHA-256

The projected decoder view retains the same file and the same verified section offsets/digests.
Tensor graph and preserved GGUF metadata are then decoded through the existing semantic readers.

The resulting binding also records:

- tensor-index SHA-256
- foundation-weights SHA-256

This prevents decoder planning from drifting away from the admitted AMI2 semantic identity.

## No duplicated decoder path

Phase632 reuses:

- AmiTensorGraphReader
- AmiPreservedGgufMetadataReader
- AmiDecoderAttentionPlanner / executor
- AmiDecoderFfnPlanner / executor
- AmiDecoderStackPlanner / executor
- AmiOutputHeadPlanner / executor
- existing AMNE kernel registry and qualified dispatch

No parallel AMNE2 decoder implementation is introduced.

## Next M3 slice

Phase633 should introduce the AMNE2 execution session that owns this semantic binding, decoder plans,
KV state, bounded execution windows, and cancellation lifecycle. The session should call the existing
decoder executors through one AMNE2 production boundary rather than exposing legacy AMI objects to
higher layers.
