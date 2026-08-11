# LHDC V5 Native 补丁新版本线适配指南

> 用途：拿到新 OTA 的 `libbluetooth_jni.so`（和 `liblhdcv5BT_enc.so`）后，按本指南完成
> 「码率 branch」与「快切等价」双补丁的静态适配与验收，不用从头查证。
>
> 前置阅读：`docs/native-patch-adoption-plan.md`（两个补丁的机制与历史决策）、
> `README.md`「LHDC V5 运行时治理与内存补丁」章节。
>
> 相关代码：`app/src/main/java/xyz/melodylsp/codec/system/NativeLhdcMemoryPatch.java`
> （spec 表）、`NativeLhdcMemoryPatchTest.java`（回归测试）。
>
> 样本与脚本：工作区根目录 `native_research/`（不在 module Git 仓库内；测试运行时从
> `user.dir` 向上查找 `native_research`，新目录放好即自动纳入覆盖）。

## 0. 先判断：这次要不要改代码

| 场景 | guard 字节 | 快切块字节 | 动作 |
| --- | --- | --- | --- |
| A | 命中现有表 | 命中现有表 | 零代码改动（可选：仅补文档） |
| B | 命中现有表 | 新字节 | 只加 `CodeBlockSpec`（16.0.10.501 即此场景） |
| C | 新字节但语义扫描唯一 | 新字节 | 加 `PatternSpec` + `CodeBlockSpec` |
| D | 语义扫描不唯一 | 任意 | 停止，不写未知地址（设计行为 `unsupported`） |

注意：码率 branch guard 有语义扫描兜底（`semantic_guard_v1`），不加 `PatternSpec` 也能
工作；加它的价值是诊断页能标注具体版本线。快切等价块**没有**语义兜底，OTA 重编译后
报 `unsupported` 是设计行为，必须按本指南人工适配。

## 1. 样本准备

- 目录约定：`native_research/<设备>_<版本>/libbluetooth_jni.so`；`liblhdcv5BT_enc.so`
  一并放入（如有）。
- 先记 sha256 并与现有样本比对：同哈希 = 同构建，直接归入场景 A。
- 可选：写 `manifest.txt`（设备 / 构建 / 指纹），参照
  `native_research/PJZ110_16.0.10.501/manifest.txt`。

## 2. 定位快切等价块（核心，无兜底）

1. 搜索块入口签名 `68ac8052`（`mov w8, #0x563`，default unsupported 块第一条）。
   骁龙线入口约在 0xA0xxxx（16.0.8.301@0xa0a1e4、16.0.9.401@0xa0a4e4、
   16.0.10.501@0xa0ccb4），MTK 线约在 0x9axxxx（PLC110@0x9b1470、RMX6688@0x9a91e0），
   随库大小漂移。
2. 反汇编约 40 条，与参考构建逐条对比：27 条指令内应**只有** `adrp/add`、`adr`、
   `bl`、尾 `b` 的立即数不同，其余完全相同；原块 `+0x68` 必须是 `0a000014`
   （`b +0x30` 到 accept 路径）。
3. 判定组别：
   - Group A（骁龙线，CIE 指针 x21、CIE 栈 x29-#0x70）：OP15/Ace6T 16.0.7.201、
     PJZ110 16.0.8.301(+PLK110)、16.0.9.401(+.402)、16.0.10.501
   - Group B（MTK 线，CIE 指针 x28、栈 x29-#0x60）：PLC110 16.0.8.300、RMX6688
4. 提取 108 字节原块（27 条指令 = 216 个 hex 字符），Java 源码中按 64/64/64/24 拆 4 段。
5. 替换块直接复用**同组共享** patched 字节（组内完全一致；两组在 `+0x14`、`+0x50`
   编码不同，别串组）。校验点：patched `+0x10` 的 b.ne 偏移 `+0x80`（落 +0x94）、
   patched `+0x68` 尾 `0c000014` 偏移 `+0x30`（落 +0x98）、safeGateInstruction =
   `0x14000024`（`b +0x90`）。
6. 唯一性（全部必须满足，命令见附录）：
   - 新原块在目标库恰好 1 次；
   - 新原块在其他已知库 0 次；
   - patched 块在目标库 0 次（无预补丁）。

## 3. 定位码率 branch guard

1. 跑 `python native_research/semantic_window_check.py`：目标行应恰好 1 个 original、
   0 个 patched（RMX6688 只有 window8 命中——MTK 在 guard 与 `mov wN,#4` 之间插入
   adrp/add；Java 侧已固定用 8 条窗口，QCOM 通常 4 条内命中）。
2. 提取 16 字节签名：`cmp xN,#2`(4B) + 条件分支(4B) + 后续 8B。签名从 cmp 开始，
   分支在 `+4`（即 patchDelta=4），patchBytes = 到同一 target 的无条件分支。
3. 与 `PATTERN_SPECS` 表比对字节：同字节 → **改名合并**而不是新增重复条目
   （测试要求 orig 两两不同；示例：`branch_plus_68_pjz110_1609401` →
   `branch_plus_68_pjz110_1609401_1610501`）；新字节 → 新增条目，命名
   `branch_plus_<imm26 十进制>_<设备>_<版本>`。

## 4. 编码器库检查

- `Get-FileHash` 与已知样本比对：同哈希 → 治理器零改动（16.0.10.501 与
  PLK110 16.0.8.301 即同哈希 `d36edd9a...`）。
- 不同 → 把新路径加进 `native_research/check_enc_exports.py` 的 libs 字典并运行，
  确认 4 个导出齐全：`lhdcv5BT_encode`、`lhdcv5BT_free_handle`、
  `lhdcv5BT_set_target_bitrate_inx`、`lhdcv5BT_get_bitrate`。导出缺失时治理器无法
  安装，需另作分析。

## 5. 代码改动清单

1. `NativeLhdcMemoryPatch.java`：
   - `PatternSpec`（场景 C）或改名合并（同字节覆盖新版本线）；
   - 新增 `CodeBlockSpec`：`lhdcv5_quality_equals_<设备>_<版本>`，orig = 新 108B，
     patched = 同组共享字节，safeGate = `0x14000024`；
   - 更新类注释 Group A/B 版本列表与构建计数。
2. `NativeLhdcMemoryPatchTest.java`：
   - `allQualitySwitchSpecsAreBoundedConsistentAndDistinct` 的 spec 计数断言 +1；
   - `everyBuildSpecMatchesItsLibraryUniquelyWhenAvailable` 加一行
     `{"<spec名>", "<目录>/libbluetooth_jni.so"}`；
   - 新增专属测试 `qualitySwitchPatternMatches<设备><版本>UniquelyWhenLibraryAvailable`
     （照抄 16.0.10.501 版本）。
3. `README.md`：「覆盖全部已知版本线」bullet、适配矩阵加行（未真机验证**不加**
   「真机验证」标注）、「补丁流程」里 pattern 变体列表如有改名同步。
4. `native_research/verify_java_specs.py`：spec 数断言 +1（当前 6）。

## 6. 验收（全过才算静态适配完成）

1. `python native_research/verify_java_specs.py` → `OK: 6 specs structurally consistent`
   （数量随表增长）。
2. 附录命令跑唯一性检查。
3. Gradle 单测（走 ASCII junction，避开中文路径 AIDL 的 GBK 问题）：
   `cd E:\melody-lsp-link` 后
   `.\gradlew.bat :app:testDebugUnitTest --tests xyz.melodylsp.codec.system.NativeLhdcMemoryPatchTest`
   预期 BUILD SUCCESSFUL、全部通过（含新增测试）。
4. 不做完整 assembleRelease（过度验证）；需要测试 APK 时参照 RMX6688 流程
   （临时改 versionName、junction 构建、aapt2/apksigner 校验、用后还原）。

## 7. 设备验证（静态适配不替代）

- 安装 → 重启 `com.android.bluetooth` → 诊断页两行状态或 logcat：
  - `evt=lhdc.memory_patch` → `status=patched`
  - `evt=lhdc.memory_patch.fast_switch` → `status=patched`
- 切「音质优先」不应出现「未适配 / 未完整适配」toast。
- 通过后才把 README 适配矩阵标注「真机验证」。

## 8. 常见坑

- 骁龙线样本 vaddr == file offset，语义脚本直接可用；不要假设其他平台也这样。
- 块字节必然不同：`adrp/add/adr/bl` 是绝对地址，OTA 重编译必变；结构等价才是
  判断依据，不要期待整块字节相同。
- 快切块无语义兜底：新 OTA 报 unsupported 是设计行为（README 已说明），按本指南补
  spec 即可。
- Group A/B 的 patched 块不同（`+0x14`/`+0x50` 编码差异），选错组会在设备上报
  `verify_failed`。
- 同字节模式改名合并时，README「补丁流程」变体列表要同步。
- 中文路径构建报 `MalformedInputException` 时一律走 `E:\melody-lsp-link` junction
  （junction 指向 module，改动自动共享）。
- 新样本目录放好后再跑测试：`findWorkspaceRoot` 自动向上查找并覆盖新目录。

## 附录 A：常用命令（PowerShell）

```powershell
# 1) guard 语义扫描（输出各库 window4/window8 命中；目标行应各恰 1 个 original）
python native_research/semantic_window_check.py

# 2) 块入口定位：搜索 68ac8052 命中位置，再按附录 B 反汇编确认结构
python -c "import re;d=open(r'<LIB>','rb').read();print([hex(m.start()) for m in re.finditer(bytes.fromhex('68ac8052'),d)])"

# 3) Java spec 表结构校验（数量、长度、尾跳转）
python native_research/verify_java_specs.py

# 4) 单测（junction 构建）
cd E:\melody-lsp-link
.\gradlew.bat :app:testDebugUnitTest --tests xyz.melodylsp.codec.system.NativeLhdcMemoryPatchTest
```

反汇编对比、108B 提取与唯一性检查可直接参照 `native_research/` 下
`disasm_*.py`、`analyze_equality_blocks.py`、`semantic_window_check.py` 的已有实现，
改路径参数即可。

## 附录 B：参考地址速查（分支 vaddr = 分支指令地址；pattern 基址 = 分支 - 4）

| 版本线 | 样本目录 | guard 分支 vaddr | 快切块入口 |
| --- | --- | --- | --- |
| 16.0.7.201 | OnePlus 15 / OnePlus Ace 6T C16.0.7.201 | 0x720f40 | 0xa067d4 |
| 16.0.8.301 | PJZ110_16.0.8.301（= PLK110） | 0x7242cc | 0xa0a1e4 |
| 16.0.9.401 | PJZ110_16.0.9.401（.402 共用 spec） | 0x7245d4 | 0xa0a4e4 |
| 16.0.10.501 | PJZ110_16.0.10.501 | 0x73696c | 0xa0ccb4 |
| 16.0.8.300 | PLC110_16.0.8.300 | 0x71375c | 0x9b1470 |
| RMX 线 | realme RMX6688 | 0x70baa0 | 0x9a91e0 |
