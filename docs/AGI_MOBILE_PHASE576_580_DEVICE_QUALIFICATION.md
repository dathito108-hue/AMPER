# AMPER AGI-Mobile Device Qualification — Phase576-580

## Goal

Phase576-580 builds the physical Android evidence path for the final two canonical domains:

- MOBILE_RESOURCE_RESILIENCE
- RESTART_RECOVERY

This phase does **not** mark either domain qualified in CI. JVM tests validate only the evidence
format, subject binding and fail-closed probe contract. Qualification requires evidence collected by
an installed Android APK.

## Artifact and device binding

The Android harness computes SHA-256 directly from the installed base APK and builds the canonical
AgiMobileQualificationSubject from package name, version code, version name and that APK digest.

Device evidence is also bound to a digest over Android build fingerprint, manufacturer, model, device,
SDK level and supported ABIs. A report from another APK or another device is rejected.

## Resource-stress evidence

A qualification session requires at least 16 resource samples.

Samples rotate through four bounded stress tiers:

- tier 0: 0 MiB
- tier 1: 16 MiB
- tier 2: 32 MiB
- tier 3: 64 MiB

The allocation is safety-capped when free RAM is low. Each sample records real battery, charging,
available RAM, low-memory state, free app storage, thermal status and the live ResourceGovernor budget.
It also evaluates the canonical Reflex learning resource policy and runs a bounded sovereign runtime
workload while the allocation is resident.

Qualification requires at least four samples from every requested tier and a score meeting the
canonical MOBILE_RESOURCE_RESILIENCE floor.

## Restart/reboot evidence

A restart checkpoint first persists a canonical qualification report through the production encrypted
MemoryOs. The harness records its canonical digest together with the current process identity, boot
counter and monotonic uptime.

On the next process start, a sample is accepted only if:

- the APK subject is unchanged;
- the device identity is unchanged;
- the process identity changed;
- the encrypted runtime restored the exact expected report digest.

A BOOT_COUNT change or monotonic-uptime reset classifies the sample as REBOOT_RECOVERY; otherwise it is
PROCESS_RESTART.

The final gate requires at least 16 restart samples, including at least eight process restarts and four
reboots. With the canonical 0.95 score floor, 16/16 must pass when exactly 16 samples are collected.

## ADB qualification commands

The launcher Activity accepts one extra:

`amper_device_qualification_command`

Start a fresh subject-bound session:

```sh
adb shell am force-stop io.amper.neuroos
adb shell am start -n io.amper.neuroos/.MainActivity \
  --es amper_device_qualification_command start
```

Collect one resource sample. Repeat at least 16 times; tiers rotate automatically:

```sh
adb shell am force-stop io.amper.neuroos
adb shell am start -n io.amper.neuroos/.MainActivity \
  --es amper_device_qualification_command resource
```

Prepare one restart checkpoint:

```sh
adb shell am force-stop io.amper.neuroos
adb shell am start -n io.amper.neuroos/.MainActivity \
  --es amper_device_qualification_command prepare-restart
```

For a process-restart sample, then force-stop and reopen without a command:

```sh
adb shell am force-stop io.amper.neuroos
adb shell am start -n io.amper.neuroos/.MainActivity
```

For a reboot-recovery sample, prepare the checkpoint, reboot the device, then reopen AMPER:

```sh
adb reboot
# after Android has booted:
adb shell am start -n io.amper.neuroos/.MainActivity
```

Query progress:

```sh
adb shell am force-stop io.amper.neuroos
adb shell am start -n io.amper.neuroos/.MainActivity \
  --es amper_device_qualification_command status
adb logcat -d -s AMPER-DeviceQualification
```

When at least 16 resource samples, 16 restart samples, eight process restarts and four reboot samples
have been collected, finalize:

```sh
adb shell am force-stop io.amper.neuroos
adb shell am start -n io.amper.neuroos/.MainActivity \
  --es amper_device_qualification_command finalize
adb logcat -d -s AMPER-DeviceQualification
```

On a debuggable build the final report can also be inspected with:

```sh
adb shell run-as io.amper.neuroos cat \
  files/amper-sovereign/device-qualification/final-report.txt
```

## Final all-domain run

Finalize does not evaluate only the two Android domains. It reruns all existing canonical probe packs
on the exact installed APK subject:

1. SYSTEM1_FAST_PATH
2. NATIVE_SYSTEM2_REASONING
3. GENERALIZATION
4. LONG_HORIZON_EXECUTION
5. MEMORY_WORLD_MODEL
6. SELF_LEARNING
7. SELF_EVOLUTION
8. AUTHORITY_INVARIANTS
9. MOBILE_RESOURCE_RESILIENCE
10. RESTART_RECOVERY

Only that same-artifact ten-domain report may produce the project verdict QUALIFIED.

## CI boundary

FAST CI must compile the Android harness and validate the codec/probe contract. CI is not a physical
Android qualification run and must not be described as evidence that MOBILE_RESOURCE_RESILIENCE or
RESTART_RECOVERY passed.
