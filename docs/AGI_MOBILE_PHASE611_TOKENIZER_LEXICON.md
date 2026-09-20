# Phase611 — AMI Tokenizer Lexicon and Exact Detokenization Boundary

Phase611 begins the text boundary for direct AMI inference without pretending that every GGUF
tokenizer family shares one encoder.

## Preserved tokenizer lexicon

`AmiTokenizerLexiconReader` scans the GGUF metadata bytes already preserved inside the AMI
TOKENIZER section and materializes only tokenizer-owned fields:

- tokenizer model;
- pre-tokenizer identity;
- vocabulary token strings;
- optional token scores;
- optional token types;
- optional BPE merge rules;
- BOS/EOS/unknown/padding token ids;
- add-BOS/add-EOS flags.

All other large metadata arrays continue to be skipped with bounded lengths.

The vocabulary, score and token-type counts must agree before the lexicon is admitted.

## Detokenization

Phase611 implements token-id -> UTF-8 text for two stored tokenizer representations.

### SentencePiece/Llama-style

- converts the SentencePiece word-boundary marker `▁` back to a space;
- joins normal pieces;
- decodes `<0xXX>` byte-fallback pieces as raw UTF-8 bytes;
- can skip CONTROL, BOS and EOS tokens.

### GPT-2 byte-level BPE storage

AMPER reconstructs the canonical GPT-2 byte-to-unicode mapping and reverses stored BPE token strings
back into the original byte stream before UTF-8 decoding.

This handles whitespace and multi-byte UTF-8 output without relying on llama.cpp.

## Fail-closed scope

Unknown tokenizer model identifiers are rejected.

Phase611 deliberately does **not** implement prompt text -> token ids yet. Tokenization rules differ
between SentencePiece unigram, GPT-2 BPE and newer pre-tokenizers, so AMPER will not substitute a
heuristic encoder and silently alter model behavior.

## Direct AMI progress

The standalone path is now:

```
token ids
 -> AMI embeddings
 -> attention/KV + FFN layers
 -> output logits
 -> sampler
 -> generated token ids
 -> AMI detokenizer
 -> UTF-8 output
```

The remaining text-input boundary is the encoder.

## Next

Phase612 will implement tokenizer-family-specific encoders beginning with the preserved tokenizer
model/pre-tokenizer metadata and will qualify round-trip behavior against known tokenizer fixtures.

After that, AMNE can expose a full UTF-8 prompt -> streamed UTF-8 response backend and be tested
side-by-side with the current llama fallback.
