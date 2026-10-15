# 欧加耳机音质助手

<p align="center">
  <img src="banner.png" alt="欧加耳机音质助手：让官方耳机面板更完整" width="100%">
</p>

把原本藏在系统蓝牙设置里的音质选项，直接加到 OPPO / OnePlus 官方「无线耳机」面板中。

它不是一个新的耳机管理 App。安装并启用后，仍然打开你平时使用的官方「无线耳机」页面，就能看到新增的控制项。

## 能做什么

- 查看当前蓝牙音频协议，例如 AAC、LDAC、LHDC、LC3。
- 直接切换播放质量，不用反复进入开发者选项。
- 切换 44.1 / 48 / 96 / 192 kHz 等耳机实际支持的采样率。
- 在支持的手机和耳机上开关 LE Audio。
- 按耳机记住你的协议、播放质量和采样率，重新连接后自动恢复。
- 在官方耳机主面板和 OneSpace 快捷面板中使用同一套控制。
- 出现问题时可一键生成反馈包，方便适配不同机型和系统版本。

## 2.5.3 更新 / Updates

- 无官方「游戏模式」入口的耳机也会显示模块「游戏低延迟」总开关；关闭后阻止 SBC 低延迟入场，帮助保持已选择的 AAC。
- 修正退出标记对记忆回放的影响，收敛缺类重复重试与堆栈日志；扩展晚加载后仍可继续安装。
- 包含 2.5.2 的 X4 共存状态修复：按实际媒体路由显示经典协议或 LC3，经典播放时保留音质控制，避免误显示或一直连接中。
- 已经处于低延迟 SBC 的旧系统，关闭开关后请先退游戏或重连并恢复 AAC。升级后重新加载宿主与蓝牙作用域。

- Headsets without an official Game Mode entry now receive the module's Game Low Latency master switch. Disabling it blocks SBC low-latency entry requests and helps retain the selected AAC codec.
- Corrects exit-marker handling for memory replay and prevents duplicate missing-class retries and stack-trace floods, while allowing late extension installation.
- Includes the 2.5.2 X4 coexistence fixes: media routes determine classic-codec versus LC3 display, and classic playback keeps its audio-quality controls.
- If an older system is already in SBC low latency, leave the game or reconnect and restore AAC after disabling the switch. Reload the host and Bluetooth scopes after upgrading.

[完整中英文说明 / Full bilingual release notes](https://github.com/Andrea-lyz/MelodyCodecTweaker/blob/main/docs/releases/2.5.3.md)

## 2.5.1 更新

- X4 类耳机（支持「LE Audio + 经典共存」，即官方 LE Audio 版本）上隐藏「游戏自动切换 LE Audio」开关，运行期交由官方链路接管，避免双重控制。

## 2.5.0 更新

- 新增「游戏低延迟」总开关（默认开启）：关闭后进任意游戏保持当前协议（LHDC 不切 SBC），并置灰官方「游戏模式」与「游戏自动切换 LE Audio」。
- 新增「游戏自动切换 LE Audio」开关（默认关闭，仅对支持 LE Audio 的耳机显示）：进游戏自动切 LC3 低延迟，离开游戏按系统计时恢复；短暂切后台返回不打断；不会关闭你手动开启的 LE Audio。
- 修复退游戏后系统蓝牙设备页 LE Audio 开关残留、切后台立即退出 LE Audio、游戏内切换偶发外放或断音等问题。
- 适配 ColorOS 17（无线耳机 17.6.3）的弹窗类名变更，恢复二级菜单与选择弹窗，并修复激活诊断显示。

## 三种 LHDC 播放质量怎么选

| 选项 | 适合谁 | 实际行为 |
| --- | --- | --- |
| 自适应 | 大多数用户 | 使用系统原本的自适应算法，优先保证连续播放，并尽可能提高音质。 |
| 连接优先 | 经常卡顿、距离较远 | 使用约 500 / 560 kbps 的中档码率，连接更稳。 |
| 音质优先 | 想追求最好音质 | 按耳机能力固定最高码率（1000 或 900 kbps）。 |

「音质优先」会按耳机能力选择最高档位（部分耳机上限是 900 kbps）。如果你希望遇到拥堵时自动保护而不是死守最高档，可以到模块诊断页的「链路」页开启实验性「码率拥塞治理器」。

## 看到这些提示是什么意思

以下提示只会在系统版本 16.0.7 及以上（补丁适用版本线）出现；低于 16.0.7 的系统 LHDC V5 为原生支持、无需补丁，不会出现这些提示，诊断页两个补丁状态会显示绿勾。

- **「未适配，请联系开发者反馈」**：当前手机系统版本还没适配码率补丁，切换「音质优先」可能不会生效。这**不是故障**，把系统版本和反馈包发给我们即可。
- **「未完整适配，强行使用可能出现异常卡顿」**：码率补丁已生效，但快切补丁缺失，切换档位时可能出现卡顿。可以先继续用，同时把反馈发给我们。

## 使用条件

- 手机已 Root，并安装支持 **libxposed API 101** 的 LSPosed / LSP 管理器。
- OPPO / OnePlus / ColorOS 系统，且带有官方「无线耳机」App（包名 `com.oplus.melody`）。
- Android 12 及以上。

本模块只能调用手机和耳机本来就支持的能力，不能让不支持 LHDC、192 kHz 或 LE Audio 的耳机凭空获得这些功能。不同系统版本、无线耳机 App 版本和蓝牙库可能存在差异，未验证组合不保证全部功能可用。

## 安装方法

1. 从 Release 下载并安装最新 APK。
2. 在 LSPosed / LSP 管理器中启用「欧加耳机音质助手」。
3. 保留模块推荐的四个作用域，不要自行减少：
   - `com.oplus.melody`
   - `com.android.bluetooth`
   - `com.oplus.wirelesssettings`
   - `com.android.settings`
4. 重启手机。
5. 连接耳机，打开官方「无线耳机」App 查看新增选项。

[下载最新版](https://github.com/Xposed-Modules-Repo/xyz.melodylsp.codec/releases/latest) · [查看源码与技术说明](https://github.com/Andrea-lyz/MelodyCodecTweaker) · [反馈问题](https://github.com/Andrea-lyz/MelodyCodecTweaker/issues)

## 出现问题怎么办

先确认模块已经启用、四个推荐作用域都已勾选，并且重启过手机。如果仍有问题：

1. 打开桌面上的「欧加耳机音质助手」。
2. 点击「开始记录问题」。
3. 回到官方耳机面板，把问题复现一次。
4. 再回到模块页面，点击「生成反馈包」。
5. 提交 Issue 时附上反馈包、手机型号、系统版本、耳机型号和问题截图。

没有给模块 Root 授权也能生成反馈包，但蓝牙底层日志会少很多。诊断页里的「尚未采集」只代表当前记录里还没有对应事件，**不代表模块没有生效**。

## 支持作者

如果这个模块对你有帮助，可以扫码请我喝杯咖啡。感谢支持，也欢迎通过反馈一起完善更多设备适配。

<p align="center">
  <img src="PY_QR.png" alt="微信 / 支付宝捐赠二维码" width="520">
</p>
