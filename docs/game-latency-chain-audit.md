# 游戏低延迟与自动切换 LE Audio 链路审查（2026-10-14）

审查工作区：`E:\我开发的模块\无线耳机-音频质量开关`。主工程为 `module/`；反编译目录用于静态对照。此次只读设备采集，没有自动操作游戏、设置或重启作用域。下列修补需新包真机验收。

## 用户反馈与证据

| 现象 | 静态确认 | 修补与验证边界 |
| --- | --- | --- |
| 退游戏回到 LHDC，系统设备页 LE Audio 仍开 | 无线设置设备页 `C2/U.y()` 经 `C2/H.test()` 查询 `LeAudioProfile.isEnabled()`，按组内任意成员的 policy 汇总；旧 bridge 只改传入 MAC | 使用官方 BR/LE 映射及 LEA group 找出成员，再读写 profile policy。成员遗漏能解释现象；本次现场残留成员及 C17 反射成功仍需日志确认。没有证据支持修改 `le_aduio_status` 来修这个开关 |
| 进游戏切 LC3 后偶发外放 | BR/LE 切换会经历断开与重连；旧 A2DP 延迟恢复没有代次校验，会与后续 LEA 切换竞态 | 串行化写入，以请求代次取消旧 A2DP 任务；LEA 已开或状态未知时禁止旧任务重新接管。确认连接并在 15 秒未连接时恢复原策略。无法静态保证游戏不会缓存外放路由，开关说明已提示可能短暂断音或外放 |
| 切后台马上退 LEA | 旧 controller 收到 `onGameModeChanged(false)` 就恢复；官方 util 默认后台 30 秒、进程结束 10 秒 | 复用官方配置时长，由模块记录离开游戏时刻独立计时；不再使用依赖内部进程/SCO 状态的剩余计时 getter。仅主 PID 与 UID 同时匹配的死亡通知可缩短为 10 秒；回前台取消旧任务，重复状态查询不延长计时 |

系统页面静态证据：

- `E:\我开发的模块\无线耳机-音频质量开关\无线设置_16.12.6\java_src\C2\U.java:1399`
- `E:\我开发的模块\无线耳机-音频质量开关\无线设置_16.12.6\java_src\C2\H.java:37`
- `E:\我开发的模块\无线耳机-音频质量开关\无线设置_16.12.6\java_src\com\android\settingslib\bluetooth\LeAudioProfile.java:257`
- `E:\我开发的模块\无线耳机-音频质量开关\无线设置_16.12.6\smali_classes2\C2\U.smali:1610`（开关赋值）
- `E:\我开发的模块\无线耳机-音频质量开关\.c17_audit\bt_ext_src\sources\com\oplus\bluetooth\feature\lowlatency\BluetoothA2dpLowLatencyUtil.java:48`（退出计时）

## 完整执行路径

1. Melody 主面板「游戏功能」组：游戏低延迟总开关、官方游戏模式、游戏自动切换 LE Audio。总开关默认开，自动切换默认关；总开关关时下两项置灰。
2. `PreferenceStore` 保存两个模块开关。宿主定向广播给蓝牙；查询回执确认实际镜像，丢包重试。蓝牙进程重建后请求宿主重新同步。
3. 蓝牙扩展 jar 晚加载后安装 `onGameModeChanged` 和 `selectBestLowLatencyCodec`，并回读已有 service 游戏状态；controller 未就绪时，总开关仍可从蓝牙镜像阻止低延迟入场。
4. 总开关关闭：拦截经典和 LEA 的新低延迟选择；已进入经典低延迟时请求官方退出；用户原本正常的 LE Audio 不属于新低延迟选择。
5. 自动切换开启：记录游戏包与耳机身份，查询官方当前单槽及 service 缓存中的该游戏决定。官方为关时不启用 LEA；运行中官方关闭会恢复模块临时启用的 LEA。
6. 确认 LE/DUAL 类型及 ASCS/HAS UUID 支持；权威 policy 不可读时跳过。只有原本关闭且被模块临时开启的 LEA 才归模块恢复；原本手动开启的 LEA 在退出游戏时保留。
7. 临时开启前提交恢复记录，启动超时恢复原策略。自动会话期间阻止经典 SBC 回退；失败后保持原经典协议，直到会话结束。游戏退出采用上述官方计时。
8. 游戏、手动开关和重连操作共用写锁；等待锁后再检查当前会话。手动模块 LEA 请求按官方 BR 地址归一化，并取消自动会话的恢复责任。
9. 恢复整组 LEA policy 后，带代次校验的 A2DP 任务恢复经典链路。游戏切换也发送 LEA 状态回执，宿主按实际连接更新。

## C16/C17 边界

两份蓝牙扩展的官方低延迟开关通道、核心 hook 名称和计时已做静态对照。支持能力与组成员解析采用反射，缺少 API/UUID/policy 时保守跳过。C16 尚未做这版真机验收；C17 构建成功也不能替代系统设备页、游戏路由和切后台听感验收。

## 手动验收

1. 从经典 LHDC 开始，开启两个模块开关，进入游戏，确认 LC3 及耳机声音。
2. 切后台约 5 秒再返回，应保持 LEA，不能被旧退出任务关闭；离开较长时间后应恢复经典链路。
3. 恢复后重进系统蓝牙设备页，LE Audio 应关闭；再进游戏时旧 A2DP 重连任务不能抢回经典链路。
4. 游戏助手关闭该游戏低延迟，模块应跳过自动切换；重新打开可恢复自动切换。
5. 总开关关时入场保持经典协议；用户原本手动开 LEA 时游戏退出应保留该状态。

## 本地验证结果

- `:app:assembleDebug` 通过。
- `:app:testDebugUnitTest` 针对 `GameLeaControllerTest`（6 项）和 `BluetoothLeAudioBridgeTest`（8 项）共 14 项通过，零失败、零错误。覆盖旧退出取消、人工选择优先、官方单槽与缓存优先级、退出计时确认/不延长/进程死亡缩短、旧 A2DP 恢复取消、组成员状态汇总、未知 policy 与能力判据。
- `git diff --check` 通过。构建日志：`E:\我开发的模块\无线耳机-音频质量开关\.c17_audit\game_ll_review_build.log`。
- 调试包：`E:\我开发的模块\无线耳机-音频质量开关\module\app\build\outputs\apk\debug\app-debug.apk`；后台计时修补后的最新 SHA-256：`22C1829F1E35F7545083BB3D3939F863AE372E17381896858984149810BC9C07`。
- 构建完成时 ADB 曾断开；2026-10-14 设备 `f05be72a`（PJZ110）重新连接后，已通过 `adb install -r` 安装上述同 SHA-256 调试包，返回 `Success`，包版本 2.4.1 / versionCode 27。作用域由用户手动重启；上述设备验收尚未执行。
- 新包复测日志：`E:\我开发的模块\无线耳机-音频质量开关\.c17_audit\game_ll_review_20261014_100803.log`（已开始采集游戏、蓝牙及路由相关日志）。

## 首次作用域重启后的主面板检查

- 2026-10-14 12:09–12:10：宿主 17.6.3 与新蓝牙进程 12418 的两个开关回执已确认一致，用户将自动切换开启后两端均为 true。耳机为经典 LHDC V5 / 192 kHz / 24 bit，LE Audio 未激活，游戏态为 false。
- 游戏 Hook 仅出现两次早期 `ClassNotFoundException`，此后 logcat 与 LSPosed 日志均没有成功、重试或结束记录；但 dumpsys 已有官方 `BluetoothLowLatencyService`，进程 maps 也有扩展 jar。故本轮只确认配置桥正常，不能判定游戏 Hook 已启用。
- 静态确认重试 attempt 原来受 installer 的 `synchronized(this)` 保护，入口日志在获取锁之后；后台线程中断会静默结束，排队标记不会复位。线程身份与 Java 等待栈未确认，不能把本次直接定性为死锁。
- 补充修补：拦截主 APK 的 `OplusBTFactory.getInstance()` 正常返回，以 factory 实际接收 `addDexPath` 的 loader 安装功能 Hook；所有 attempt 改由单个 scheduled worker 执行，生命周期回调只排队，不持 installer 大锁安装。保留启动重试，并以短线程名 `MC-game-hook` 和直接 logcat 的进入/退出标记观察工作线程。
- 最新补充包构建及同一组 14 项测试通过，日志 `E:\我开发的模块\无线耳机-音频质量开关\.c17_audit\game_ll_loader_build.log`；需再次由用户重启蓝牙作用域确认成功加载，尚未进入游戏验收。

## 补充包作用域加载确认

- 2026-10-14 12:29:37，用户再次重启蓝牙作用域，进程变为 13195。工厂返回后记录 `game.ll.dex_ready`，串行 worker 随即安装 `onGameModeChanged`、service `getInstance`、`onProcessDied` 和 `selectBestLowLatencyCodec`，12:29:37.939 记录 `game.ll.worker.exit complete=true`。
- 此后宿主与蓝牙两个游戏开关为 true，并收到 `game.ll.state.confirmed`。这次可确认游戏 Hook 安装与配置同步通过；进游戏 LC3 切换、退出缓冲、系统开关刷新及声音路由仍待用户手动验收。

## 首次进游戏验收

- 2026-10-14 12:33:41，用户启动 `com.levelinfinite.sgameGlobal`；记录 `game.ll.mode game=true`、`game.ll.suppress reason=auto_lea_session mode=3`，经典 SBC 低延迟入场被拦。之后记录 `game.ll.auto.connected`。
- 当前 dumpsys：`mGameMode=true`、`mUserPreferLLMode=true`、`mA2dpActiveDevice=null`、`mLeAudioActiveDevice=…E5:50`、`mLeAudioConnectedDeviceNum=2`、`mAudioDeviceRoute=536870912`（BLE headset）；经典 A2DP state machine 已断开，LE Audio 两个 state machine 已连接。不能把经典 state machine 残留的 LHDC codec 配置当作当前路由。
- 用户反馈「声音正常在耳机，没有外放或明显断音」。本次自动进入 LE Audio 与听感验收通过；一次正常切换不足以排除概率外放，后续仍需验证切后台/恢复及重复切换。

## 短暂切后台测试与计时修补

- 用户反馈「好像切协议了」。日志确认：12:39:35.604 游戏态变 false，12:39:35.648 模块记录退出等待仅 2,000 ms；12:39:37.683 禁用 LEA policy，随后恢复 A2DP。12:39:46.321 返回游戏，又再次启用 LEA。因此短后台缓冲验收失败，不能标为已通过。
- 原实现把官方 `getLowlatencyExitDelay()` 的低值收敛到 2 秒。该 getter 依赖内部 `mIsForegroud`、`mIsProcessDied`、时间戳及 `mWatingTime`，不能作为模块的独立退出缓冲；现场三个 `persist.bluetooth.ll_exit_timeout.*` 属性均为空，后台配置默认仍为 30 秒。
- 另静态确认官方 `onGameProcessDied` 按 PID 或 UID 匹配，同 UID 子进程结束可能被记为游戏结束。模块死亡通知改为主 PID 与 UID 同时匹配，并由模块记录死亡时刻，避免沿用该内部标志缩短缓冲。
- 修补后读取官方静态配置时长，以模块记录的离开游戏时刻计时；后台默认 30 秒，确认主进程死亡默认 10 秒，返回游戏使旧计时失效。新增两项独立计时回归测试，相关测试共 16 项通过，Debug 构建通过；构建日志 `.c17_audit/game_ll_grace_build.log`。修补包已覆盖安装，待用户重启蓝牙作用域及复测。

## 独立后台计时真机复测

- 用户重启后的蓝牙进程为 28860，游戏 Hook 安装完成且两个开关同步为 true。
- 13:03:18.376 离开游戏，13:03:18.447 记录 `game.ll.exit.schedule delayMs=29950 source=background_grace officialPolicy=true`；13:03:26.229 返回游戏。后台游戏信号持续约 7.85 秒。
- 返回后保持前台，日志至少覆盖至 13:04:15，已经超过原退出期限；没有禁用 LEA、恢复 A2DP 或重新开启 LEA 的记录。现场仍为 BLE headset 路由，A2DP inactive、LEA active、两个 LEA state machine 连接。
- 用户反馈「这次正常了」。短暂切后台保持 LEA、返回后取消旧退出任务的验收通过。下一步确认长时间离开后的 LHDC 恢复及系统设备页 LE Audio 开关状态；游戏低延迟总闸与官方单游戏关闭也需按新包逐项验证。

## 长时间离开与系统设备页验收

- 13:08:36.906 游戏态变 false，退出缓冲为 29,941 ms；13:09:06.941 禁用 LEA policy，约 30.04 秒后开始恢复。
- 13:09:08 的组状态回执为 `supported=true enabled=false connected=false`；随后 A2DP 重连，13:09:11.597 已连接。现场协议为 LHDC V5 / 192 kHz / 24 bit，A2DP active、LEA inactive、LEA 连接数为 0。
- 用户反馈「约40s后正常切回LHDC，切设备页正常显示LE Audio关闭」。当前前台组件为 `com.oplus.wirelesssettings/.SubSettingsExt`，因此这张系统设备页确由无线设置承载。
- 本轮三个重点现象的对应路径已通过：进入游戏自动使用 LEA 且用户听音正常；短后台返回保持 LEA；长时间离开恢复 LHDC，系统设备页 LE Audio 关闭。概率外放只能记录本轮未复现，不能据此保证所有游戏/机型永不出现。

## 低延迟总闸关闭验收

- 13:15:22 用户关闭模块总闸，宿主发送 `lowLatency=false autoLea=true`，蓝牙接收并回读确认。13:15:28 进入游戏，记录 `game.ll.suppress reason=master_off_entry`。
- 官方该游戏开关仍为 1，`mGameMode=true`、`mUserPreferLLMode=true`；实际仍是 LHDC V5 / 192 kHz / 24 bit，`codecSpecific2=0`，A2DP active、LEA inactive、LEA 连接数为 0。证明即使官方允许低延迟且模块自动 LEA 偏好保留为开，总闸关闭仍能保持经典 LHDC。
- 用户反馈「应该还是LHDC」，与实际路由相符；并确认「游戏模式」与「游戏自动切换 LE Audio」两项均已置灰。总闸协议行为与置灰表现验收通过。官方单游戏关闭、模块总闸开启时的保护路径尚待这版实际 UI 测试。

## 官方单游戏关闭优先级验收

- 用户将游戏助手的该游戏低延迟关闭，再开启模块总闸，自动 LEA 保持开启。13:26:01 与 13:26:16 进入游戏时均记录 `game.ll.auto.skip reason=official_off`，未强行开启 LEA。
- 现场官方键为 `com.levelinfinite.sgameGlobal:0`，模块两个开关均为 true；游戏前台，`mGameMode=true`、`mUserPreferLLMode=false`。实际保持 LHDC V5 / 192 kHz / 24 bit，A2DP active、LEA inactive、LEA 连接数为 0。
- 用户确认「确定保持LHDC」。官方关闭优先级验收通过。本机 ColorOS 17 / Enco X3 的本轮重点场景已验证；C16 仍仅静态核对，未真机验收。
- 测试结束时模块两个开关已恢复为开启，游戏助手该游戏的官方开关暂为关闭；需用户手动改回开启以恢复测试前状态。

## 本轮结论与收尾

本机 ColorOS 17 / Enco X3 的本轮重点场景验收通过：自动进入 LE Audio、本轮听音正常、短暂切后台保持 LEA并取消旧恢复任务、长时间离开恢复 LHDC及系统开关关闭、总闸关闭保持 LHDC并置灰两行、官方单游戏关闭优先。C16 只完成静态核对，未做真机验收；本轮未复现外放不代表可以保证所有场景完全无外放，现有提示保留。

调试日志采集已收尾，日志文件保留。最后确认的开关状态为模块总闸开、自动 LEA 开、该游戏官方低延迟关；用户需把官方开关改回开，才能恢复本轮测试前的自动切换条件。

## 能力绑定显示修补（2026-10-14 晚）

- 事实确认：原实现中「游戏自动切换 LE Audio」只要页面存在官方「游戏模式」锚点就会注入显示，与耳机 LE Audio 能力无关；能力只在运行时拦截（`auto.skip lea_not_supported`）。
- 按用户要求绑定：新增 `CodecController.applyGameAutoLeaVisibility()`，以主 LE Audio 开关相同的判据（`LeAudioManager.isSupported`）在耳机不支持时隐藏该行；插入点在 `wireGameRows`、延迟重排、`refreshGameRows` 与 `onLeAudioStateChanged`，能力状态变化时自动刷新。总闸「游戏低延迟」不绑定能力（它只保留经典协议，对所有耳机有效）。
- 构建与同一组测试通过，最新调试包 SHA-256：`6D6D7C1880EDFACE4C8EB039481AEC77A102D6CEA81E51F8FF03984F123CCC95`。构建日志 `.c17_audit/game_ll_capability_build.log`，`git diff --check` 通过，包已覆盖安装。
- 待验证：重启「无线耳机」作用域后，Enco X3 页面应仍显示该行（回归检查）；如接入不支持 LE Audio 的耳机，该行应隐藏。

## X4 类（官方共存）耳机屏蔽自动切换（2026-10-14 深夜）

- 判据链（反编译确认）：SmartAudio 远程特性回调 `(16384 & remoteFeature) != 0`（bit 0x4000）→ `OplusDualModeManagerImpl.notifyDualModeSupport` 写蓝牙进程偏好 `dual_mode_support`（键 = 耳机地址；不支持时移除键）；`isSupportDualConnection` 读取同源偏好，为官方「游戏时自动开 LE Audio」链路的前置。设备上该文件当前为空表（X3 上报不含 0x4000）。
- 实现：`BluetoothLeAudioBridge.readLeaCoexistSupportedForGame` 读取该偏好（请求地址、官方 BR 地址与 LEA 组成员任一为 true 即命中；读取失败返回 null）；`ACTION_LE_AUDIO_STATE` 回执新增 `lea_coexist` 字段；宿主 `LeAudioManager.isOfficialCoexist` 记录；`CodecController.applyGameAutoLeaVisibility` 仅在「支持 LE Audio 且非官方共存」时显示自动切换行；`GameLeaController.startAutoLea` 命中官方共存时直接让位（`game.ll.auto.skip reason=official_coexist`，不建会话、不压制经典回退）。
- 待验证：X4 真机（宿主隐藏该行、官方链路自行接管）；X3 回归（该行仍显示，日志无 coexist 命中）。
