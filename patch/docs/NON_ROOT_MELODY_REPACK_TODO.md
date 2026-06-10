# 非 Root 改包版 Melody 音质控制 TODO

目标：评估并推进一个“不依赖 Root / LSPosed”的版本，通过修改并重签 `com.oplus.melody` APK，把当前模块里的协议显示、LDAC/LHDC 播放质量、采样率、按耳机记忆选择迁移到改包版 Melody 内。

本文件只作为本地推进清单，不上传云端、不提交发布。

## 快速结论

方向可做，且比 AndroGhostInjector 更适合非 Root 用户。

已知前提：Bluetooth Codec Changer 已经在当前一加 13 / 欧加 ROM 上验证可用。因此 Phase 1 不再是“从零判断是否存在非 Root 可能性”，而是把 BCC 已验证的能力放到 Melody / 改包环境里复核一遍，确认权限、进程、目标设备解析、回读确认和 UI 集成没有额外变量。

`Bluetooth Codec Changer` 已经证明：普通 App 在不少 ROM 上可以通过反射调用：

- `BluetoothA2dp.getCodecStatus(BluetoothDevice)`
- `BluetoothA2dp.setCodecConfigPreference(BluetoothDevice, BluetoothCodecConfig)`

并且可通过 `BluetoothCodecConfig.Builder` 写入：

- `codecType`
- `sampleRate`
- `bitsPerSample`
- `channelMode`
- `codecSpecific1`

这正好覆盖我们想保留的四类能力：

- 协议显示：可行性高。
- LDAC/LHDC 播放质量：可行性中高，需真机确认每个 ROM 是否接受写入。
- 采样率切换：可行性中高，需真机确认组合约束和回读。
- 按耳机记忆选择：可行性高，本质是本地 SharedPreferences + A2DP 连接广播重放。

LE Audio 开关先不纳入非 Root 版目标。它目前更依赖 `com.android.bluetooth` / `com.oplus.wirelesssettings` 的 privileged 路径，非 Root 改包版不应承诺一键切换。

## 关键风险

1. **同包名安装风险**
   - 如果目标机上的 `com.oplus.melody` 真的是用户 App，卸载后安装我们重签版本应该可行。
   - 如果它实际仍是系统预装包，只是允许“卸载更新”或“为当前用户卸载”，同包名重签 APK 可能因为签名不一致而安装失败。
   - 必须先用真机验证，不要凭 UI 上“可卸载”判断。

2. **签名权限损失**
   - Melody manifest 里有不少 OPlus 私有权限，例如 `com.oplus.permission.safe.BLUETOOTH`、`OPLUS_COMPONENT_SAFE`、`IOT` 等。
   - 重签后这些 signature 权限大概率拿不到。
   - 我们的音质控制核心不依赖这些权限，但 Melody 原有部分生态能力、Provider、Receiver、系统设置入口可能降级。

3. **隐藏 API / 蓝牙栈 ROM 差异**
   - `Bluetooth Codec Changer` 已在当前一加 13 上证明此路可行。
   - Melody 是欧加设备专用 APK，目标用户也主要是 OPPO / OnePlus / ColorOS/OxygenOS 设备，因此同代欧加 ROM 上可用概率很高。
   - 仍不把“所有欧加型号必定可用”写死：地区包、Android 大版本、蓝牙 Mainline 模块、厂商蓝牙栈小版本都可能影响隐藏 API 行为。
   - 必须把写入结果以 `ACTION_CODEC_CONFIG_CHANGED` + `getCodecStatus` 回读为准。
   - 如果某 ROM 返回 `BLUETOOTH_PRIVILEGED` / `SecurityException`，非 Root 版只能提示不支持，不能像 LSPosed 版那样走系统进程 bridge。

4. **APK 改包维护成本**
   - Melody 版本更新后类名、Preference key、资源结构可能变。
   - 改包版需要针对 16.6.3、16.7.1 等具体 APK 做基线，不能像 LSPosed 版动态覆盖那么优雅。

5. **AndroGhostInjector 不作为非 Root 方案**
   - 它本身需要 `su`、eBPF、`/proc/<pid>/mem`，适合 root/研究环境，不适合普通用户分发。
   - 本路线不使用它。

## 本地证据

Bluetooth Codec Changer 关键文件：

- `Bluetooth Codec Changer/java_src/com/amrg/bluetooth_codec_converter/data/codec/CodecReflectionKt.java`
  - 反射 `getCodecStatus`
  - 反射 `setCodecConfigPreference`
- `Bluetooth Codec Changer/java_src/com/amrg/bluetooth_codec_converter/data/codec/CodecManager.java`
  - `BluetoothCodecConfig.Builder`
  - `setCodecPriority(1000000)`
  - two-step 写入，先写 `codecSpecific1 = 0`，延迟约 250ms，再写目标值。
- `Bluetooth Codec Changer/java_src/com/amrg/bluetooth_codec_converter/data/codec/CodecUtil.java`
  - LDAC：`1000 / 1001 / 1002 / 1003`
  - LHDC：`0x8000 | index`，Option 1..9
  - 采样率 bit：`1=44100`、`2=48000`、`4=88200`、`8=96000`、`16=176400`、`32=192000`

当前 LSPosed 模块可复用思想但不能原样搬：

- 可复用：
  - `BluetoothCodecReflect` 的读写封装思路。
  - `CodecSnapshot` / `CodecRequest` 数据结构。
  - `CodecLabelTable` 标签表。
  - `CodecController` 的状态和写后回读策略。
  - `PreferenceStore` 的按 MAC 记忆策略。
  - `HostHookInstaller` 的页面扫描和 Preference 反射经验。
- 需要删除或改写：
  - libxposed 入口与 Hook API。
  - `com.android.bluetooth` system bridge。
  - `com.oplus.wirelesssettings` LE Audio bridge。
  - `ServiceManager.addService/getService` 路径。
  - root shell fallback。

## Phase 0：安装可行性预检

- [ ] 在目标手机执行：
  - `adb shell pm path com.oplus.melody`
  - `adb shell dumpsys package com.oplus.melody`
  - 记录它是 `/data/app/...` 还是 `/system` / `/product` / `/vendor` 下的预装包。
- [ ] 测试用户侧完整卸载：
  - 普通卸载是否能彻底移除。
  - 卸载后 `adb shell pm list packages | grep melody` 是否还存在。
- [ ] 准备一个仅重签、未改代码的 Melody APK。
- [ ] 安装仅重签 APK，确认是否出现：
  - `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
  - `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`
  - `INSTALL_FAILED_DUPLICATE_PERMISSION`
  - `INSTALL_FAILED_VERSION_DOWNGRADE`
- [ ] 安装成功后验证原 Melody 基础功能：
  - App 能启动。
  - 耳机详情页能打开。
  - OneSpace 能打开。
  - 电量、降噪、EQ、空间音频等原功能是否正常。
- [ ] 记录重签后实际授予的权限：
  - `adb shell dumpsys package com.oplus.melody | grep granted=true`
  - 重点看 `BLUETOOTH_CONNECT`、`BLUETOOTH_SCAN`、位置权限、OPlus safe 权限。

验收：同包名重签 Melody 能在非 Root 设备上安装并保持主要原功能，否则该路线需要改为“独立 App 版”或“不同包名克隆版”，价值会明显下降。

## Phase 1：无 Root A2DP 直连固化

目标：基于 BCC 在一加 13 已经可用的事实，快速做一个 Melody 侧最小闭环。重点不是证明“能不能无 Root”，而是确认改包版 Melody 的身份、权限和页面设备解析不会破坏 BCC 同款读写路径。

- [ ] 做一个最小测试入口，推荐直接放进改包 Melody helper dex，而不是另做独立 App。
  - 原因：BCC 已经证明独立 App 路线可用；我们真正需要验证的是 Melody 改包环境。
- [ ] 请求运行时权限：
  - `BLUETOOTH_CONNECT`
  - Android 12+ 需要用户显式授权。
- [ ] 获取 A2DP proxy：
  - `BluetoothAdapter.getProfileProxy(context, listener, BluetoothProfile.A2DP)`
- [ ] 获取当前连接设备：
  - 优先 `BluetoothA2dp.getConnectedDevices()`
  - 或复用 Melody 当前页面 MAC。
- [ ] 反射读取：
  - `getCodecStatus(device)`
  - `getCodecConfig()`
  - `getCodecsSelectableCapabilities()`
- [ ] 打印并保存：
  - active codec type
  - active sampleRate
  - active bitsPerSample
  - active channelMode
  - active codecSpecific1..4
  - selectable codec types
  - selectable sample rate masks
  - selectable codecSpecific1 values
- [ ] 反射写入一个低风险测试：
  - 当前 active codec 不变。
  - 只在 selectable 里选择另一个 sampleRate 或 codecSpecific1。
  - 构造 `BluetoothCodecConfig.Builder`。
  - 调用 `setCodecConfigPreference(device, config)`。
- [ ] 监听并回读：
  - `android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED`
  - 3 秒内再次 `getCodecStatus`
- [ ] 判断结果：
  - 成功：进入 Phase 2，直接开始 UI 注入。
  - 抛 `SecurityException` 或含 `BLUETOOTH_PRIVILEGED`：记录为 Melody 改包身份差异；用同设备 BCC 对照排查是否权限、targetSdk、调用时机、设备对象来源不同。
  - 无异常但不生效：优先套用 BCC two-step、250ms 延迟、active 字段保留策略，再判断是否需要组合修正。

验收：在改包 Melody 内能非 Root 写入并回读确认至少一个 LDAC/LHDC quality 或 sampleRate。由于 BCC 已在一加 13 上可用，这一阶段预期应当能跑通；如果跑不通，优先排查我们集成方式，而不是立即否定路线。

## Phase 2：确定改包注入方式

优先路线：不重建 Melody Java 源码，只做 APK 级别注入。

- [ ] 使用 apktool 解包目标 Melody APK。
- [ ] 编译一个独立 helper dex，例如：
  - 包名：`xyz.melodylsp.codec.direct`
  - 输出：`classesN.dex`
  - 不依赖 LSPosed。
  - 不依赖模块 APK `R` 资源。
  - 尽量只用 Android SDK + 反射。
- [ ] 把 helper dex 放入解包后的 Melody APK。
- [ ] 选择最小 smali patch 点：
  - 方案 A：Patch `DetailMainActivity.onResume/onStart`，调用 `DirectMelodyInjector.onActivityResumed(activity)`。
  - 方案 B：Patch `OneSpaceDetailActivity.onResume/onStart`，调用同一个入口。
  - 方案 C：Patch Melody Application `onCreate`，注册 `Application.ActivityLifecycleCallbacks`，运行时扫描页面。
- [ ] 推荐先做方案 A+B：
  - 改动点少。
  - 不需要稳定 Application 类名。
  - 出问题只影响目标页面。
- [ ] 保留方案 C 作为后续兼容增强。
- [ ] 所有入口必须 try/catch，绝不让 Melody 崩溃。

验收：重签 Melody 启动后，logcat 能看到 helper dex 已加载，目标 Activity resume 时执行到我们的入口。

## Phase 3：移植直接读写核心

新建 direct 版核心，避免拖入 LSPosed/system bridge 复杂度。

- [ ] `DirectBluetoothCodecApi`
  - 从当前 `BluetoothCodecReflect` 裁剪。
  - 保留 `getProfileProxy`、`getCodecStatus`、`setCodecConfigPreference`。
  - 删除 system bridge、broadcast bridge、root fallback。
  - 对 `SecurityException` 做明确错误码。
- [ ] `DirectCodecSnapshot`
  - 可直接复用当前字段设计。
  - 不需要 AIDL Parcelable，普通 Java model 即可。
- [ ] `DirectCodecRequest`
  - active codec + sampleRate + bits + channel + specific1..4。
  - 支持 `withSpecific1`、`withSampleRate`。
- [ ] `DirectCodecLabels`
  - 复用 `CodecLabelTable` 映射。
  - 保留未知值 fallback。
- [ ] `DirectPreferenceStore`
  - 使用 Melody 自身 `Context.getSharedPreferences("melody_lsp_codec_prefs", MODE_PRIVATE)`。
  - key 继续按 MAC 隔离。
  - 默认不记忆。
- [ ] `DirectWriteController`
  - 写入顺序：
    1. 单步写入。
    2. 对 `codecSpecific1 != 0` 使用 Bluetooth Codec Changer 同款 two-step：先写 `specific1=0`，延迟 250ms，再写目标。
    3. 回读确认。
  - 不再 fallback 到系统进程。

验收：helper 内部可以读快照、生成可选项、写入并确认，不依赖任何 Xposed 类。

## Phase 4：移植 Melody UI 注入

目标：在改包版 Melody 页面里显示和 LSPosed 版接近的 UI。

- [ ] `DirectMelodyInjector`
  - 暴露静态入口：`onActivityResumed(Activity activity)`。
  - 判断 Activity 类名：
    - `com.oplus.melody.ui.component.detail.DetailMainActivity`
    - `com.oplus.melody.onespace.OneSpaceDetailActivity`
  - 扫描 FragmentManager 找 PreferenceScreen。
- [ ] 页面识别：
  - DetailMain：优先找 `HiQualityAudioItem`，其次 `EqualizerItem`。
  - OneSpace：找 `pref_noise_switch` / `pref_noise_switch_category` / `pref_more_setting` / `footer_preference`。
- [ ] 避免重复注入：
  - 使用 screen identity weak map。
  - 或检查 key：`melody_codec_lsp_header` / `melody_codec_lsp_category`。
- [ ] 创建 Preference：
  - 尽量复用当前 `CodecBlockBuilder` 反射策略。
  - 优先构造 host 里的：
    - `com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory`
    - `com.coui.appcompat.preference.COUIPreference`
    - `com.coui.appcompat.preference.COUISwitchPreference`
  - fallback 到 AndroidX Preference。
- [ ] DetailMain 显示：
  - Header：`蓝牙音质 · <codec>`
  - `编解码器` 行：只读或高级模式入口，初期可只读。
  - `播放质量` 行。
  - `采样率` 行。
  - `记住此耳机的选择` 开关。
- [ ] OneSpace 显示：
  - Header。
  - 播放质量。
  - 采样率。
  - 不显示记忆开关，保持快速面板简洁。
- [ ] 弹窗选择器：
  - 不依赖 `ListPreference`，继续沿用当前模块手写 PopupWindow / Dialog 思路。
  - 选项来自实时 selectable capabilities。
- [ ] 文案直接用字符串常量，不使用模块资源 id。

验收：改包版 Melody 的 DetailMain / OneSpace 中能出现音质区块，原页面项顺序和点击不被破坏。

## Phase 5：能力集合和组合修正

- [ ] 协议显示：
  - 从 `getCodecStatus().getCodecConfig().getCodecType()` 显示。
  - 未知 codec 显示 `Codec(0x...)`。
- [ ] 播放质量：
  - LDAC：
    - `1000`：990 kbps / 音质优先。
    - `1001`：660 kbps / 均衡。
    - `1002`：330 kbps / 连接优先。
    - `1003`：自适应。
  - LHDC：
    - 先兼容 Bluetooth Codec Changer：`0x8000 | index`，Option 1..9。
    - 再保留当前模块已有 label / vendor id 识别逻辑。
- [ ] 采样率：
  - mask 解码：
    - `1` -> 44.1 kHz
    - `2` -> 48 kHz
    - `4` -> 88.2 kHz
    - `8` -> 96 kHz
    - `16` -> 176.4 kHz
    - `32` -> 192 kHz
- [ ] 写入前组合修正：
  - 新 sampleRate 必须在当前 active codec selectable mask 里。
  - quality 切到音质优先时，如果当前 sampleRate 组合不被接受，尝试提升到 selectable 中更合适的 rate。
  - 写入失败时回滚 UI 到回读真值。
- [ ] 写入后确认：
  - 监听 codec changed。
  - 3 秒超时。
  - 回读不一致则 toast：`切换未生效，请重试`。

验收：选项不展示系统未报告的档位；写入失败不会让 UI 停在假状态。

## Phase 6：按耳机记忆选择

- [ ] 记忆开关默认 false。
- [ ] key 设计：
  - `<MAC>_remember`
  - `<MAC>_specific1`
  - `<MAC>_samplerate`
  - `<MAC>_codecType` 可选，仅用于校验。
- [ ] 用户打开记忆时：
  - 立即保存当前回读值。
- [ ] 用户关闭记忆时：
  - 删除该 MAC 的 specific1 / samplerate。
- [ ] 写入成功时：
  - 如果 remember=true，更新快照。
- [ ] 注册 A2DP 连接广播：
  - `android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED`
  - 连接后延迟 1500ms / 3000ms 读取能力。
  - 如果持久值仍在 selectable capabilities 内，自动重放。
  - 如果不在能力集合内，只跳过该字段，不删除用户保存值。
- [ ] 防抖：
  - 同一 MAC 短时间多次连接事件只重放一次。

验收：重连耳机后能自动恢复上次成功设置；关闭记忆后完全透明，不主动写入。

## Phase 7：打包、签名、安装

- [ ] 确定本地工具链：
  - apktool
  - apksigner
  - zipalign
  - Android SDK build-tools
- [ ] 本地生成 debug/test keystore。
- [ ] 解包 Melody。
- [ ] 插入 helper dex。
- [ ] Patch smali 调用点。
- [ ] 重建 APK。
- [ ] zipalign。
- [ ] apksigner 签名。
- [ ] 安装前备份原 APK 和版本信息。
- [ ] 安装测试包。
- [ ] 启动并抓日志：
  - `adb logcat -s MelodyCodecDirect:V MelodyCodecLsp:V`
- [ ] 不上传 APK，不放 release，不同步云端。

验收：本地生成的改包 APK 可安装、启动、注入、读写。

## Phase 8：测试矩阵

设备 / ROM：

- [ ] 当前主力 ColorOS / OnePlus 设备。
- [ ] 至少一个 Android 14 / 15 / 16 版本。
- [ ] Melody 16.6.3。
- [ ] Melody 16.7.1。

耳机 / codec：

- [ ] SBC/AAC 普通耳机：只显示协议，隐藏 quality。
- [ ] LDAC 耳机：330/660/990/自适应。
- [ ] LHDC 耳机：Option 档位。
- [ ] 支持 96k / 192k 的耳机：采样率显示与切换。
- [ ] 不支持 Hi-Res 的耳机：DetailMain fallback 锚点。

场景：

- [ ] 冷启动 Melody。
- [ ] 从系统蓝牙设置跳入 DetailMain。
- [ ] 打开 OneSpace。
- [ ] 蓝牙断开 / 重连。
- [ ] 切换播放质量。
- [ ] 切换采样率。
- [ ] 开启记忆后重连。
- [ ] 关闭记忆后重连。
- [ ] 写入失败时 UI 回滚。
- [ ] Melody 原有降噪、EQ、空间音频、耳机设置项仍可用。

## Phase 9：降级策略

- [ ] 如果 `getCodecStatus` 失败：
  - UI 显示 `暂时无法获取编解码器信息`。
  - 保留重试。
- [ ] 如果读可用但写入抛 `SecurityException`：
  - quality / sampleRate 行置灰。
  - summary 显示 `当前系统不允许非 Root 写入`。
- [ ] 如果写入无异常但回读不一致：
  - toast `切换未生效，请重试`。
  - 回滚 UI。
- [ ] 如果改包后 Melody 原有关键功能损坏：
  - 停止推进同包名改包。
  - 改做独立 App 版，或者只保留本地实验。

## Phase 10：和现有 LSPosed 模块的关系

- [ ] LSPosed 版继续作为完整功能版：
  - 保留 system bridge。
  - 保留 LE Audio 开关。
  - 保留诊断页和反馈包。
- [ ] 非 Root 改包版作为轻量版：
  - 不承诺 LE Audio。
  - 不承诺所有 ROM 可写。
  - 强调“如果系统放行隐藏 A2DP API，即可工作”。
- [ ] 尽量共享纯 Java 逻辑：
  - label 表。
  - sampleRate mask 解码。
  - request/snapshot model。
  - two-step 写入策略。
  - remember store 语义。
- [ ] 不共享：
  - Xposed Hook 安装器。
  - system process bridge。
  - wirelesssettings bridge。
  - root fallback。

## 第一轮最小闭环

第一轮不要追求 UI 完整，先做最小可验证闭环：

1. 同包名重签 Melody 可安装。
2. Patch `DetailMainActivity.onResume` 调到 helper。
3. helper 能输出当前 active codec 到 logcat。
4. helper 能插入一个只读 `蓝牙音质 · <codec>` Preference。
5. helper 能弹一个简单列表切 LDAC quality。
6. 写入后 3 秒内回读确认。
7. 成功后再补采样率、LHDC、记忆开关、OneSpace。

考虑到 BCC 已经在一加 13 上跑通，第一轮最小闭环可以直接开做；这不是高风险探索，而是把已验证能力搬进 Melody。只要第 1 步“同包名重签 Melody 可安装”成立，后续 2-7 步就很值得投入。
