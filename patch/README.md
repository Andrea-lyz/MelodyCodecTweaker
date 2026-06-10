# Melody Patch Workspace

This directory holds the local, branch-only assets for the non-root Melody repack experiment.

Current stage:

1. Keep the target `com.oplus.melody` APK in `patch/apks/`.
2. Use GitHub Actions to decode, inspect, rebuild, align and test-sign the APK.
3. Treat the first artifact as a repack smoke test, not a functional codec patch.
4. Same-package replacement is now blocked on stock non-root devices:
   `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: oplus named app is not match signature`.
5. Do not add helper-dex or smali entrypoint injection unless the target environment has
   OEM signing, root, LSPosed, or a system signature-check bypass.
6. For non-root users, pivot to a standalone app that mirrors the Bluetooth Codec Changer
   direct A2DP reflection path.

The detailed implementation checklist is in `patch/docs/NON_ROOT_MELODY_REPACK_TODO.md`.
