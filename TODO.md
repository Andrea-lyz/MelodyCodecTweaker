# MelodyCodecTweaker — TODO

整理两个方向：兼容性兜底（host 升级 / 不同设备 / 不同 codec 的鲁棒性）和 LE Audio 开关。
按 ROI（实施成本 / 风险收益）排序，可独立挑选。

---

## 实现状态（2026-05）

已实现：

- **A1 OneSpace 启发式定位** — 新增 `hookOneSpaceActivity()`，hook `OneSpaceDetailActivity.onResume/onStart`（manifest 保留 FQN），扫 FragmentManager 找含 OneSpace key 的 fragment 后注入。
- **A2 PrefRef setter 签名兜底** — `PrefRef.invokeSetter()` 名字找不到时按唯一 void 签名兜底（歧义时不猜）。
- **A3 DetailMain 备用锚点** — `EqualizerItem` → 首个可见 PreferenceCategory 的 fallback 注入链。
- **A4 LHDC vendor id 放宽** — 范围扩到 `0x10..0x3F`，`0x20+` 用 `looksLikeLhdc()` 二次确认 vendor word。
- **A5 LifecycleEventObserver 修复** — 改用 `Application.ActivityLifecycleCallbacks` 清理订阅，attach 去重，杜绝 receiver 泄漏。
- **A6 CDM hook 强化** — `isCdmEnforcementMethod()` 匹配 cdm/association/privileged 系列方法名。
- **B1 + B2 LE Audio 一键切换** — `LeAudioIpc` / `WirelessSettingsHookInstaller`（com.oplus.wirelesssettings scope）/ `LeAudioManager`；melody 主面板注入 LE Audio 开关，点击后由 wirelesssettings 进程（system uid）弹出官方 `COUIAlertDialogBuilder` 警告弹窗 → 用户确认 → `LeAudioProfile.setEnabled` → 回传状态。LE Audio 开启时主面板和 OneSpace 都显示"蓝牙音质 : LC3"并隐藏播放质量/采样率选项卡，关闭则恢复。

---

## A. 兼容性兜底（让模块在 host 升级 / 新设备 / 新 codec 下尽量不挂）

### A1. OneSpace fragment 启发式定位 ⭐⭐⭐⭐⭐
**问题**：当前硬编码 `com.oplus.melody.onespace.d` 这个 R8 短哈希类名。下个版本 host 升级很可能改名（变成 `c` / `e` / 其他单字母），整个 OneSpace 注入直接失效，没有兜底。base class hook (`com.oplus.melody.ui.base.c`) 覆盖不到 OneSpace，因为 `onespace.d` 直接继承 `com.coui.appcompat.preference.h`。

**修复**：
- Hook `com.oplus.melody.onespace.OneSpaceDetailActivity.onResume`（FQN，manifest 注册保留）
- 拿到 Activity 后扫描 `getSupportFragmentManager().getFragments()`
- 对每个 fragment 调 `getPreferenceScreen()`，检查是否含 `pref_more_setting` / `pref_noise_switch` 等 OneSpace 特有 key
- 命中即注入

**收益**：跨 host 版本最关键的单点故障消除。
**代价**：~40 行代码。

---

### A2. PrefRef setter 加签名兜底 ⭐⭐⭐⭐
**问题**：`setKey` / `setTitle` / `setOrder` / `setVisible` / `setSelectable` / `setEnabled` 等所有 setter 都只按方法名查找。R8 改名会**静默失败**（无 exception，UI 看着空白）。我们之前踩过 `setKey` 这一坑。`addPreference` 已经按签名兜底，其他没有。

**修复**：仿照 `addPreference` 的 `findUnaryMethod` 模式：
- setter 名字找不到时按签名查找：1-arg 方法、参数类型匹配、返回 void
- 用 `param0 == String.class` / `param0 == CharSequence.class` 等做精确匹配
- 在 `setKey` / `setTitle` / `setSummary` / `setOrder` / `setVisible` / `setSelectable` / `setEnabled` / `setIconSpaceReserved` / `setPersistent` / `setChecked` 上都加

**收益**：一次性消除"R8 改 setter 名 → UI 整体哑火"的整类故障。
**代价**：~60 行代码（统一封装 invoke 路径）。

---

### A3. DetailMain 备用注入锚点 ⭐⭐⭐
**问题**：当前只有 `HiQualityAudioItem` 一个锚点（Hi-Res 模式开关）。不支持 Hi-Res 的耳机（如开放式耳机、低端机型）整个 DetailMain 注入完全消失。

**修复**：fallback 锚点链：
1. 优先：`HiQualityAudioItem`（Hi-Res 开关，OPPO 高端耳机）
2. 次选：`EqualizerItem`（均衡器，几乎所有耳机有）
3. 兜底：扫描 `O7.O.f4809x` 里定义的顺序 `["product","battery","noise","devices","sound","ai","game","other","earphone","about","disconnect","guide"]`，找到第一个 visible 的 PreferenceCategory，在它后面插入

**收益**：覆盖所有非 Hi-Res 耳机的 DetailMain 注入。
**代价**：~50 行代码。

---

### A4. LHDC vendor id 范围放宽 ⭐⭐
**问题**：当前 `0x10..0x1F` 范围识别为 LHDC。OPPO 后续 ROM 给 LHDC 分配新 vendor id（比如 0x20+）就识别不出来，UI 显示 `Codec(0x20)`，picker 选项消失。

**修复**：
- 扩大范围到 `0x10..0x3F`
- 加二次确认：codec id 不在已知列表 + `codecSpecific1` 的高 bit pattern 看起来像 LHDC（lossless flag bit 等）→ 也当作 LHDC
- 详细可参考 LHDC LL/V5 vendor word 的 bit layout（Savitech 公开规范）

**收益**：未来 codec 演进时不需要每次都改代码。
**代价**：~20 行 + 需要查 LHDC 协议规范确认 bit 含义。

---

### A5. LifecycleEventObserver 名字解析修复（内存泄漏） ⭐⭐
**问题**：`androidx.lifecycle.LifecycleEventObserver` 名字解析失败（`ClassNotFoundException`），导致：
- `Subscription.unregisterReceiver` 永远不被调用 → 每次开 OneSpace/DetailMain 漏一个 BroadcastReceiver 引用
- `subscriptions` map 永不清理 → fragment 销毁后 entry 累积
- 短期看不出，长期使用内存累积

**修复**：
- 不直接用 `LifecycleEventObserver` FQN
- 改成扫 `androidx.lifecycle.*` 包下找含 `ON_DESTROY` 枚举常量的接口（Lifecycle.Event 枚举）
- 或者改用 `Fragment.getViewLifecycleOwner().getLifecycle().addObserver(...)` 然后用 `DefaultLifecycleObserver`（API 24+，androidx 类名 R8 不会改）
- 或者最简单：注册 `Application.ActivityLifecycleCallbacks` 监听 Activity 销毁，对应清理订阅

**收益**：解决长期使用内存累积。
**代价**：~30 行代码。

---

### A6. CDM 关联绕过的强化 ⭐
**问题**：`Bluetooth Utils.enforceCdmAssociation` hook 是 Path A 写入路径的关键。一旦 OPPO 把这个名字改了，Path A 直接被 framework 拒绝，UX 看到"切换未生效"。

**修复**：
- 不只 hook `enforceCdmAssociation`，扫所有名字含 `cdm` / `enforceAssociation` / `requireBluetoothPrivileged` 的方法都 hook
- 检测到 melody 调用栈就放行
- 详细看 Android 17 蓝牙模块 mainline 后是否新增其他 CDM 检查（`MetadataCheckUtils` 等）

**收益**：低 — 当前 CDM 检查能用就能用，改名概率较低。
**代价**：~30 行。

---

## B. LE Audio 开关

目标：用户在 melody 主面板就能切换 LE Audio，不用跳到系统设置。

### B1. Phase 1 — UI + 跳转方案（不做 hook，先验证集成）⭐⭐⭐⭐
**做什么**：
- `CodecBlockBuilder` 加一行 LE Audio `SwitchPreference` 到 codec block 末尾
- 仅在 DetailMain 显示，OneSpace 不显示
- 设备支持性探测：
  - 主信号：melody 自己的 SharedPreferences `Alive_le-device-info` 是否含此 MAC
  - 次信号：`BluetoothDevice.getUuids()` 含 ASCS (`0000184e`) / PACS (`0000184f`) / BASS (`00001850`) UUID
  - 都没命中 → `setVisible(false)`
- 状态读取：从 `Alive_le-device-info` 反射读 `LeDevice.isLeOpen()`
- 状态监听：注册 `oplus.bluetooth.device.action.PUT_LEA_MODE_INFO` 广播，热更新
- 点击行为：`startActivity(wireless.settings.DEVICE_PROFILES_SETTINGS)` + `highlight_args_key="MEDIA_AUDIO"` + `device` extra
  - 用户在系统设置完成切换（弹原版 dialog → 用户确认）
  - 用户回到 melody，PUT_LEA_MODE_INFO 触发 SwitchPreference 刷新

**完成标志**：开关能正确显示当前状态，点击后跳到系统设置高亮 LE Audio 那行，用户切换后回来 melody 自动同步。

**优点**：
- 不需要新增 LSPosed scope
- 不调任何 privileged API
- 用户知情同意走系统原版 dialog（合规、零风险）
- 即便 Phase 2 失败也能独立交付

**代价**：~100 行代码。

---

### B2. Phase 2 — 跨进程 hook 实现一键切换（在 Phase 1 之上叠加）⭐⭐⭐
**做什么**：
- `module/app/src/main/resources/META-INF/xposed/scope.list` 增加 `com.oplus.wirelesssettings`
- 新建 `WirelessSettingsHookInstaller.java`：
  - Hook `com.oplus.wirelesssettings.app.OplusWirelessSettingsApp.onCreate`
  - 拿到 Application context 后注册动态 BroadcastReceiver：
    ```
    action = "xyz.melodylsp.codec.action.SET_LE_AUDIO"
    extras: { mac: String, enable: boolean }
    ```
  - 也注册 `xyz.melodylsp.codec.action.QUERY_LE_AUDIO_SUPPORT`（携带 mac 查询是否支持，返回结果广播给 melody）
  - 收到 SET_LE_AUDIO 时反射调用：
    ```java
    LocalBluetoothManager mgr = LocalBluetoothManager.getInstance(ctx, null);
    LeAudioProfile p = mgr.getProfileManager().getLeAudioProfile();
    p.setEnabled(device, enable);
    ```
  - 安全：`Binder.getCallingUid()` 反查包名验证发送方是 `com.oplus.melody`
- `MelodyCodecLspEntry` 多调一个 `WirelessSettingsHookInstaller.install()`
- `CodecController` 中：
  - LE Audio Switch 点击改成"弹自己的 AlertDialog（复刻 wirelesssettings 文案）→ 用户点开启 → sendBroadcast(SET_LE_AUDIO)"
  - 不再 startActivity，UX 全程在 melody 内

**风险点**：
- wirelesssettings 进程可能未启动 → 发广播自动唤醒（实测 wirelesssettings 是常驻进程）
- `LocalBluetoothManager` 单例时机 → lazy 拿，拿不到 retry
- `LocalBluetoothManager` / `LeAudioProfile` FQN 在 settingslib AAR 中保留，反射稳定
- LSPosed scope 扩大 → 仅注册一个 receiver，源码可审计

**优点**：完美 UX，模块内一键完成。

**代价**：~150 行代码（installer + receiver + dialog + IPC 协议）。

---

### B3. （可选）替代路径 — root shell 直接切换 ⭐
仅在 Phase 2 失败时考虑。已经验证 `cmd bluetooth_manager set-priority` 在 OPPO ROM 上不一定可用。可探索：
- `service call bluetooth_le_audio <transaction-id>`（需要逆向 LeAudio AIDL transaction 编号，不稳定）
- 或直接 `pm grant com.oplus.melody android.permission.BLUETOOTH_PRIVILEGED`（需要 root，授权后 melody 自己就能调 setConnectionPolicy；但 BLUETOOTH_PRIVILEGED 是 platform-only signature 权限，pm grant 大概率被拒）

不推荐推进 B3，除非 B2 完全失败。

---

## 推进顺序建议

1. **A2 PrefRef setter 兜底**（最低成本最高收益，做一次受益所有 host 升级）
2. **A1 OneSpace 启发式定位**（OneSpace 注入鲁棒性最关键）
3. **B1 LE Audio UI + 跳转**（独立可交付，UX 提升明显）
4. **A5 LifecycleEventObserver 修复**（内存泄漏，长期质量）
5. **B2 LE Audio hook 一键切换**（B1 验证 UI 后再做）
6. **A3 DetailMain 备用锚点**（覆盖更多耳机型号）
7. **A4 LHDC vendor id 扩大**（未来 codec 演进的兜底）
8. **A6 CDM hook 强化**（最低优先级，当前没坏没必要修）
