# AMPER Phase581-583 — Fast Qualification APK Artifact

## Goal

Provide one low-cost path to obtain the exact canonical APK needed by the Android physical
qualification harness without spending a full llama/MTMD/Vulkan verification cycle.

## Fast dispatch

Run the existing **Android CI** workflow with:

- mode: `fast`

The verify job still runs:

- JVM/unit tests;
- canonical debug APK build.

For manual workflow dispatches, the verify job now uploads:

- `app-debug.apk`;
- `app-debug.apk.sha256`;
- `app-debug.apk.provenance.txt`.

The artifact name includes the exact Git commit SHA and is retained for seven days.

## Native cost boundary

A `fast` dispatch sets `native_changed=false`, so these jobs remain skipped:

- optional-llama-aar;
- native-mtmd-arm64;
- native-mtmd-vulkan-arm64.

A `full` dispatch still forces all native jobs.

Pull requests continue to auto-detect native/build changes. Workflow-file-only changes no longer
force native builds by themselves; a workflow change that needs native validation must use an explicit
`full` dispatch.

## Physical qualification identity

Before installing the APK, verify the downloaded file against `app-debug.apk.sha256`.
The Android qualification harness independently computes SHA-256 again from the installed base APK.
Physical evidence is accepted only when the report remains bound to that exact APK subject and one
device digest.

This phase changes artifact delivery only. It does not change any AGI-Mobile qualification verdict.
