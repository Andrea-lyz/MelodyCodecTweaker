# 宿主 COUI UI 的 DexKit 定位

`HostUiBindings` 在 `HostHookInstaller.install()` 中复用现有 DexKit bridge，
按当前宿主 APK 解析一次，随后关闭 bridge。结果仅在该宿主进程中缓存，
升级宿主并重启进程后自动重新解析；不跨 APK 版本保留旧类名。

## 定位契约

- 弹窗：以库自身的 `COUIAlertDialogBuilder` 日志字符串定位基础 builder，验证 Context
  构造器、标题、正文、正负按钮与返回 Dialog 的 show 方法；只接受唯一候选。继续通过
  DexKit 的 superClass 查询寻找宿主包装器，要求直接继承基础 builder、完整弹窗契约、
  自身覆写正按钮设置方法且唯一命中。优先使用这个包装器，保留宿主模糊和高亮按钮语义。
- 菜单：以 `COUIPopupListWindow` 日志字符串定位，验证 PopupWindow 继承关系、
  List 设置方法、View/int/int 展示方法和点击监听器设置方法。
- 条目模型：从菜单的 `int -> item` 返回类型确定，验证字段结构。
  优先沿官方 BaseAdapter.isEnabled 的委托链寻找 enabled 字段。
  新 COUI 的 isEnabled 仅判断分隔行时，使用官方 item builder 的 reset/build
  默认状态（enabled=true、selected=false）区分两个字段，不按字段名字排序猜测含义。
- 17.6.6 的 R8 保留了条目 builder 的 reset/build 方法，但移除了构造器。
  仅对经过结构检查的纯字段 holder 使用 allocateInstance，再执行 reset/build；
  不实例化界面或宿主单例。任何不匹配或不唯一的结果都转入兼容路径。

主路径不限制包名长度、类名或 DEX 序号，删除了原先最多检查 2000 个短类名、
同包查找条目等扫描限制。已知旧版本绑定仍可兜底。

LE Audio 确认框不再使用 AppCompat builder 配 COUI 布局的组合。
显式优先选择宿主 `COUIAlertDialog.BottomWarning` 主题，资源表使用点号命名；
`COUIAlertDialog_BottomWarning` 仅作旧宿主别名兼容。COUI 框首次布局后再检查
正负按钮文字和按钮自身可见性，避免在 `show()` 返回时过早检查 `isShown()`。
按钮缺失则关闭该框并使用明确的系统
日间/夜间 Dialog 主题，保证确认与取消入口存在。

## 已完成的验证（2026-10-24）

- PJZ110 / ColorOS 17.0.0.103，实际无线耳机 APK 17.6.6。
- 初次设备临时 app_process 探针：`dialog=U2.h`、
  `popup=com.coui.appcompat.poplist.b`、`item=b3.s`、`selected=i`、`enabled=j`。
  展示方法为 `A(View,int,int)`，监听器设置方法为 `z(OnItemClickListener)`。
- 238 个单元测试，0 failures / 0 errors；debug、release APK 构建通过。
- 上述探针仅验证实际 APK 的解析与条目字段赋值，没有安装新模块。
  OneSpace / DetailMain 的按钮、弹层样式、选项点击，以及深色模式，仍需新包加载后验收。

日志 `ui.bindings.resolved` 会记录 DexKit 命中结果并保留为进程状态，
问题录制可检查 `le.melody.dialog` 和 `ui.choice.popup` 是否实际使用 COUI。

## 23:32 测试包弹窗回归修复

用户截图中居中、浅色按钮的弹窗实际为系统 fallback。保留的 LSPosed 日志确认：
`U2.h` 报 `dialog_actions_missing`，随后记录 `le.melody.dialog builder=framework`。
同时原定位只找到库基类，没有选用 Melody 的 `r7.b`；该包装器构造器开启背景模糊，
正按钮覆写转入 COUI 的高亮入口。现补齐包装器定位、底部主题与首次布局后的校验。

更新后的手机隔离探针确认 `base=U2.h wrapper=r7.b`，底部主题为 `0x7f120148`，
下划线名称查询返回 0。完整界面渲染需要系统登记的应用进程；隔离 app_process 在
包装器注册模糊效果 ContentObserver 时被系统拒绝，因此不把此次探针当成渲染验收。
240 项单元测试与 release 构建通过，最终底部布局、双按钮和颜色仍须更新包在宿主中验收。

新日志 `le.melody.dialog.validated` 在首次布局通过后记录 builder 和 gravity；
若按钮确实不可用则记录 `le.melody.dialog.actions_missing` 后再启用系统兜底。
