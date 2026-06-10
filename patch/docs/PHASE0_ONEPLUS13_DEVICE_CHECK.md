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
- Runtime permissions are not currently granted:
  - `android.permission.BLUETOOTH_CONNECT`
  - `android.permission.BLUETOOTH_SCAN`
  - location / phone / record-audio related runtime permissions
- `DetailMainActivity` and `OneSpaceDetailActivity` are both resolver-visible:
  - `.ui.component.detail.DetailMainActivity`
  - `.onespace.OneSpaceDetailActivity`

## Interpretation

The same-package repack path is still viable enough to continue:

- The installed Melody package is a data app path, not a mounted `/system` or `/product` APK.
- Because the current package is OEM-signed, it has OPlus private permissions today. A repacked APK will lose those signature grants unless signed with the OEM key.
- Codec control itself should not need those OPlus private permissions if we mirror the Bluetooth Codec Changer direct A2DP reflection path.
- After installing a repacked APK, we must explicitly request `BLUETOOTH_CONNECT` before attempting `getProfileProxy`, `getConnectedDevices`, `getCodecStatus`, or `setCodecConfigPreference`.

## Remaining Phase 0 Checks

- Test installing the repack-smoke artifact after uninstalling the current OEM package.
- Record whether install fails with signature/update errors.
- If install succeeds, verify whether core Melody surfaces still open despite losing OPlus private grants.
- Re-run `dumpsys package com.oplus.melody` after repack install and compare granted install/runtime permissions.

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
