# Phase 0 Device Check - OnePlus 13

Source: user-provided ADB output from `adb shell pm path com.oplus.melody` and
`adb shell dumpsys package com.oplus.melody`.

## Findings

- `com.oplus.melody` is installed from `/data/app/.../base.apk`.
- `scannedAsStoppedSystemApp=false`.
- Package flags do not include `SYSTEM`.
- Current installed package is `versionName=16.1.1`, `versionCode=16001001`.
- Target patch APK in this branch is `16.7.1`, so it is version-up relative to the currently installed package.
- Installer / initiating package is `com.oplus.exsystemservice` with uid `1000`.
- Current package signature is the OEM signature, so many OPlus safe/signature permissions are granted.
- Repack-smoke install failed on stock non-root with:
  - `Failure [INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: oplus named app is not match signature]`
- Runtime permissions are not currently granted:
  - `android.permission.BLUETOOTH_CONNECT`
  - `android.permission.BLUETOOTH_SCAN`
  - location / phone / record-audio related runtime permissions
- `DetailMainActivity` and `OneSpaceDetailActivity` are both resolver-visible:
  - `.ui.component.detail.DetailMainActivity`
  - `.onespace.OneSpaceDetailActivity`

## Interpretation

The same-package repack path is blocked on this stock non-root device:

- Although the installed Melody package is a data app path, OPlus still enforces a named-app / shared-user signature match.
- The failure happens before app launch, so runtime permission handling or code changes cannot fix it.
- Because the current package is OEM-signed, it has OPlus private permissions today. A repacked APK will lose those signature grants unless signed with the OEM key.
- Codec control itself should not need those OPlus private permissions if we mirror the Bluetooth Codec Changer direct A2DP reflection path.
- Therefore the non-root route should pivot away from replacing `com.oplus.melody`.
- The viable non-root path is a standalone app or differently named experimental shell that mirrors the Bluetooth Codec Changer direct A2DP reflection path and requests `BLUETOOTH_CONNECT` itself.

## Phase 0 Result

- Same-package Melody replacement: blocked without root, OEM signing key, or a system signature check bypass.
- LSPatch / APK patching cannot preserve the original OEM signature after modifying the APK.
- `adb install -r`, downgrade flags, or runtime permission changes do not address this error class.
- Keep the GitHub Actions repack workflow as a smoke/forensics artifact only; do not invest in Melody UI injection for stock non-root users.

## PowerShell Note

This command fails in PowerShell because `grep` is not a local command:

```powershell
adb shell pm list packages | grep melody
```

Use one of these instead:

```powershell
adb shell "pm list packages | grep melody"
adb shell pm list packages | Select-String melody
```
