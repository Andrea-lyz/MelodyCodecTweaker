# Melody Patch Workspace

This directory holds the local, branch-only assets for the non-root Melody repack experiment.

Current stage:

1. Keep the target `com.oplus.melody` APK in `patch/apks/`.
2. Use GitHub Actions to decode, inspect, rebuild, align and test-sign the APK.
3. Treat the first artifact as a repack smoke test, not a functional codec patch.
4. Add helper-dex and smali entrypoint injection after the repack smoke test is installable.

The detailed implementation checklist is in `patch/docs/NON_ROOT_MELODY_REPACK_TODO.md`.
