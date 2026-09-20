# Phase592 — App-private native model staging

## Physical finding

Phase591 successfully passed governed Titan route admission on-device:

- selected model: imported GGUF
- backend: `llama.cpp-aar`
- selection: `NORMAL_RANKING`
- estimated execution memory: 1256 MiB

The local inference smoke test then failed before native model load with:

```
FileNotFoundException: /proc/self/fd/147
open failed: EACCES (Permission denied)
```

## Root cause

AMPER's content-URI bridge exposed an already-open Android document as
`/proc/self/fd/<fd>`. Newer Android/SELinux builds can deny reopening that descriptor through
its procfs pathname even though the app legitimately owns the underlying document permission.

Removing identity verification would not solve the architecture: the native llama loader also
requires a pathname and may hit the same procfs restriction.

## Phase592

AMPER now supports two trusted native-path contracts:

1. descriptor-bound paths where the platform permits them;
2. validated app-private paths whose filesystem identity remains stable for the callback.

For content-URI models/projectors, the resolver lazily stages the admitted bytes into an
app-private cache using a content-addressed SHA-256 filename. Before llama/MTMD loads the staged
path, `ModelArtifactIdentityVerifier` revalidates the exact staged bytes against the installed
SHA-256, length and GGUF structural identity.

Properties:

- durable model/projector locator remains the original user content URI;
- no arbitrary external filesystem path is accepted;
- staging is lazy and content-addressed;
- staged paths are inside canonical Android app-private cache storage;
- symlink/type and filesystem-identity checks remain fail-closed;
- stale-length cache entries are discarded and restaged;
- the native backend still performs full artifact identity verification;
- Android/Titan capability, resource and authority gates are unchanged.

The same path contract is wired into the llama AAR, native text and MTMD paths so the procfs
restriction is not reintroduced when moving to the final native runtime.
