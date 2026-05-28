# wireless-headset-codec-panel

LSPosed module that injects a "蓝牙音质" block into two surfaces of the OPPO/OnePlus
"无线耳机" app (`com.oplus.melody`).

| Surface | Insertion point | Source class |
| ------- | --------------- | ------------ |
| HighAudio (高解析度音频 二级页) | Below "Hi-Res 模式开关" `PreferenceCategory` | `com.oplus.melody.ui.component.detail.highaudio.HighAudioPreferenceFragment` |
| OneSpace (底部快捷面板) | Between `pref_noise_menu_category` and `pref_more_setting_category` | `com.oplus.melody.onespace.d` (`OneSpaceListFragment`) |

The block contains four rows, all rendered with COUI/`androidx.preference` types to match the
host visual style:

1. **当前编解码器** — read-only; shows the live codec name from `BluetoothA2dp.getCodecStatus`.
2. **播放质量** — `ListPreference`; visible only for LDAC / LHDC, entries derived from
   `getCodecsSelectableCapabilities().codecSpecific1`.
3. **采样率** — `ListPreference`; entries derived from
   `getCodecsSelectableCapabilities().sampleRate` bitmask. Includes 192 kHz when supported.
4. **记住此耳机的选择** — `SwitchPreferenceCompat`; off by default. When on, the chosen
   `{codecSpecific1, sampleRate}` is persisted per MAC and replayed on next reconnect.

## API & framework requirements

This module is built against [**libxposed API 101**](https://github.com/libxposed/api). It is
loaded only by frameworks implementing that API level (LSPosed 1.9+ etc.). The legacy
`XposedBridge` / `XposedHelpers` interface is **not** used.

Discovery files:

```
app/src/main/resources/META-INF/xposed/java_init.list   # entry FQCN
app/src/main/resources/META-INF/xposed/module.prop      # minApiVersion=101 staticScope=true
```

## Build

```bash
# 1. One-time: bootstrap the Gradle wrapper.
gradle wrapper

# 2. Build a release APK (R8 enabled).
./gradlew :app:assembleRelease

# Output:
#   app/build/outputs/apk/release/app-release-unsigned.apk
```

Sign the APK with your own debug keystore for sideloading:

```bash
apksigner sign --ks ~/.android/debug.keystore \
               --ks-pass pass:android \
               --key-pass pass:android \
               app/build/outputs/apk/release/app-release-unsigned.apk
```

A `local.properties.sample` is provided; copy to `local.properties` and set `sdk.dir`.

The APK declares LSPosed scope on `com.oplus.melody` and `com.android.bluetooth`. Enable both
scopes in LSPosed Manager.

## Master switch

Tap the launcher icon ("蓝牙音质（Melody）") for `MasterSwitchActivity` — toggles
`module_prefs.xml#enabled` (default `true`). The host process picks the change up via
`XposedModule.getRemotePreferences("module_prefs")` on its next start.

## Write path order

```
direct API   →  com.android.bluetooth bridge (AIDL)   →  Settings.Global dev-options keys
```

A `Toast` warns the user when the fallback to `Settings.Global` is taken. A 3 s confirmation
window watches for `BluetoothA2dp.ACTION_CODEC_CONFIG_CHANGED`; if no echo is observed the UI
rolls back to the value reported by `getCodecStatus`.

## State sources

| Signal | Source | Refresh trigger |
| ------ | ------ | --------------- |
| Active codec / current quality / current sample rate | `BluetoothA2dp.getCodecStatus` | host-side broadcast `ACTION_CODEC_CONFIG_CHANGED`, AIDL listener push, fragment `onStart` |
| Selectable quality | `getCodecsSelectableCapabilities().codecSpecific1` | same as above |
| Selectable sample rate | `getCodecsSelectableCapabilities().sampleRate` bitmask | same as above |
| Remember toggle | `melody_lsp_codec_prefs.xml` | UI toggle, MAC switch |
| Master switch | `module_prefs.xml` (mirrored via `getRemotePreferences`) | next host process start |

## Logging

```bash
adb logcat -s MelodyCodecLsp:V
```

Logs are also forwarded to the libxposed framework log via `XposedInterface.log`, so they
appear in the LSPosed module log viewer too. Every key event uses the structured form
`evt=<name> k=v ...` so `grep` is enough for triage.

## Source layout

```
module/app/src/main/
├── AndroidManifest.xml
├── resources/META-INF/xposed/
│   ├── java_init.list                       # libxposed entry
│   └── module.prop                          # minApiVersion=101
├── res/
│   ├── values/{strings,arrays}.xml          # zh-CN + xposed_scope
│   └── values-en/strings.xml                # en fallback
├── aidl/xyz/melodylsp/codec/bridge/         # ICodecBridge / Snapshot / Request
└── java/xyz/melodylsp/codec/
    ├── MelodyCodecLspEntry.java             # XposedModule entry
    ├── bridge/                              # Parcelable types crossing the bridge
    ├── bt/BluetoothCodecReflect.java        # @hide A2DP reflection
    ├── label/CodecLabelTable.java           # human-readable labels w/ fallbacks
    ├── storage/PreferenceStore.java         # per-MAC remember store
    ├── host/                                # host-side hooks + UI controller
    │   ├── BridgeServiceLocator.java
    │   ├── CodecBlockBuilder.java
    │   ├── CodecBridgeClient.java
    │   ├── CodecController.java             # consumes AIDL listener pushes
    │   ├── CodecPreferences.java
    │   ├── ConnectionStateReplayer.java
    │   ├── HostHookInstaller.java
    │   ├── MelodyResIds.java
    │   ├── SettingsGlobalFallback.java
    │   └── WriteResult.java
    ├── system/                              # com.android.bluetooth side
    │   ├── CodecBridgeService.java          # AIDL Stub w/ caller UID check
    │   └── SystemHookInstaller.java
    ├── ui/MasterSwitchActivity.java
    └── util/MLog.java
```

## Implementation notes

- All hook bodies are wrapped in try / catch and downgrade to `MLog.e`; the host panel never
  crashes (Property 4 / 6).
- `ConnectionStateReplayer` only replays values that are still in the negotiated capabilities;
  missing values are silently skipped (Requirement 7.9).
- `CodecBridgeService` enforces caller UID == melody before doing any privileged work.
- `R8` is enabled for release builds; `proguard-rules.pro` keeps the entry, AIDL types, and
  `XposedModule` callbacks reachable. Other classes can be obfuscated.

## Known limitations

- Property tests sketched in `tasks.md` are not yet wired to this demo module.
- The system-side bridge requires the BT process scope to be granted in LSPosed and a ROM that
  permits `ServiceManager.addService` from `com.android.bluetooth`. When unavailable, the
  module silently falls back to direct API + Settings.Global; this is intentional.
- The decompiled obfuscation map (`f27613b` / `f17198C` / `y8.g` / `com.oplus.melody.onespace.d`)
  matches `16.6.3`. Other versions emit a `WARN` log without injecting and the host panel
  renders unchanged.
