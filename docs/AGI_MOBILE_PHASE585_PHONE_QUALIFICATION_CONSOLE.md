# AMPER Phase585 — Phone-Native Qualification Console

Phase585 removes the PC/ADB dependency from normal physical qualification.

The AMPER main screen now exposes an **AGI-Mobile physical qualification** console.

## Phone-only sequence

1. Install the exact qualification APK.
2. Open AMPER and tap **Start / reset qualification**.
3. Tap **Run resource batch (4 tiers)** four times.
   - Each batch runs one bounded sample at 0, 16, 32 and 64 MiB requested stress.
   - The harness safety-caps the allocation when device memory is constrained.
4. Create at least eight process-restart samples:
   - tap **Prepare + restart AMPER process**;
   - AMPER terminates its own process only after the encrypted checkpoint is synchronously persisted;
   - reopen AMPER;
   - the restored exact digest is recorded automatically.
5. Create at least four reboot samples:
   - tap **Prepare phone reboot sample**;
   - reboot the phone normally;
   - open AMPER once after boot;
   - the restored exact digest and boot transition are recorded automatically.
6. The restart domain still requires at least 16 restart/reboot samples total. After the minimum
   eight process restarts and four reboots, collect four additional restart samples of either type.
7. Tap **Refresh qualification progress** whenever needed.
8. Tap **Finalize exact-APK 10-domain qualification** only after the sample floors are met.

Finalize reruns all ten canonical domains against the SHA-256 of the installed APK. A report from a
different APK or device cannot qualify the current subject.

The console does not execute Android tools, widen AuthorityGate or bypass approval. Process
termination is limited to AMPER's own process and happens only from an explicit user button.
