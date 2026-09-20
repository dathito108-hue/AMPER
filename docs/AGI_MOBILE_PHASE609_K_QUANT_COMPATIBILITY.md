# Phase609 — Exact K-Quant Compatibility for AMI/AMNE

Phase609 removes a practical compatibility blocker for mobile GGUF models by adding exact portable
execution for the common ggml K-quants:

- Q4_K — GGML type 12 — 256 elements / 144 bytes
- Q5_K — GGML type 13 — 256 elements / 176 bytes
- Q6_K — GGML type 14 — 256 elements / 210 bytes

## ABI source of truth

The bit layouts are implemented against the exact llama.cpp revision already pinned by AMPER:

`972d2313bc0bf0a45f634f77d95c9fb03aeab12c`

Phase609 mirrors that revision's block field order, `get_scale_min_k4` packing and Q4_K/Q5_K/Q6_K
dequantization semantics. The implementation is not inferred from model names or current upstream
master.

## SOURCE_EXACT remains unchanged

AMI FOUNDATION_WEIGHTS remain byte-for-byte copies of the admitted GGUF tensor data.

K-quant support is transient execution decoding only. AMPER does not:

- requantize the source tensor;
- rewrite the AMI foundation;
- substitute a different quantization scheme;
- claim native acceleration before qualification.

## Dispatch safety

New AMNE primitives exist for:

- MATVEC_Q4_K
- MATVEC_Q5_K
- MATVEC_Q6_K

The deterministic reference backend advertises them immediately.

The current native ARM64 backend does not advertise them in Phase609. Therefore qualified dispatch
cannot accidentally route K-quants to an unqualified native implementation. Existing qualified
native F32/Q4_0/Q8_0 primitives remain available.

## Decoder and embedding compatibility

The bounded AMI matrix executor now accepts Q4_K/Q5_K/Q6_K rows. Window sizing uses the exact
256-element super-block geometry.

The token embedding reader also decodes one requested K-quant row without materializing the full
vocabulary matrix.

## Golden tests

Golden blocks lock:

- Q4_K 6-bit scale/min packing + 4-bit values;
- Q5_K low nibble + high-bit plane;
- Q6_K low nibble + upper two-bit plane + signed per-group scales;
- exact block byte lengths;
- AMNE planner/dispatch behavior;
- malformed storage rejection.

## Next

Phase610 can add numerically qualified native ARM64 K-quant matvec kernels. Because the portable path
already establishes exact semantics, every native implementation can be compared against a stable
oracle before dispatch.

After native K-quant admission, the model pipeline continues with final RMSNorm + output/LM-head
projection for a complete next-token forward pass.
