# AAC/SBC 耳机无官方游戏入口的本地适配（2026-10-15）

## 反馈与范围

- 反馈设备：OnePlus PJZ110，Android 16，PJZ110_16.0.3.501(CN01)，蓝牙 15.0.0，模块 2.5.1 (29)。
- 反馈包：`OPlusHeadsetAudioHelper-feedback-20261015-145531(3).zip`、`OPlusHeadsetAudioHelper-feedback-20261015-154124(3).zip`。
- 用户确认该耳机官方没有「游戏模式」入口。原来的模块游戏开关依赖这一行作为注入锚点，因此没有可操作的总开关。
- 日志确认 AAC 被记住，但系统通过单元素 SBC `codecSpecific2=3` 请求进入低延迟；两个私有低延迟类持续查找失败。日志没有提供用户 ROM 的 APK/扩展 JAR，不能确认是类名还是加载器差异。

## 修改

1. 官方入口不存在时，将模块「游戏低延迟」总开关放到已有音质区尾部；「游戏自动切换 LE Audio」仍需能力确认才显示。官方行迟到时重新绑定其启用状态，不重复注入。
2. 在已有的 `A2dpNativeInterface.setCodecConfigPreference` Hook 中增加兼容门控：仅当模块总开关关闭、方法返回 boolean、请求为单元素 SBC 低延迟入场时跳过原调用并返回 false。该拒绝路径不发送游戏活动提示。
3. SBC `s2=2/3` 判为入场；`s2=5` 为退出，不再延长 AAC 记忆回放的游戏抑制。普通 SBC `s2=0`、AAC、其它 codec 和混合配置数组均放行。

标记和失败返回的静态依据是本地 C16/C17 扩展中的 `A2dpLowLatencyCodec.getLowLatencyCodecConfig()`、`getNormalCodecConfig()`，以及 `BluetoothLowLatencyStateMachine` 对 `setCodecConfig()` 返回值的检查。用户反馈中的 `s2=3` 请求经过了本次门控的同一 Java 下发点；用户 ROM 的实际效果仍需复测。

## 本地结果

- `GameModeCodecPolicyTest` 4 项、`GameLeaControllerTest` 8 项通过，零失败、零错误。
- `:app:assembleRelease` 通过；使用项目现有英文路径入口 `E:\melody-lsp-link` 构建，避开中文路径下的 CMake/测试类加载异常。
- 本地测试包使用调试签名，APK v3 签名验证通过。版本保持 2.5.1 (29)，不是正式发布包。
- 测试包来自当前工作区，包含工作区原有的未提交修改；本次只对上述适配文件作增量修改。
- APK：`E:\我开发的模块\无线耳机-音频质量开关\.android-build\test-apks\OPlusHeadsetAudioHelper-2.5.1-aac-game-compat-test1.apk`
- SHA-256：`C98214AA63515129B02250E18908F729D5C9757FD766F5BFE364B5413D0E5AAB`
- 构建日志：`E:\我开发的模块\无线耳机-音频质量开关\.c17_audit\aac_game_latency_compat_build_ascii.log`

## 用户设备复测

1. 安装测试包后重新加载模块的宿主和蓝牙作用域。打开该耳机的官方面板，确认模块音质区出现「游戏低延迟」。
2. 先退出游戏并恢复 AAC；如已经处于低延迟 SBC，可先重新连接耳机，再选择 AAC、开启记忆，并关闭模块「游戏低延迟」。
3. 进入游戏，检查实际协议持续为 AAC、声音正常从耳机输出；退出游戏后复核一次。
4. 反馈日志应有 `game.ll.state.sent/recv lowLatency=false`。命中旧栈兼容门控时应出现 `game.ll.suppress reason=master_off_native_entry`，被拦截的请求不应再出现对应的 native `.done` 记录。
5. 如系统发送 `s2=5` 退出请求，应允许下发；后续正常快照不应再因这个标记持续出现 `game.mode.probe.active`。

兼容门控负责阻止后续低延迟入场。用户 ROM 私有服务 Hook 尚未恢复，不能据此承诺关闭开关瞬间就能使已经进入低延迟的连接立即恢复 AAC；首次复测应按第 2 步建立正常 AAC 基线。

## 17:04 反馈与重试修补（test2）

`OPlusHeadsetAudioHelper-feedback-20261015-170409.zip` 确认：17:03:06 独立开关已注入；17:03:31.843 命中 `master_off_native_entry`；17:03:32.041 音频以 AAC（`codec_index=1`）启动。这次捕获验证了 SBC 入场拦截和 AAC 播放启动。私有低延迟类仍不能加载，工厂每次返回都触发重试，造成 48 次安装尝试及大量重复堆栈。

test2 修补：

- 生命周期只安排一次初始安装与启动重试；相同工厂实现及加载器的重复返回不再触发安装，也不重置问题去重状态。普通工厂兜底对象不再被误报为 DEX 就绪。
- 优先使用工厂实际返回实现的加载器，并保留入口加载器回退；已有工厂单例也会被捕获。
- 安装期间收到新的就绪信号时，合并为一次后续尝试。启动重试结束后，新的实现或加载器仍可触发安装。
- 私有类缺失按扩展状态记录一次简短信息；其它安装异常保留一次堆栈。启动重试用尽且 A2DP 兼容 Hook 可用时，记录 `game.ll.hook.state mode=classic_sbc_compat`；私有 Hook 完整安装后记录 `mode=private_hooks_ready`。

本地验证：`GameLatencyHookStateTest` 8 项、`GameModeCodecPolicyTest` 4 项、`GameLeaControllerTest` 8 项，共 20 项通过，零失败、零错误；离线 Release 构建、补丁空白检查和 APK v3 签名验证通过。新包仍需在反馈设备上确认重复日志已收敛及 AAC 保持正常。

- 调试签名测试包：`E:\我开发的模块\无线耳机-音频质量开关\.android-build\test-apks\OPlusHeadsetAudioHelper-2.5.1-aac-game-compat-test2.apk`
- SHA-256：`74A207F4189D4BABC69873417380A3A90391A2018DF2598A783838CBD82FCAA5`
- 构建日志：`E:\我开发的模块\无线耳机-音频质量开关\.c17_audit\aac_game_latency_retry_fix_build.log`
