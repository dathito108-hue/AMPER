# Hotfix588 — Android Canonical App-Private Storage Root

## Physical-device failure

The Safe Launcher captured a startup crash before AMPER runtime initialization:

```
java.lang.IllegalArgumentException:
managed sovereign path contains a symbolic link: /data/user/0
```

The stack reached `SovereignPathIdentity.requireSafeExistingComponents()` through
`MemoryKeyManifestStore`, `EncryptedFileMemoryJournal.managed()`, and
`AmperRuntime.persistentEncrypted()`.

## Root cause

Some Android builds expose `Context.filesDir` through an Android-managed symbolic-link
prefix such as `/data/user/0`.

AMPER's sovereign path identity checks correctly reject symbolic links inside managed
storage, but the raw Android path caused that protection to reject a platform-owned alias
before AMPER's own namespace was reached.

## Fix

`AndroidAppPrivateStorage` canonicalizes only the trusted Android app-private
`filesDir` boundary. AMPER then derives `amper-sovereign` descendants from the
canonical root.

Applied to:

- `MainActivity` persistent encrypted runtime root
- `ReflexLearningJobService` runtime restore and reflex artifact root
- `AndroidAgiMobileDeviceQualificationHarness` physical evidence root

`SovereignPathIdentity` itself is unchanged, so symlink rejection inside AMPER-managed
storage remains fail-closed.

## Regression coverage

A JVM test creates a real symbolic-link alias and verifies that the Android boundary
helper resolves it to the physical directory before sovereign descendants are derived.
