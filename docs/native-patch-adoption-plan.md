# LHDC V5 Native 补丁适配计划（码率 branch + 快切等价补丁）

> 状态：2026-08-09 审查完成，等价补丁 4 构建待实施。
> 相关代码：`app/src/main/java/xyz/melodylsp/codec/system/NativeLhdcMemoryPatch.java`；
> 样本与脚本：`native_research/`；决策记录：`fix-plan-20260805-BQR-protective-downgrade.md`。

## 1. 起因

ColorOS 16 重构了蓝牙栈 `libbluetooth_jni.so` 的 LHDC V5 相关逻辑，引入两个独立问题：

**问题 A：固定码率被忽略（码率 branch 补丁）**

「音质优先」写入 1000 / 900 kbps 目标码率时，新版蓝牙栈忽略固定 Target_Cap 请求，
播放始终停留在厂商 ABR 决定的码率。表现为切换「音质优先」后实际码率不跟随。

**问题 B：codec equality 漏掉 LHDC V5（快切等价补丁）**

新版 `A2DP_CodecEquals` 重构时遗漏了 LHDC V3/V5 vendor equality 分发，切换音质档位时
明确报错：

```
A2DP_CodecEquals: unsupported codec id 0x4c35053aff
```

`0x4c35053aff` 即 LHDC V5。即使当前与目标配置只有质量位差异
（`0x8009 自适应 → 0x8008 固定 1000`，采样率 / 位深 / 声道 / codecSpecific3 全同），
仍被判定 `restart_output=true`，触发 `BTA_AvReconfig` 完整重建输出——表现为
「环境支持最高码率播放，却十分卡顿」（重建链路后的启动拥塞）。

旧版（一加 11）保留完整链路 `A2DP_CodecEquals → A2DP_VendorCodecEquals →
A2DP_VendorCodecEqualsLhdcV5`，忽略纯音质码率字段差异，得到 `restart_output=false`
——保留已稳定的 AVDTP 链路，只重置编码器并调整码率，切换平滑。

## 2. 补丁机制

两个补丁都作用于进程内已映射的 `libbluetooth_jni.so`（扫描映射字节 → 临时改写页面属性 →
写分支/代码块 → 验证 → 恢复保护），但性质不同：

| | 码率 branch 补丁 | 快切等价补丁 |
| ---- | ---- | ---- |
| 修复问题 | A（固定码率被忽略） | B（快切重建输出卡顿） |
| 机制 | 4 字节 branch 改写 | 整个 default 代码块替换 |
| 匹配方式 | 精确字节序列（21 字节 pattern） | 精确 whole-block 签名 |
| 语义扫描兜底 | 已有（ARM64 语义扫描） | 无（精确匹配，重编译即不支持） |

## 3. 审查过程（2026-08-09）

### 3.1 样本清单与合并

`native_research/` 共 7 个样本，SHA-256 合并后为 **5 个独立构建**：

| 独立构建 | 样本 | 说明 |
| ---- | ---- | ---- |
| OP15/Ace6T | OnePlus 15 `libbluetooth_jni_op15.so` = Ace 6T C16.0.7.201（同 hash） | |
| PJZ110_1608301/PLK110 | PJZ110 16.0.8.301 = PLK110 16.0.8.301（同 hash） | Buds Ace 3 同栈 |
| PJZ110_1609401 | PJZ110 16.0.9.401 | 当前主验证机型 |
| PLC110 | PLC110 16.0.8.300(CN01B90P01) | |
| RMX6688 | realme RMX6688 | MTK 平台 |

### 3.2 码率 branch 补丁扫描

对 5 个构建逐一扫描现有 5 个 `PatternSpec` 原样字节，结果**全部唯一命中**：

| 构建 | 命中 pattern | 偏移 |
| ---- | ---- | ---- |
| OP15/Ace6T | branch_plus_23_op15 | 0x720f3c |
| PJZ110_1608301/PLK110 | branch_plus_69 | 0x7242c8 |
| PJZ110_1609401 | branch_plus_68_pjz110_1609401 | 0x7245d0 |
| PLC110 | branch_plus_73_plc110 | 0x713758 |
| RMX6688 | branch_plus_27_rmx6688 | 0x70ba9c |

### 3.3 快切等价补丁审查

以 PJZ110_1609401 已适配的补丁为模板，提取语义指纹（`mov x9,#0x3aff; movk x9,#0x3505,lsl#16;
movk x9,#0x4c,lsl#32; cmp x8,x9`，即构造 LHDC V5 id 比较），并反汇编各构建的
`A2DP_CodecEquals` 分发链（`cmp 0x100e0ff / 0x2400d7ff / 0xaa012dff` 三连 + default
unsupported 分支）：

- 5 个构建的分发链**结构完全同构**，LHDC V5 均未接入，全部落到 default unsupported 分支；
- 5 个构建都**存在** `A2DP_VendorCodecEqualsLhdcV5` 函数（字符串与语义指纹均证实）——
  问题确认是分发链漏接，而非函数缺失；
- 字符串佐证：`unsupported codec` / `VendorCodecEquals` / `LhdcV5` 在 5 个构建中均存在。

结论：**PJZ110_1609401 的 bug 在 5 个构建中普遍存在**，仅 PJZ110_1609401 已适配。

## 4. 当前结果（适配矩阵）

| 独立构建 | 机型 / 系统 | 码率 branch | 快切等价补丁 |
| ---- | ---- | ---- | ---- |
| PJZ110_1609401 | PJZ110 16.0.9.401 / OnePlus 13（.402 一加 11 共用 spec） | ✅ | ✅ 已适配 + 真机验证 |
| OP15/Ace6T | OnePlus 15 / Ace 6T C16.0.7.201 | ✅ | ❌ 待适配 |
| PJZ110_1608301/PLK110 | PJZ110 16.0.8.301 / PLK110（Buds Ace 3） | ✅ | ❌ 待适配 |
| PLC110 | PLC110 16.0.8.300 | ✅ | ❌ 待适配 |
| RMX6688 | realme RMX6688（MTK） | ✅ | ❌ 待适配 |

## 5. 等价补丁实施计划

### 5.1 各构建差异（决定独立 spec 的要素）

语义模板（LHDC V5 id 构造、字段校验、质量位掩码 `0xc0100735`）跨构建复用，但以下必须
逐构建适配：

| 差异项 | OP15 / PJZ110_1608301 / PJZ110_1609401 | PLC110 / RMX6688 |
| ---- | ---- | ---- |
| CIE 指针寄存器 | x21 | x28 |
| CIE 栈偏移 | x29 - #0x70 | x29 - #0x60 |
| default 入口（补丁点） | 0xa067d4 / 0xa0a1e4 / 0xa0a4e4 | 0x9b1470 / 0x9a91e0 |
| 函数尾跳转目标 | 各不同 | 各不同 |

### 5.2 实施步骤

1. 新增 4 个 `CodeBlockSpec`（`lhdcv5_quality_equals_op15 / _pjz110_1608301 /
   _plc110_1608300 / _rmx6688`），orig = 各构建 default 块等长字节，patch = 语义模板
   适配寄存器 / 栈偏移 / 跳转目标，不足等长处以原尾部指令补齐；
2. 单测：每个 spec 原样唯一命中、patch 后语义等价、与 PJZ110_1609401 模板指令一致性；
3. 构建（debug/release）+ 静态验证；
4. 真机验证（见 §7）。

## 6. 版本漂移策略

- 精确 whole-block 签名是**有意设计**（"OTA 重编译即不支持，不猜测补丁"）——宁可报
  unsupported 也不误伤；
- 版本更新（如 16.0.8.301 → 16.0.9.401）后，字符串表 / 链接布局漂移会使字节序列失效，
  需要重新提取 spec；
- 同一版本线的多机型若 so 相同（如 .401/.402）可共用 spec；
- **未来可选优化**：为等价补丁增加 ARM64 语义扫描定位（与码率 branch 的语义兜底一致），
  缓解版本漂移，但写入仍需精确字节。

## 7. 验证清单

每台真机设备：

1. 安装模块，开启「实验性：自动码率保护」（诊断页）；
2. 播放中切换「音质优先 ↔ 连接优先 ↔ 自适应」，观察：
   - 无卡顿 / 无重建输出中断；
   - 实际码率跟随档位（logcat `quality_mode` / `target bit rate`）；
3. 关键日志：
   - 不应再出现 `A2DP_CodecEquals: unsupported codec id 0x4c35053aff`；
   - `restart_output=false`（纯质量变化）；
   - `native.patch.state.recv status=patched`（码率 branch 与等价补丁均生效）；
4. 反馈包一份（含 root 蓝牙日志），离线核对。

## 8. 相关证据

- 快切报错与重建时间线：`feedback/OnePlus Buds Ace 3/OnePlus13-flow-spec-ab-20260806-025920/
  logcat-all.txt`（line 54157 起：unsupported codec → 会话结束 → AVDTP Reconfig →
  启动拥塞 TX 45/45 → choppy）；
- 反汇编 / 扫描脚本：`native_research/`（`analyze_patterns.py`、临时扫描脚本见会话记录）。
