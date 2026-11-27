# 验证记录

## 开源基线回归测试（首批）

- GitHub Actions: https://github.com/ylh440104/twoyi-apk-reverse/actions/runs/45587662013
- 验证分支: verify-opensource-boot-input
- 远端验证提交: 8726c8f0fe4d98267e66cb637dbb565e552ced65
- boot-state: success。编译实际 TwoyiStatusManager 类，使用小型 Android API stub 验证超时后恢复、通知不阻塞、重复观察、重置旧会话。
- input-stream: success。直接编译生产代码使用的 event_stream.rs，验证发送端关闭后排空退出，以及写错误时不等待发送端关闭而立即退出。
- 本机未运行 javac、rustc、Android 构建或反编译。

## 新仓库首次成功构建

- 新仓库：https://github.com/ylh440104/twoyi-baseline（公开，独立于分析仓库 twoyi-apk-reverse），分支 main。
- 构建运行：https://github.com/ylh440104/twoyi-baseline/actions/runs/45735948256 ，普通 GitHub 托管 runner，assemble 成功，未在任何本机执行编译。
- 固定上游提交 dd052a07a812e742ec7583da393466ccc066a8b1；打包报告记录 development_sha=29e1ddfa367d2f4b3a9d3ecab62a3fb535dda186、run_id=45735948256。
- 应用 APK 211553105 字节，SHA-256 7bcc631c5700ed8264dc768efe5ad376d0455012bc317f55e975c91aac6cfea7；测试 APK SHA-256 ec9e0ef2202e6798591e6b13859a3bba5d2968c85d173dc76fc3c509497265e6。下载后本地 sha256sum 与报告一致。
- 归档预检输出 Bundled ROM passed the installer's archive preflight，随包 rootfs.7z 与 rom.ini 通过打包哈希校验。
- 预发布下载：https://github.com/ylh440104/twoyi-baseline/releases/tag/baseline-11260131 。这是 debug 构建，未经真机验收，非发布质量产物。
- 该进程中发现并修复三个仅靠云端编译才能暴露的问题：commons-compress 1.20 需要 xz 1.8 的 LZMA 流类、GuestSessionService 重复字段、UIHelper 缺少 Set 导入与 Glide 生成 API 依赖注解处理。
- 托管 x86 模拟器任务已移除：宿主 APK 仅含 arm64-v8a 且来宾为 ARM 镜像，x86 模拟器无法承载；设备级验收改由专用 ARM64 runner 执行，目前 skipped（需要 workflow_dispatch 且 device_smoke=true）。
## 完整云端构建进度

- 开发验证仓库：ylh440104/twoyi-baseline（公开），分支 main；通过轻量文本 overlay 叠加固定上游提交，runner 仅浅拉取上游。
- 首次 APK 构建：https://github.com/ylh440104/twoyi-apk-reverse/actions/runs/45588006975 。SDK 安装失败，原因是新版 sdkmanager 要求 JDK 17；尚未到 Rust 或 Java 编译。
- 已提交修正 12cd23fc0696d971b90c666099913779e6ebf429：SDK 工具使用 JDK 17，旧 Gradle 使用 JDK 11。重跑结果待检查。
- 同一开发分支回归测试：https://github.com/ylh440104/twoyi-apk-reverse/actions/runs/45588003829 ，两项 success。
- 旧版官方 ROM 仅在 runner 获取，记录下载校验值；当前未固定预期校验值，不能宣称完整可重现构建。

## 多指输入实现进度

- 触点映射独立测试运行 45588813568 成功，验证非连续 pointer ID、容量限制、slot 复用和取消清理。
- 开发提交 8b65e3f926c129f50dcd359a8c0146fae5279b38 已接入 TouchSlots，修复 MOVE 坐标、取消、重连状态与同步帧，云端完整编译待确认。
- 随后本机源码进一步修正设备 capability 位图与 tracking ID 范围；该后续修改尚未发布到开发分支，下一轮需一起提交验证。
- 完整构建 45589007575 最后检查仍在 Rust 阶段运行，未得到 APK 结果。

## 基线编译与依赖修复

- 45589007575：Rust 1.60 / NDK 22 的 JNI 编译成功，旧 ROM 下载与提取成功；Gradle 因 com.hzy:libp7zip:1.7.0 无法解析失败。
- 将一般依赖来源切换为 Maven Central，仅为 libp7zip 添加限定模块的 Aliyun JCenter 归档镜像。POM 可访问，不代表 AAR 解析已通过；云端重跑待确认。
- eb07acccfdb59b0e374e14ae7d492992f3d427c7 已发布上述依赖修复及触摸 capability 位图修改。
- 原始 0.5.4 APK 的 runner 实测 SHA256 为 ca5e25b3d1852c3b9df8f776f5798269de3c3c40fbf4b92c3768f483ee1a71b5。本地 workflow 已新增此校验，尚待下一次 workflow 发布。
- 45589368403：接入触摸 slot 后的 host 回归测试成功；实际 Android 输入链路仍待验证。

## 首次完整构建成功

- https://github.com/ylh440104/twoyi-apk-reverse/actions/runs/45590001636 全部构建步骤 success，包含 ARM64 JNI、ROM 提取和 assembleDebug。
- Artifact 15010154512：opensource-baseline-build，210743848 bytes，已确认未过期；未下载至本机。
- 该构建包含 eb07accc 的触摸设备能力修正与归档依赖来源。
- 后续提交 6f4d6589722e2e45eed711ca5d2967db0d008bd3 增加固定 APK 校验、打包内容核验和签名验证，重跑待确认。

## Activity 与 ROM 生命周期进度

- 45590757132：固定旧 APK 校验、assembleDebug、打包资源校验、签名验证均 success。
- 45591641548：第一批 Activity 恢复修改完整构建 success；不等于设备重建测试通过。
- cb1fa7ad262dcdf430431789b2be84e7dc92de68：新增进程共享 RomPreparation，界面重建等待同一次任务；首次解压与复制失败不再被忽略；安装中标记用于中断后重装。完整构建 45592148580 success，host tests 45592148569 success。
- 随后本地 RomManager.initRootfs 改为属性写入失败时抛出异常，尚待发布；避免错误清除安装中标记。
- ffce0141e40e4e1d41919d3e948c65a8fdadc91a：发布同进程、非导出前台 GuestSessionService，中英文常驻通知，START_NOT_STICKY；准备失败停止服务。一起发布 ROM 属性写入失败异常。构建结果待收集。
- 45591773314：迟到完成等待与移除 Activity reset 修改完整构建 success。
- 前台服务当前基于旧 target SDK；新版平台限制、通知行为及实际后台存活尚未实测。
- 未实现安装 staging/旧版本回滚和原生 Surface 代际保护。

- 449042f564f3daeb11a34f71f205fb4a85567e5f：Activity 以弱引用订阅 ROM 准备，销毁时中断启动等待线程，避免长期持有旧界面；中断与启动失败分开处理。云端结果待收集。
- 前台 Service 构建 45592790123 最后检查仍在执行；其 host tests 45592790121 已成功。

## 前台 Service 与窗口回调验证

- 45592790123：前台 GuestSessionService 和属性写入失败处理的完整 APK 构建 success。
- a15dfb8c117d6b213b73f3b6fa86087b17ed3ee9：新增 WindowOwner，在新 Surface 接管后忽略旧 holder 的 resize/destroy 回调。
- 45593744399：包含实际 WindowOwner 的回归测试 success，验证窗口替换后的过期销毁不影响当前窗口；完整 APK 构建 45593744522 待结果。
- Java holder 身份保护不解决 native 初始化就绪和 ANativeWindow 引用生命周期，不能代替真机窗口重建测试。

## 导航键实现进度

- c6d1562b6cb01abe6225111ccfacc323975b9c9c：新增 Android 到 Linux 的 BACK/HOME/MENU/音量映射，未知键忽略，完整按下/释放同步帧；键盘能力位图同步更新。Activity 返回明确传入 BACK。
- 音量映射仅作为 native API 能力，尚未接管实体音量键。
- 新增云端 key_events.rs 测试，结果待收集；guest Generic.kl 实际映射及设备运行仍待确认。
- 45593232899：Activity 观察者释放修改完整构建 success。

## Guest 启动诊断进度

- 45594207411：导航 key_events 回归测试 success；完整 APK 构建 45594207337 待结果。
- 45593744522：窗口 holder 保护完整 APK 构建 success。
- f33e9e19ae9dce86291e21cba6bc74173fd159a1：原生 init spawn/log 参数错误通过 JNI IllegalStateException 上报，UI 停止等待和通知；持有 Child 并 wait 回收与记录退出状态，标记后续不能直接重启。
- 云端提交 `cb1f1b1b` 已加入运行中 init 退出的 JNI 回调，用于更新 Java 界面和前台服务。回归测试与 APK 构建分别见 Actions 运行 `45597231467`、`45597231529`。设备端退出通知和完整进程树回收仍待验收。
- 新 JNI 错误路径完整编译与设备故障注入待验证。

## 基座关机流程

- 设置内的关机与重启现在由工作线程停止宿主创建的 guest init 进程组，并等待回收 init。移除了启动时按父进程编号批量清理的旧逻辑。
- 关闭成功后退出宿主进程以释放旧渲染器。重启通过 Android AlarmManager 请求延迟重新打开，实际行为需覆盖系统后台启动限制。
- 源码检查已核对 JNI 方法、子进程状态和字符串资源。完整 APK 构建及真机验证待执行，尤其需确认独立进程组中的 guest 服务是否残留。
- ARM64 设备用例新增窗口重建、Activity 停止和恢复期间的 init PID 一致性检查。用例随后调用原生停止接口，检查 init 已回收，以及重复停止仍能成功。用例待云端执行。设置页重启和跨进程组服务清理需要单独验收。

## 设备端 ROM 安装验收

- 本地提交 `481e195` 增加 ARM64 专用设备用例，使用随 APK 打包的旧版配套 ROM，覆盖首次安装、再次安装、事务恢复及完成后状态清理。
- 用例分别在 `data` 和 `sdcard` 放置标记文件，更新后逐字节检查内容。Actions 会在文件选择器和窗口重建测试之前运行此用例，并保留设备测试输出。
- 已静态检查 YAML、脚本语法和执行顺序。APK 编译与设备测试待云端运行。

## 原生窗口引用

- 本地实现为当前窗口和渲染启动线程分别保留引用。尺寸更新与关闭时使用临时引用，并核对回调对应的窗口仍在使用。尺寸更新采用 Android Surface 回调报告的缓冲区尺寸。
- 已静态核对 `ndk` 0.6.0 的窗口封装及宿主引用路径。旧渲染库何时完成初始化、如何持有子窗口，以及替换和关闭时的实际行为，仍需云端构建和 ARM64 设备验收。

## APK 导入入口

- `cb3e257` 在连接来宾 ADB 之前检查 guest 是否已完成启动。未启动或已退出时，导入回调会收到明确提示。
- `InstallerReadinessTest` 覆盖未启动时拒绝导入，已接入 ARM64 设备工作流。工作流和资源静态检查通过。设备用例与启动后实际安装 APK 的流程待验证。

- 本地安装流程为每次导入创建私有 ADB socket，并分别限制服务启动、连接和安装的等待时间。`8c50c12` 将正向导入接入 ARM64 生命周期用例。用例在来宾启动后经正式安装器导入一份路径含空格的测试 APK，检查安装成功回调、私有 ADB 工作目录回收及来宾 init PID 不变。
- `74b8bdd` 将正向导入用例的输入改为由 Actions 生成的独立测试 APK。它声明 API 27 且无可执行代码。工作流会检查 API 27 的签名兼容性，并在打包后核对测试 APK 内嵌文件的哈希。
- 云端工作流语法与本地差异检查已通过。仍待运行设备用例以确认随包 `libadb.so` 对 `localfilesystem` 的支持。`77e5af4` 增加了损坏 APK 的安装失败用例，检查安装阶段的错误反馈和临时目录清理。分包安装与异常退出后的进程回收仍待覆盖。

- `91bf67e` 让已安装应用列表按同一组 base 和 split APK 检查来宾 ARM64 兼容性并发起导入。新增设备用例覆盖无原生库的 base 搭配三种 ABI 分包的筛选结果。工作流语法已检查，设备用例和 `install-multiple` 实际安装仍待执行。文件选择器选中多份 APK 时，宿主要求至少一份可解析的 base APK，并检查其余无法单独解析的文件是否含有 APK 清单。分包归属、版本和签名交由来宾包管理器验证。设备用例覆盖分包排在 base 前面和拒绝非 APK 成员。

- ARM64 生命周期用例现通过正式安装器依次导入单 APK、同签名的 base 与 feature 分包组合，并检查损坏 APK 的失败清理。Actions 生成两份 API 27 测试 APK 并核对签名和打包后的哈希。实际分包安装待 ARM64 设备执行。

## 中断安装阻塞后续安装

- 发现一个会永久卡死“给实例装系统”的缺陷：ROM 事务开始时要求槽目录里没有任何既有日志/临时目录，而安装被取消或进程被杀会留下 `.rom-install-v1` 与 `.rom-build-*`。槽 0 的启动路径本来就会先 `recoverInstall`，但按槽安装路径没有，于是**一次中断之后该槽再也装不上**；释放实例也不会清这些残留。
- 修复：`RomInstallTransaction.abandon` 丢弃“未发布计划”的事务（拒绝清掉已发布的计划，那种必须走恢复）；`RomManager.installRootfsInto` 先 `recover()` 既有事务再开新事务；带清理的释放先 `abandonIncompleteInstall` 再删 rootfs。
- 保持纯 Java 可测：`GuestInstanceManager` 不直接依赖 Android 的 `RomManager`，改为接受 `SlotCleaner` 回调（Android 侧传 `RomManager::abandonIncompleteInstall`），宿主用例可注入探针验证回调确实被调用。
- 回归新增：begin→abandon→begin 可复用、空闲时 abandon 返回 false、已发布计划被拒绝 abandon。
- Actions 全部通过（十四项 PASS），运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45787896768 ，同批 APK 构建成功。
- 未验收：真实解包被中断（杀进程/断电）后的恢复效果。

## 下次启动直接显示崩溃堆栈

- 新增：若上一次启动在界面出现前崩溃，下一次启动会弹出对话框显示记录到的完整堆栈，用户点“继续”后清除记录。这样即使进不了设置、也没有 adb，也能把崩溃内容截图出来。
- 新版本 `baseline-11261016`：https://github.com/ylh440104/twoyi-baseline/releases/tag/baseline-11261016 ，应用 APK 211595877 字节，SHA-256 `8110327643cabb5d1f9bb37acc65e4e865754596554308ffbcbfaa75d10bac30`（与打包报告一致）。
- 同时确认打包报告里 `lib/arm64-v8a/*.so`、`assets/rootfs.7z`、`assets/rom.ini` 的打包哈希与构建输入完全一致，排除“资源缺失导致启动失败”。
- 闪退根因仍未确定；需要该对话框内容或 `last-crash.txt`。

## 闪退的降低风险版本

- 在拿到堆栈前，先移除三条启动期风险：debug 构建不再启动 AppCenter；`RomManager.ensureBootFiles` 抛异常只记录不再中断启动；清单里未启用的四个 `:guestN` 服务声明移除（类保留，等多进程真正落地再加回）。
- 设置页对偏好查找做空值保护，避免布局与实际条目不一致时 NPE。
- 新版本 `baseline-11260958`：https://github.com/ylh440104/twoyi-baseline/releases/tag/baseline-11260958 ，应用 APK 211594589 字节，SHA-256 `53945f7ce36fa57836482a2a0a27f367d18a0f60a402cde2df11f8de2d435341`（与打包报告一致）。
- 这些是**降低风险**，不是根因修复；仍需真机崩溃文件确认。

## 阶段四十二：`ET_EXEC` 固定映射，真实来宾 init 引导成功（88 项，本机实测）

- 新增 `MappedImage::map_fixed()`：用 `MAP_FIXED_NOREPLACE`（`0x100000`）在 `reserve_start`
  请求映射；范围被占用就**失败**而不是替换别人的映射。`run_child` 遇到 `ET_EXEC` 自动走这条路。
- `preflight` 的 `NeedsFixedMapping` 改为只在链接地址 `< 0x10000`（基址为 0、用户态不可映射）时才阻塞。
- 新增 2 项测试：固定映射确实落在链接地址；目标范围被占用时**报错而非覆盖**。合计 **88 项全绿**。
- **实测真实来宾 `rootfs/init`**（Android 8.1，`ET_EXEC`，链接地址 `0x400000`）：
  ```
  check: loadable
  outcome: Signaled(11) fault: signal=11 address=0x200000 pc=0x4ba430
    pc is at file offset 0xba430
  ```
  `pc` 首次**落在镜像自己的地址范围内**（上一轮还是 `0x5f` 这种垃圾值），
  说明引导成功、来宾 `init` 的代码真的在执行；随后碰未映射的 `0x200000` 而崩——
  完整启动 `init` 还需要 `/dev`、`/proc`、mount 等环境，属于运行时范畴。
- 仍未实现：`DT_RELR`/`DT_ANDROID_REL` 解析（`toybox` 等现代系统二进制需要）。

## 阶段四十一：第一个真实 bionic 程序跑通（86 项，本机实测）

- 换可控对象：用 Termux clang 编一个**动态链接 bionic** 的最小程序（`hello`，6 个导入），
  符号正好由宿主 bionic 提供。
- 新增 `run::run_traced()`：**ptrace** 子进程，`PTRACE_GETREGSET`(`user_regs_struct` 272 字节)
  取 `x0..x30/sp/pc`，`PTRACE_GETSIGINFO` 取 `si_addr`——**来宾自装信号处理器也挡不住取证**。
- 取证定位到根因：`pc=entry+0x120`、`x8=0`、`si_addr=0`，对应指令 `ldr x0,[x8]`（读 `stdout`），
  GOT 槽为 0。原因是 `stdout` 是**数据符号**，走 `R_AARCH64_GLOB_DAT`(1025)，而只处理了
  `R_AARCH64_JUMP_SLOT`(1026)，**数据导入全都没解析**。
- 补上 `GLOB_DAT` 后实测：
  ```
  libraries: 2/2 opened
  imports: 0 unresolved of 6
  hello from bionic, argc=3
  outcome: Exited(7)
  ```
  **第一个真实 bionic 二进制在自研 loader 上完整运行并正确退出。**
- 顺带如实报告两种装不了的镜像：`toybox` 用 **`DT_RELR`**、真实 `init` 是 **`ET_EXEC`**
  （须映射到 `0x400000`）。`preflight` 新增 `NeedsFixedMapping` 与 `UnreadableRelocations` 两个阻塞项，
  `Elf` 新增 `elf_type` 与 `relocation_tables`。
- 新增 2 项测试（共 **86 项全绿**）：ET_EXEC 被判 `NeedsFixedMapping`、ET_DYN 不受影响。

## 阶段四十：TLS/TCB 尝试与边界（84 项，本机 bionic 实测）

- `Fault` 增加 `pc`：从 `ucontext.uc_mcontext.pc` 取，`runinit` 会把 PC 换算成文件偏移。
  实测：真实 `init` 崩在 `pc=0x5f`/`address=0x200000`，`toybox` 崩在 `address=0x35230`。
- `Elf::tls`：解析 `PT_TLS`。**实测两者都没有可用 TLS 镜像**（toybox `memsz=0`，静态 `init` 无 `PT_TLS`）。
- 按 variant I 实现 TLS 块（分配、拷初始镜像、`msr tpidr_el0`）后，**诊断代码自己崩了**：
  自造的全零 TCB 不满足 bionic 布局（errno 与 bionic 槽位在线程指针后面），
  切过去后同线程任何宿主 libc 调用都会踩坏地址。
- 因此改成：TCB 块照常分配，但**只有 `PT_TLS.memsz > 0` 时才切 TP**，且切换是最后一步、
  之后不调用宿主 libc。真实二进制行为回到上一轮（能报地址，仍崩）。
- **结论/空缺**：真实 bionic 程序需要**符合 bionic 布局的 TCB**（`pthread_internal_t`/`bionic_tls`/`dtv`），
  不能靠猜；在做到之前 TLS 是空缺项。84 项测试全绿。

## 阶段三十九：崩溃地址取证，为 TLS 定位（84 项，本机 bionic 实测）

- 新增 `run::run_diagnosed()`：子进程里装 `SA_SIGINFO` 处理器，把 `si_addr` 经**管道**送回父进程
  （信号处理器里不能分配内存）；`Fault { signal, address, pc }` 让「signalled 11」变成可定位的原因。
- 期间修掉两个真 bug：
  1. **栈镜像放错位置**：原本放在栈区底部、`sp` 指向它，任何压栈（含信号处理器）都会写到映射区下方；
     现在镜像贴顶，向下留 1 MiB。
  2. **处理器退出码掩盖信号**：`_exit(128+signal)` 让父进程看到 `Exited(139)`，现在翻译回 `Signaled(11)`。
- 新增 3 项测试（共 **84 项全过**）：空指针来宾报 `address=0x0`、干净来宾不报故障、
  以及一个「在测试进程的 fork 子进程里处理器确实会跑」的隔离用例（证明不是测试框架的问题）。
- **真机实测故障地址**：真实来宾 `init` → `0x200000`；`/system/bin/toybox` → `0x35230`。
  两个都在很低的位置，与「`TPIDR_EL0` 仍指向宿主 TCB、来宾按自己偏移访问」一致——
  **TLS 是下一个必须实现的东西，现在有地址为证**。

## 阶段三十八：跳转执行打通（81 项，本机 bionic 实测）

- 新增 `tools/miniloader/src/run.rs`：`run_guest()` 在 **fork 出的子进程**里映射镜像、
  用**实际映射偏置**生成 auxv、把栈镜像写到 mmap 的 1 MiB 栈区（`apply_base` 把字符串指针
  绝对化）、`mov sp, x9; blr x10` 跳入；父进程 `waitpid` 并把状态翻译成 `Exited/Signaled`。
  在子进程里跑子进程，来宾的 `exit` 只结束它，崩掉的镜像也带不走加载器。
- 顺带修掉一个真 bug：auxv 原本在 fork **之前**用偏置 0 生成，于是 `AT_PHDR`/`AT_ENTRY` 全是错的；
  现在在映射之后按 `image.entry() - elf.entry` 算偏置再生。
- `tests/run.rs` 5 项（`cfg(target_arch = "aarch64")`，用 `cc` 现场汇编手写 AArch64 来宾）：
  `write("hi\n")+exit(42)` → `Exited(42)`；读 `[sp]` 取 argc → `Exited(3)`；`brk #7` → `Signaled(5)`；
  非 ELF 不 fork 直接 `Unloadable`；stdout 接管道能读到 `captured\n`。
- **本机 `cargo test` 合计 81 项全过。**
- 真实二进制：`toybox` 依赖 `8/8`、313 导入 `0 unresolved`、映射成功，但跳转后 **SIGSEGV**；
  真实来宾 `init` 同样 SIGSEGV。**共同原因：没有建立 TLS**（bionic 在 `__libc_init` 里要用
  `TPIDR_EL0` 指向线程控制块）——这是下一个要实现的。

## 阶段三十七：真正加载依赖库并合并符号（76 项，本机 bionic 实测）

- `SymbolTable` 增加 `open_libraries()` / `resolve_names()` / `has_library()` / `mark_opened()`，
  并**持有 dlopen 句柄**（Drop 时才关闭）——提前关掉会让表里的地址全部失效。
  - `dlopen(RTLD_NOW | RTLD_LOCAL)`：`RTLD_NOW` 让缺失依赖当场暴露，而不是来宾跑到一半才崩；
  - 解析顺序是**先已打开的库、再全局作用域**。
- `preflight` 的 `NeedsLibraries` 只列**没被打开**的库，打开了就不阻塞。
- CLI `--check` 先打开依赖、再解析导入，并打印 `libraries opened N/M`、`imports unresolved K`。
- **本机 bionic 实测（真实系统二进制）**：
  ```
  /system/bin/toybox          libraries opened 8/8   imports unresolved 0   loadable
  /system/bin/sh              libraries opened 1/1   imports unresolved 0   loadable
  /system/bin/app_process64   libraries opened 13/13 imports unresolved 0   loadable
  真实来宾 rootfs/init         needed=(static)                                loadable
  ```
  即 **313 个导入的真实 Android 二进制已被完整解析**。
- 新增 2 项测试：不存在的库不会被记为已打开；`mark_opened` 逐个满足依赖检查前/中/后三种状态。
- 仍未实现：**自己的 `DT_NEEDED` 映射器**（现在是借系统 `dlopen`）、TLS、`dlopen` 语义、跳转执行。

## 阶段三十六：`DT_NEEDED` 依赖清单（74 项，本机 bionic 实测）

- 新增 `symbols::needed_libraries()`：解析 `PT_DYNAMIC` 的 `DT_NEEDED`/`DT_STRTAB`/`DT_STRSZ`，
  按链接期地址在段表里定位字符串表，返回依赖清单；静态镜像返回空。
- `preflight` 增 `needed` 与 `NeedsLibraries` 阻塞项：**不再把「依赖库未加载」误报成「符号缺失」**；
  `--check` 多打印 `check: needed=…` 或 `(static)`。
- 本机（Android bionic aarch64）实测：
  - `/system/bin/toybox` → `libcrypto.so libz.so liblog.so libprocessgroup.so libselinux.so libc.so libm.so libdl.so`
  - 真实来宾 `rootfs/init` → `(static)`
  - `/system/bin/linker64` → `(static)`
- 新增 3 项测试（手工构造 `PT_DYNAMIC` fixture）：读依赖、静态镜像为空、动态镜像被 `NeedsLibraries` 阻塞。
- `cargo test` 在 bionic 上 **74 项全过**。
- 仍未实现：**真正加载这些库并合并符号表**（目前只如实报告会阻塞）。

## 阶段三十五：在真机（Termux = bionic aarch64）上验证，并跑通真实来宾 init

- 用户指出 Termux 本身就是 Android + bionic + aarch64，于是本机装了 Rust 1.100，
  **在目标环境**跑测试与诊断（比 Linux CI 更接近真实）。
- `cargo test` 在 bionic 上 **71 项全过**。关键修正：`transport`/`session` 原来写的是
  `cfg(target_os = "linux")`，在 Android 上被整组跳过——也就是 `socketpair`+`SCM_RIGHTS`
  从未在 bionic 上验证过。改成 `cfg(unix)` 后全部通过，**证明 fd 传递在 Android 上可用**。
- 真实二进制实测（本机输出）：
  - `/system/bin/toybox`：4 段、313 导入、294 可提供；缺 19 个（`gz*`/SELinux/`__android_log_write`）
  - `/system/bin/linker64`：**loadable**（4 段、0 重定位、0 导入）
  - `/system/lib64/libc.so`：1575 条 `R_AARCH64_RELATIVE`、507 导入全可提供
  - **真实来宾 `rootfs/init`**（从本机已安装的 APK 里取出）：**loadable**，2 段、0 重定位、
    **0 导入（静态链接）**；映射后入口字节 `e0 03 00 91`（`add x0, sp, #0`）
- 本机编译顺带抓出三个 CI 没暴露的错：`phnum` 是 `usize` 而字段要 `u16`（**编译失败**）、
  测试里 `AT_NULL` 位置算错、断言 `AT_BASE` 时把 kind 当 value。
- 新增能力：`stack::auxv_for()` 生成 `AT_PAGESZ/AT_PHDR/AT_PHENT/AT_PHNUM/AT_ENTRY/AT_BASE`，
  `Elf` 现在算出程序头表的虚拟地址（找不到就 `None`，不猜）。
- **新暴露的缺口**：`toybox` 那 19 个缺失说明我们**没有实现 `DT_NEEDED` 依赖库加载**；
  真实链接器会先加载 `libz/libselinux/liblog` 再解析符号，我们只在当前进程 `dlsym`。
  对静态的来宾 `init` 无影响，但对任何动态链接的来宾都会误判 blocked。

## 阶段三十四：一键设备取证脚本

- 新增 `tools/device_probe.sh`：一条命令把验证需要的信息全部收集到一个文件，避免来回问答。
  它**只读**——不安装、不清数据、不启动任何东西；除非显式给 `--loader`，否则连 push 都不做。
- 采集内容分八节：
  1. 设备身份（型号/SDK/ABI/内核）；
  2. root 与包状态（`id` / `su 0 id` / `versionName` / `primaryCpuAbi`）；
  3. 来宾数据布局（`rootfs`、`slots`、`dev/input`、`gfxstream-vulkan`）；
  4. **来宾 init 的 ELF 头**与 `build.prop` ——这决定镜像到底能不能装载；
  5. 应用自己记录的 `preflight.txt`、`log.txt`、`last_kmsg.txt`；
  6. 进程与最近崩溃（`logcat -b crash`）；
  7. 可选：推入自研 loader 诊断并跑 `--check` / `--map`（也对真实 `rootfs/init` 跑一次）；
  8. 报告里每节说明该怎么读。
- 支持 `-s SERIAL` 选设备、`--loader PATH` 附带二进制、`--dry-run` 只校验脚本、`--help` 看用法；
  失败不致命（记录 `(exited N)` 继续）。
- CI 把它纳入 host 回归：`bash -n` + `--dry-run` + `--help`，实测
  `dry run: script parses, adb is not touched`（run 45957695981 全绿；Android 构建同批通过）。

## 阶段三十三：设备可直接验证的两个产物（CI 自发布）

用户有设备，于是把两条验证路径都做成**可直接拿到的产物**：

1. **APK**：`android-build.yml` 新增 `publish` job（`workflow_dispatch` + 输入 `publish_release`）。
   它把本次构建的 APK **在 CI 里直接发成 Release**，避免再经本地转手 200 MB。
   - 手动触发：`POST /dispatches {"inputs":{"publish_release":true}}` → **204**，
     run 45955698212 **success**；
   - 产物：<https://github.com/ylh440104/twoyi-baseline/releases/tag/build-45955698212>，
     `twoyi_0.5.4-11270705-debug.apk`，216,526,703 字节，
     SHA-256 `387334ed83d06741d056d56444e48d2f5e3bad10b877163b6a5d18da162c395f`。
2. **loader 诊断二进制**：`miniloader-android.yml` 的 `android` job 现在把交叉编译出的
   `miniloader-plan`（aarch64-android）连同 `sha256.txt` 与 `HOWTO.txt` 一起传成 artifact
   `loader-android-cross-build`（1,047,259 字节），可直接 `adb push` 到设备运行。

**说明**：本地从 GitHub 拉 216 MB 反复中断（三次都在 ~20 MB 处断开），所以改成 CI 自发；
这不改变产物本身，只是分发路径更可靠。

## 阶段三十二：在 ARM runner 上原生跑测试 + 交叉编译到 Android（手动触发验证）

- 新增 `.github/workflows/miniloader-android.yml`，两个 job：
  - **`test`（`ubuntu-24.04-arm`）**：在 **AArch64 上原生**跑整套 68 项测试——映射、重定位、fd 传递
    现在都在来宾使用的架构上执行过，不只是 x86；并额外用一个本机 `cc` 编译的 aarch64 `_start`
    程序做端到端映射。实测（run 45950550060 的 `test` job）：
    ```
    aarch64
    entry: 0x40010c
    entry executable: true
    map 0x400000..0x401000 file=336 bss=0 prot=5
    mapped: entry=0xff9e33ba410c segments=1
    entry bytes: ff 43 00 d1
    test result: ok — 合计 68 项
    ```
  - **`android`（`ubuntu-22.04`）**：用 NDK 交叉编译到 `aarch64-linux-android`，证明这棵 crate
    也能对 bionic 编译。产物：
    ```
    ELF 64-bit LSB pie executable, ARM aarch64
    4166920 bytes
    Type: DYN (Position-Independent Executable file)  Machine: AArch64
    NEEDED: libdl.so, libc.so
    ```
- **手动触发验证**：`POST /actions/workflows/miniloader-android.yml/dispatches` → **204**，
  run 45951126912 `workflow_dispatch` **success**。
- 过程中两次失败都已修：
  1. NDK **没有** aarch64 宿主版本（r22 只发 `linux-x86_64`），把 `android` job 放回 x86_64，
     并改用真实的包名 `android-ndk-r22b-linux-x86_64`（run 45950045223）；
  2. 新版 Rust 链接 `-lunwind` 而 NDK 不提供 → 固定 **Rust 1.60.0**（与 app 原生库一致），通过
     （run 45950376996 → 修复后 45950550060）。
- 边界：`test` job 跑的是 **Linux glibc** 下的原生 ARM，不是 Android bionic；`android` job 只证明
  **能编译**，没有在设备上运行过。

## 阶段三十一：数据通路打通——从 fd 读出 ELF 并映射（68 项单测）

- `transport::read_all_fd(fd)`：`fstat` 取长度 + `pread` 循环读满，**不动文件偏移**
  （这样同一个描述符之后还能再用）；读不满或空文件直接报错。
- `session::load_from_attach(loader_fd, echo_a, echo_b, page, bridge)`：把官方 loader 的数据通路
  串成一次调用——`B1F2` 拿到描述符 → 从描述符读出 ELF → 解析 → 应用重定位/跳转槽 → 映射。
  返回 `(Attached, MappedImage)`；描述符**保持打开**，因为来宾之后还要用它
  （对应 `TWOYI_LINKER_BOOT_DATA_FD`）。
- 新增测试 `the_loader_reads_the_guest_from_the_descriptor_and_maps_it`：broker 把打开的 ELF
  （含 `ff 43 00 d1` 这类 AArch64 指令字节）的 fd 递过去，loader 侧真的读到并映射；
  断言**映射后的入口字节与文件里的 payload 完全一致**。
- CI 运行 45949181122 全绿：**68 项**；同批 host 回归与 Android 构建也通过。
- 边界：仍然没有「跳转执行」——映进来的是 AArch64 代码，CI 是 x86；也没有 TLS 与 `dlopen` 语义。

## 阶段三十：完整握手跑在真实 socketpair + fd 上（67 项单测）

- 新增 `tools/miniloader/src/session.rs`：把 `B1F2` → `TBD1` 的完整会话搬到 `socketpair` 上，
  **每端只用自己那一个描述符**（这版 API 从「传 `SocketPair`」改成「传自己的 fd」，
  因为一对 socket 不可能被两个线程同时拥有）。
  - `attach_over_socketpair` / `serve_attach`：请求不带 fd，**应答必须带 fd**，没带就报
    `MissingDescriptor`（不静默接受）；
  - `exec_over_socketpair` / `serve_exec`：校验序号、tag、`is_success()` 与后端位图，
    若应答意外夹带 fd 会**关掉它**而不是泄漏。
- `tests/session.rs` 4 项（真跑）：握手拿到的 fd **能 `pread` 出 broker 那份文件的内容**、
  broker 不给 fd 会被识别、attach 后紧接 exec 的完整会话、tag 不匹配被拒。
- CI 运行 45948537778 全绿：**67 项**（session 4 + transport 4 + preflight 6 + symbols 8 +
  bridge 6 + reloc 7 + executor 4 + image 4 + plan 8 + proto 6 + handshake 5 + resolver 5）。
- 失败记录（run 45948238005）：`libc::pread` 的 count 是 `size_t`，我按 `off_t` 传了 `i64`，
  编译直接不过；改成 `usize` 后通过。
- 边界：仍是 **CI 的 Linux**，不是 Android；真机上「broker 打开哪个 rootfs 并递出去」还没实现。

## 阶段二十九：真的传一个文件描述符（`SCM_RIGHTS`，63 项单测）

- 这段是整个握手里**唯一不能用字节流证明**的部分：官方 loader 的 `socketpair(1, 0x80005)`
  之后，第二次收发带的是**辅助数据里的一枚 fd**（`docs/LOADER_PROTOCOL.md` §1）。
- 新增 `tools/miniloader/src/transport.rs`：
  - `SocketPair::new()` 建 `socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_CLOEXEC)`，
    **与反汇编里的 `0x80005` 完全对应**；
  - `send_frame(fd, payload, Some(fd))` 把描述符放进 `SCM_RIGHTS` 一起发出；
  - `recv_frame(fd, max)` 取回数据**与随行的描述符**；
  - `dup_here(fd)` 复制描述符，`close_fd(fd)` 关闭收到的描述符。
- `tests/transport.rs` 4 项（Linux 上真跑）：无 fd 帧往返、**带 fd 的帧取回后能 `pread` 出原内容**
  （验证对方拿到的确实指向同一文件，不是编号巧合）、两帧各带各的 fd 且互不相同、
  `dup` 得到独立描述符且关掉原件不影响副本。
- CI 运行 45943103988 全绿：**63 项**（transport 4 + preflight 6 + symbols 8 + bridge 6 + reloc 7 +
  executor 4 + image 4 + plan 8 + proto 6 + handshake 5 + resolver 5）。
- 期间连挂三次都是我自己的类型问题，值得记下：
  1. `libc::CMSG_LEN`/`CMSG_SPACE` 是 `unsafe fn`，必须放 `unsafe` 块（run 45941532430）；
  2. `libc::msghdr` 用结构体字面量初始化在这版 `libc` 上编译不过，改成 `mem::zeroed()` 再逐字段赋值；
  3. 测试导入 `miniloader::transport::dup_here` 但当时只把它做在 `SocketPair` 的关联函数里，
     补了同名自由函数（run 45942127565）。
- 边界：**只在 CI 的 Linux 上验证过**；Android 的 `SOCK_SEQPACKET` 行为、以及真机上
  「谁来提供这枚 fd」都还没跑过。

## 阶段二十八：体检结论落盘并进入诊断包（host 用例 + 构建通过）

- 新增 `GuestPreflightNote.java`（纯 Java，`android.` 与 `R.string` 计数均为 0）：把体检结论写进
  `cacheDir/preflight.txt`，一行一条且**稳定可 grep**：`时间 判定 路径 详情`；
  - 换行/制表/控制字符被压成 `_`，保证「一行一条」的格式不被破坏；
  - 最多保留 **12 行**，写入走 `.tmp` + rename，崩溃不会留半截文件，也不会残留临时文件。
- `TwinRuntime.startPhone()` 在激活实例前记录判定（`OK` 也记），这样**启动失败**至少留下原因；
  `LogEvents.getBugreport()` 把这个文件加入诊断包，点「发送日志」就能带走。
- `tools/GuestPreflightNoteTest.java`：格式、清洗、12 行上限（写 24 条后确认最旧被丢、最新在末行）、
  `.tmp` 不残留、嵌套目录自动创建、拒绝空目标/空行，并断言源码里**没有任何 Android 依赖**。
- CI：host 回归 45938735603 全绿（boot-state 共 **20 条 PASS**），其中
  `PASS: guest preflight record is one line, bounded and crash safe`；
  Android 构建 45938735628 通过。首次失败（45938194224）是测试里 `expectThrows(Runnable…)`
  吞不掉 `IOException`，改成自定义 `IOAction` 后通过。

## 阶段二十七：Android 侧的来宾镜像体检（host 用例 + 构建通过）

- 新增 `GuestRomPreflight.java`（**纯 Java，无任何 Android 类型**）：读来宾 `rootfs/init`
  的 ELF 头，给出结构化结论——`MISSING / NOT_ELF / WRONG_CLASS / WRONG_MACHINE /
  ENTRY_NOT_EXECUTABLE / DYNAMICALLY_LINKED / OK`。
  这样「启动失败」会变成「系统镜像是 32 位」「init 不是可执行程序」「入口不可执行」这类**具体原因**。
- `TwinRuntime.startPhone()` 在激活实例之前先跑这个体检，不合格就用对应文案返回给界面；
  判定到文案的映射放在 UI 层（`TwinRuntime`），类本身保持可在宿主机编译。
- 新增 `tools/GuestRomPreflightTest.java` 7 个用例（缺失、脚本、32 位、x86-64、入口不可执行、
  PT_DYNAMIC、正常），并**额外断言源码里不出现 `android.` / `R.string`**——防止以后有人把
  Android 依赖加回来、让宿主机跑不动。
- CI：host 回归 45937511165 全绿，`PASS: guest init preflight names the structural reason it cannot load`；
  Android 构建同批通过。
- 期间抓出两个我自己的错：类里原本引用了 `R.string`/`android.os.Build` 导致宿主机编译不了
  （run 45936951718），以及测试 fixture 把 entry 和段基址传错位、导致「正常镜像」被判不合格。
  前者改成纯 Java，后者修正 fixture 后核对过四种判定的等价推导。

## 阶段二十六：装载前判定 preflight（59 项单测）

- 新增 `tools/miniloader/src/preflight.rs`：把所有「装载前就知道的事」汇成一个判定——
  段布局与预留跨度、`R_AARCH64_RELATIVE` 数量、导入清单（强/弱 + 宿主能否提供）、
  以及入口是否落在可执行段。`Preflight::blockers` 逐条给出**可读原因**
  （`required host libc bridge symbol is unavailable: malloc` 等），`reason()` 拼成一行。
- 判定规则与真实动态链接器一致：**强导入缺失 → 阻塞**；**弱导入缺失 → 不阻塞**（会成为 0）。
- CLI 新增 `--check`：打印 `check: loadable|blocked` 与 `segments/reserved/relocations/imports`，
  被阻塞时逐条打印 `check blocker:`，并以状态码 3 退出。
- `tests/preflight.rs` 6 项：导入全部可服务即可装载、强导入缺失被阻塞、弱导入缺失不阻塞、
  入口在可执行段外被阻塞、重复导入只报一次、绑定分类可知。
- CI 全绿（run 45936286623，**59 项**）：真实二进制实测两例——
  ```
  check: loadable
  check: segments=1 reserved=4096 relocations=0 imports=0        # -static -nostdlib
  check: loadable
  check: segments=2 reserved=73728 relocations=4 imports=8        # 动态链接，4 条 RELATIVE、8 个导入
  ```
- 期间被 CI 抓出两个我自己的错：
  1. 测试 fixture 的段只给了 `R|W` 没有 `X`，preflight 正确地把它判为「入口不可执行」——
     是 fixture 错了，不是判定错了（run 45935298427）；
  2. `--check` 只拿 `KNOWN_REQUIRED` 填桥，导致**本可装载**的动态程序被判 blocked
     （run 45935773573 输出 6 条 missing）。改为用**来宾自己声明的导入名**去问桥后，
     同一程序变成 `check: loadable`（run 45936286623）。
- 边界：判定用的是**宿主的 glibc 符号**，真机上要换成 bionic；仍未在设备上跑过。

## 阶段二十五：真实 aarch64 动态导入 + 弱符号修正（53 项单测）

- CLI 新增导入报告：对给的 ELF 列出 `JUMP_SLOT` 数量、去重名字数、本宿主能提供几个，
  以及**缺哪些**；能提供的名字直接列出来。
- CI 里额外交叉编译一个**动态链接**的 aarch64 程序（`malloc`/`snprintf`/`puts`/`free`），
  让桥去面对真实导入表。实测输出（run 45934239931）：
  ```
  imports: 8 slot(s), 8 distinct name(s), 7 servable here
  imports servable: __libc_start_main __cxa_finalize __snprintf_chk malloc abort puts free
  imports weak (may stay zero): __gmon_start__
  ```
- **这条证据立刻抓出一个真 bug**：`__gmon_start__` 是**弱未定义符号**，真实动态链接器允许它
  解析为 0；而我的 `apply_jump_slots` 把任何未解析名字都当致命错误，等于**拒绝加载一个正常的程序**。
  修法：
  - `DynSym` 增加 `binding`（`STB_LOCAL/GLOBAL/WEAK/OTHER`，来自 `st_info` 高 4 位），
    `JumpSlot` 增加 `weak`；
  - `apply_jump_slots` 与 executor 的补丁循环都改为「**弱未解析 → 写 0**，强未解析 → 报错」；
  - CLI 把弱符号缺口单独列为「may stay zero」，避免和真正阻塞的缺失混为一谈。
- 新增 2 项测试：`the_binding_is_recorded`（GLOBAL/WEAK 被正确读出）、
  `a_weak_import_may_stay_unresolved`（弱导入未解析时写入 0 且**不**让加载失败）。
- CI 全绿：**53 项**（symbols 8 + bridge 6 + reloc 7 + executor 4 + image 4 + plan 8 + proto 6 +
  handshake 5 + resolver 5），运行 45934239931；host 回归 45934239938 亦通过。
- 边界：`__snprintf_chk` 这类 fortify 符号说明宿主提供的是 **glibc 的名字**，不是 bionic；
  真机上桥必须换成 bionic 的符号集，这一点没变。

## 阶段二十四：宿主符号桥（51 项单测）

- 新增 `tools/miniloader/src/bridge.rs`：`SymbolTable` 是「名字 → 地址」的显式表，
  - `insert()` 拒绝空名与 **0 地址**（把槽填成 0 只会让来宾跳到空指针，比加载失败更难查）；
  - `missing(required)` / `require_all()` 给出缺哪些必需符号，错误文案与官方 loader 的
    `required host libc bridge symbols are unavailable` 一致；
  - `from_current_process(names)` 用 `dlsym(RTLD_DEFAULT, …)` 从**当前进程**取真实地址，缺失的名字
    保持缺失而不是编造；
  - `KNOWN_REQUIRED` 只列有直接证据的名字（`docs/LOADER_DECODE.md` 里解出的
    `android_set_application_target_sdk_version`、`__android_log_print`、`dlopen/dlsym/…`）。
- `MappedImage::map_with_bridge(elf, page, relocate, Some(&table))`：在**可写阶段**（重定位之后、
  `mprotect` 收窄之前）按 `JUMP_SLOT` 打补丁；任何未解析的导入直接让**加载失败**并报出名字。
- `tests/bridge.rs` 6 项：拒绝空名/0 地址、缺失清单与 `require_all`、从当前进程取到
  `malloc/free/memcpy` 且不存在名字保持缺失、**映射后的槽真的持有桥给出的地址**、
  未解析导入令加载失败、无桥时槽保持原样。
- CI 运行 45932259675 全绿：**51 项**（bridge 6 + symbols 6 + reloc 7 + executor 4 + image 4 +
  plan 8 + proto 6 + handshake 5 + resolver 5）；同批 host 回归 45932259730、Android 构建 45932259731 均通过。
- 边界：桥只做**查表**，没有实现 bionic 语义（`__android_log_print` 等要宿主真正提供实现）；
  也没有在设备上验证过任何一次真实解析。

## 阶段二十三：动态符号与跳转槽（45 项单测）

- 新增 `tools/miniloader/src/symbols.rs`：
  - `dynamic_symbols()`：读 `.dynsym` + 其 `sh_link` 指向的 `.dynstr`，给出名字、值、大小与
    `undefined`（`SHN_UNDEF`）标记——来宾的外部导入就靠这个列表暴露出来；
  - `jump_slots()`：读 `SHT_RELA` 里 type **1026**（`R_AARCH64_JUMP_SLOT`）的条目，
    按 `r_info >> 32` 取符号、从 `.dynstr` 取名，得到「槽地址 → 需要解析的名字」；
  - `apply_jump_slots()`：用调用方给的 `resolve(name)` 打补丁；**未解析的导入直接报错**，
    而不是把槽填成 0 让来宾跳到空地址。
- `tests/symbols.rs` 6 项，fixture 是手工构造的 `.dynstr` + `.dynsym` + `.rela.plt`（真实节布局）：
  `reads_dynamic_symbols`、`reads_jump_slots_with_their_names`、`patches_slots_through_the_resolver`、
  `an_unresolved_import_is_reported`、`a_rejected_write_is_reported`、`a_symbol_free_image_reads_as_empty`。
- CI 运行 45931681661 全绿：**45 项**（symbols 6 + reloc 7 + executor 4 + image 4 + plan 8 +
  proto 6 + handshake 5 + resolver 5）。host 回归 45931681701 也绿。
- 边界：**解析器本身没实现**——宿主 libc bridge 的符号表还没有，`resolve` 目前只能由调用方注入；
  而且 executor 尚未在映射流程里调用 `apply_jump_slots`（下一步接）。

## 阶段二十二：R_AARCH64_RELATIVE 重定位（39 项单测）

- 新增 `tools/miniloader/src/reloc.rs`：
  - `relative_relocations()` 解析节头表里的 `SHT_RELA` 段（`.rela.dyn`/`.rela.plt`），
    只保留 type `1027`（`R_AARCH64_RELATIVE`），其它类型（如 `JUMP_SLOT` 1026）忽略；
  - `apply()` 在 `bias + r_offset` 处写入 `bias + r_addend`，写入被拒绝时报错；
  - 没有节头表（被 strip 的镜像）时返回空列表，不视为错误。
- `MappedImage::map()` 改为两趟：先逐段可写并拷贝，**再应用重定位**，**最后**统一 `mprotect`
  收窄权限——这正是官方 loader 的顺序（重定位必须发生在可写阶段）。
  新增 `map_with_relocations(elf, page, false)` 用于对照。
- `tests/reloc.rs` 7 项：解析 `.rela.dyn`、忽略非 relative 类型、无节头表返回空、
  `bias + addend`（含负 addend）计算、写入被拒、**映射后槽位确实持有 `bias + addend`**、
  跳过重定位时槽位保持原样。
- CI 运行 45931123630 全绿：**39 项**（reloc 7 + executor 4 + image 4 + plan 8 + proto 6 + handshake 5 + resolver 5）。
  真实 AArch64 二进制映射结果不变：`mapped: entry=0x7f59d997610c`、`entry bytes: ff 43 00 d1`；
  该二进制没有动态重定位（`relocations: 0`），符合它是 `-static -nostdlib` 的事实。
- 首次失败（45930673459）是个真 bug：我用 `r_offset` 直接加到**映射基址**上，而 `r_offset` 是
  链接期虚拟地址，必须减去**预留跨度起点**；`the_mapped_image_holds_rebased_pointers` 把它抓了出来。
- 边界：只实现 `R_AARCH64_RELATIVE`；`JUMP_SLOT`、符号重定位、TLS 都还没做。

## 阶段二十一：真的 mmap（32 项单测 + 真实 ELF 映射成功）

- 新增 `tools/miniloader/src/executor.rs`：`MappedImage::map(elf, page)` 走官方 loader 同一套序列——
  一次 `PROT_NONE` 预留整段 → 逐段改成可写、`copy_nonoverlapping` 拷文件字节 →
  `mprotect` 成该段自己的权限；`Drop` 里 `munmap`。失败路径会先 `munmap` 再返回错误，不留泄漏。
  新增依赖 `libc = "=0.2.126"`（对应 1.60 MSRV）。
- 新增 `tests/executor.rs` 4 项：映射后按虚拟地址读回的文件字节与源一致、bss 尾部读为 0、
  两段各自权限正确（`prot=5` / `prot=6`）、越界读返回 None、64 次映射-释放循环不耗尽地址空间。
- **真实 AArch64 ELF 在 CI 里被真的映射进进程**（run 45929873394）：
  ```
  mapped: entry=0x7fad3408b10c segments=1
  mapped[0] addr=0x7fad3408b000 file=336 mem=336 prot=5
  entry bytes: ff 43 00 d1
  ```
  `0x7fad3408b10c − 0x7fad3408b000 = 0x10c = 268`，与干跑镜像的 `entry_offset=Some(268)` 一致；
  入口字节 `ff 43 00 d1` 正是 AArch64 的 `sub sp, sp, #0x10`（编译器为 `_start` 生成的第一条指令），
  说明拷进去的确实是那个二进制。
- 测试合计 **32 项**（executor 4 + image 4 + plan 8 + proto 6 + handshake 5 + resolver 5）。
  首次失败（45929489813）是两个类型问题：`MapError → ExecError` 缺 `From`、`prot` 是 `u32` 而
  `libc::mprotect` 要 `i32`，都已修。
- 边界：**没有跳转执行**（映进来的是 AArch64 代码，CI 是 x86），也没有重定位与握手。

## 阶段二十轮：把段真正摆进内存（干跑镜像，28 项单测 + 真实 ELF）

- 新增 `tools/miniloader/src/image.rs`：`GuestImage::load(elf, page)` 按映射计划把每个 `PT_LOAD`
  摆进一块 **arena**（长度 = 预留跨度），复制文件字节、bss 尾部保持 0，并给出
  `entry_offset()`、`entry_segment()`、`read_vaddr(addr, len)`。
  这是真 `mmap` 之前的**干跑**：布局、拷贝偏移、清零都能在任意宿主上验证。
- 新增 `tests/image.rs` 4 项：段落到页对齐地址、文件字节逐字节一致、bss 全 0、
  按虚拟地址读回同样字节、越界段被拒、entry 在镜像外时无偏移。
- 真实 ELF 端到端：CI 用交叉编译的 aarch64 `_start` 程序跑 CLI，输出
  `image: base=0x400000 arena=4096 bytes entry_offset=Some(268)`
  —— entry `0x40010c` 正好是 base 之后第 268 字节，与 `readelf` 的入口一致。
- 证据：run 45928406023 全绿（image 4 + plan 8 + proto 6 + handshake 5 + resolver 5 = 28 项），
  其中 `test segments_are_placed_and_file_bytes_copied ... ok`、
  `test addresses_are_page_aligned_and_bias_is_respected ... ok`。
  首次失败（45928051472）只是 `PROT_EXEC` 未使用的 import，已删。
- 边界：**仍然是内存里的字节，不是真的 mmap**；没有重定位、没有跳转、没有握手。

## 阶段二第九轮：用真实 aarch64 ELF 验证映射计划（CI 通过）

- 之前 `miniloader-plan` 只在**合成字节**和一次「x86 二进制被干净拒绝」上验证过，
  等于没跑过真正的 AArch64 输入。本轮补上：CI 里用 `gcc-aarch64-linux-gnu`
  编译一个 freestanding `_start` 程序（`-nostdlib -static`），再让规划器处理它。
- 运行 45927604557 的实测输出：
  ```
  entry: 0x40010c
  entry executable: true
  seg[0] vaddr=0x400000 filesz=336 memsz=336 bss=0 flags=0x5
  reserve: 0x400000..0x401000 (4096 bytes, page 4096)
  map 0x400000..0x401000 file=336 bss=0 prot=5
  initial stack: 160 bytes
  ```
  `prot=5` = `PROT_READ|PROT_EXEC`，与 `flags=0x5`（`PF_R|PF_X`）一致；
  预留区间按 4 KiB 页对齐；初始栈 160 字节且通过 16 字节对齐检查。
- 断言写在 workflow 里（`entry executable: true`、`reserve:`、`map 0x`、`prot=5/7`、`initial stack:`），
  任何一条不满足就红。
- 边界：这只证明**解析与规划**正确，没有真正 `mmap`、没有跳转、没有握手。

## 阶段三轮（续八）：来宾相位显示与「复用已运行会话」（构建通过）

- `TwinRuntime` 新增 `guestPhase()`：把 `TwoyiStatusManager.phase()` 的
  `STOPPED/STARTING/RUNNING/FAILED` 暴露成界面能用的枚举；新增 `reopenPhone()`。
- 关键修正：**已经跑起来的来宾不能被再次「启动」**。之前 Phone 页不管状态都走 `startPhone`，
  而 `startPhone` 会过 `requireStartable` 再激活并拉新窗口——对运行中的会话是错的。
  现在 `RUNNING/STARTING` 走 `reopenPhone()`（只拉窗口），只有停止态才走启动路径。
- 界面：Phone 页按相位显示「启动中 / 正在执行 / 启动失败 / 准备中」；设置页的「孪生使用的手机」
  一行改为显示实时相位；主界面每秒读一次相位（`LaunchedEffect` + `delay`）。
- 新增依赖 `kotlinx-coroutines-android:1.6.0`（`delay` 用），显式声明而不是靠传递依赖。
- 证据：Android 构建 run 45926222423 通过；host 回归 run 45926222264 通过。
- 边界：相位来自进程内状态机，**真机上没人验证过**「启动中→运行」是否真的会发生；
  界面只反映它，不制造它。

## 阶段三轮（续七）：任务直播状态与三个控件（host 用例 + 构建通过）

- 新增 `TwinTaskState.java`（纯逻辑，宿主可测）：九个阶段
  `IDLE/QUEUED/PREPARING/RUNNING/PAUSED/STOPPING/USER_IN_CONTROL/DONE/FAILED`，
  以及「谁能做什么」的守卫：
  - 只有 `RUNNING` 能暂停、只有 `PAUSED` 能继续；
  - 停止只对 `RUNNING/PAUSED/PREPARING` 开放，且先进入 `STOPPING`，到步骤边界才交棒 `USER_IN_CONTROL`；
  - 接管只在 `RUNNING/PAUSED` 可用；交回后回到 `RUNNING`；
  - `DONE/FAILED` 是终态且不能被覆盖；新任务只能从 `IDLE/DONE/FAILED` 开始。
- 界面：Twin 页输入区上方新增**状态条**，显示当前阶段文案并提供暂停/继续、停止、接管、交回按钮
  （按钮出现与否完全由上面的守卫决定，不是写死的）。
- 证据：run 45925201051 的 `PASS: twin task state guards pause, stop and hand-back`
  （同批还有密钥信封、模型目录、表单规则三条 PASS）；Android 构建 run 45925201033 通过。
- 边界：**没有任务引擎**——发消息时把状态直接推进到 `RUNNING` 以便演练控件，
  真实的 `QUEUED→PREPARING→…` 由将来的运行时驱动。这是刻意的占位，不是实现完成。

## 阶段三轮（续六）：密钥只留在本机（host 用例 + 构建通过）

- 最新版承诺「密钥只保存在本机」，所以连接不再只存一个 model 名：
  - `TwinSecretFormat.java`（纯逻辑，宿主可测）：把 GCM nonce + 密文打包成 `v1:<base64>` 信封，
    校验 nonce 必须 12 字节、密文至少一个 16 字节 tag；自带小 base64 以便脱离 Android 测试。
  - `TwinSecretStore.kt`：Android Keystore AES-256/GCM（`AndroidKeyStore`，别名 `twin-provider-key`），
    `seal`/`open` 失败返回 null 而不是抛出。
  - `TwinRuntime`：保存 baseUrl + model + 密钥信封；`providerConfig()` 读回时解密；
    封不上就返回 false，界面显示 `twin_key_save_failed` 而不是假装成功。
  - 设置页的「模型」一行现在同时显示模型名与 API 地址。
- 证据：run 45924216077 的 `PASS: provider secret envelope packs a nonce and ciphertext safely`
  （同批 `PASS: model catalog ...`、`PASS: provider form rules ...` 仍在）；
  Android 构建 run 45924215995 通过。
- 边界：Keystore 本身**无法在 CI 里跑**（需要 Android 运行时），所以只验证了信封格式与纯 Java 的
  base64 往返；加解密在真机上仍未验证。

## 阶段三轮（续五）：模型列表真的会去拉（host 用例 + 构建通过）

- 新增 `TwinModelCatalog.java`（纯逻辑，宿主可测）：
  - `modelsUrl()`：拼 `/models`，处理尾斜杠、双斜杠、空白、null；
  - `classify()`：2xx → OK、401/403 → BAD_KEY、404 → NOT_FOUND、其余 → SERVER；
  - `parseModelIds()`：从 OpenAI 风格 `{"data":[{"id":...}]}` 里取 id，处理任意空白、转义引号、去重；
  - `containsErrorObject()` / `errorMessage()`：识别 `{"error":{...}}` 并取出 `message`。
- 新增 `TwinModelService.kt`：`HttpURLConnection` GET，`Authorization: Bearer`，15 s 超时，
  把结果映射到既有文案；错误体也读出来作为 detail。
- 界面：连接页「获取模型」现在真的发请求，成功后把模型列成可点选的按钮（选中带 ✓），
  失败显示 `twin_key_invalid` / `twin_network_error` / `twin_parse_error` 等；请求中按钮禁用并显示
  `twin_provider_fetching_models`。
- 证据：run 45923544357 的 `PASS: model catalog URL, status mapping and JSON reading`；
  Android 构建 run 45923544344 通过。
- 边界：**网络请求本身没有在 CI 里跑过**（无外网凭据），只验证了 URL/状态码/JSON 三类纯逻辑；
  真实服务商的响应差异仍需真机或人工验证。

## 阶段三轮（续四）：模型连接表单的本地校验（host 用例 + 构建通过）

- 新增 `TwinProviderRules.java`（不含 Android 类型，可在宿主机跑）：三类本地拒绝——
  key 为空（`REQUIRED`）、key 含空格/换行/全角或非 ASCII（`BAD_CHARS`）、
  URL 非 http(s) 或畸形（`NOT_HTTP`/`MALFORMED`），另有模型名两端不得有空白的规则。
- 新增 `tools/TwinProviderRulesTest.java`，并入 host 回归：覆盖 null/空白 key、空格、换行、
  全角、中文、超长 key；缺 scheme、错 scheme、只有 scheme、空 host、非数字端口、host 含空格；
  模型名首尾空格；以及 `canConnect` 的组合判断。
- 证据：run 45922802725 的 `PASS: provider form rules reject empty, non-ASCII and non-http input`；
  Android 构建 run 45922802712 通过。
- 界面：连接页在提交时逐项校验并显示对应文案（`twin_api_key_required`、`twin_api_key_bad_chars`、
  `twin_base_url_invalid`），全部通过后才写入偏好并返回。
- 边界：只做**本地**规则；服务端拒绝（`twin_key_invalid`、`twin_model_not_found`）要等真正的网络请求。

## 阶段三轮（续三）：确认回合（构建通过）

- 会话消息从 `Pair<String, Boolean>` 改成三种 `Line`：`User`、`Twin`、`Need`。
  `Need` 是最新版区别于普通聊天的核心交互：先说清后果，再等用户回答。
- 确认卡片的行为（文案全部来自最新版同名键）：
  - 标题用 `twin_need_tap_question`（带 `%1$s`），下面按动作类型显示后果：
    删除 → `twin_need_consequence_delete`、付款 → `_pay`、发送/发布 → `_send`；
  - 三个出口：同意（`twin_need_accept` → 记为 `twin_need_row_accepted`）、
    拒绝（`twin_need_reject` → `twin_need_row_declined`）、
    「去手机」交给用户处理（`twin_need_go_phone` → 打开来宾 → `twin_need_row_handled`）；
  - 有未答的确认时，输入区上方显示 `twin_need_waiting`。
- CI：45921932368 构建通过、45921932420 host 回归通过。首次失败（45921184948）是
  在 `onClick` 里调 `stringResource(...)` —— Compose 不允许；改为把模板 id 与参数存进 `Line.Need`，
  在卡片里格式化后通过。
- 边界：**触发确认的是本地关键词判断**（delete/pay/send 及其中文），不是模型；真实行为要等模型接入。

## 阶段三轮（续二）：设置页与记忆页（构建通过）

- 新增两个界面，文案取自最新版同名键（中英各 57 条新增，资源总数 187 / 184）：
  - **设置页**：按最新版分组（Twin / 孪生手机 / 本机 / 帮助），模型状态、三个开关
    （查看屏幕、敏感输入保护、风险操作前询问）、记忆入口、手机模式与「未创建/未开机」的条件提示；
  - **记忆页**：列出本地记忆、单条删除、全部清除（带二次确认文案）、底部说明。
- `TwinRuntime` 增加偏好读写：`isFlag/setFlag`、`memories/remember/forget/forgetAll`，
  键名常量 `FLAG_SEE_SCREEN`/`FLAG_SENSITIVE_GUARD`/`FLAG_CONFIRM_RISKY`。
- CI：45919875872 构建通过。首次构建（45919195413）因资源里的裸撇号被 aapt2 拒绝
  （`Invalid unicode escape sequence`），改为 `\'` 转义后通过。
- 边界：开关只落本地偏好、尚未影响任何运行时行为；记忆只存在本地，未参与对话。

## 阶段三轮（续）：Phone 页可以真正打开来宾（构建通过）

- `TwinRuntime.startPhone()`：`GuestInstanceGate.requireStartable` 先挡（`UNKNOWN_INSTANCE`/`NO_ROM` 直接返回原因）
  → `registry.activate(id)` + `save()` → 启动 `Render2Activity`。
  激活必须在启动之前：来宾进程是按**活动实例**解析槽位的。
- Phone 页新增「全屏使用手机」按钮：仅当 `phoneReady && !busy` 可点；失败把原因显示在页面上。
- CI：45918461523 构建通过、45918461505 host 回归通过（路径守卫 PASS）。
- 边界：这条链路只是**把已有启动路径接上界面**，没有改启动本身；真机上是否真能进画面仍未验证
  （需要设备；见 `docs/STAGE1_STATUS.md` 的空缺项）。

## 阶段三轮：界面接到真实实例状态（构建通过）

- 新增 `ui/twin/TwinRuntime.kt`：界面的**唯一**状态来源，不再靠布尔占位。
  - `phoneState()`：读 `GuestInstanceRegistry` → `GuestInstanceGate.evaluate`，把
    `UNKNOWN_INSTANCE / NO_ROM / STARTABLE` 映射成「未创建 / 已创建但无系统 / 可用」；
  - `createPhone()`：`GuestInstanceManager.create` → `activate` → `GuestInstanceInstaller.installBundledRom`，
    在后台线程执行，失败把 `message` 带回界面；
  - 模型连接用 `SharedPreferences` 持久化（`provider_connected` 与 `provider_label`）。
- 界面改动：两步入场的「创建」与 Phone 页的「创建手机」都走 `createPhone`，按钮在忙时禁用并显示
  `twin_boot_preparing`；连接模型写入偏好后回到主界面；「已完成」状态来自磁盘而非内存。
- CI：45917912498 构建通过（先前 45916703367 因 `BootScreen` 缺 `LocalContext` 失败，已修）；
  host 回归 45917912505 通过（路径守卫仍 PASS）。
- 明确边界：**还没有启动来宾**——创建只到「装好系统」这一步，`Twin` 页回复仍是占位串，
  也没有渲染 Surface。下一步才是把启动链路接上。

## 阶段三首轮：Compose 界面成型（构建通过）

- 把 `TwinActivity.kt` 从「一张占位卡片」扩成**四个界面**：
  1. **两步入场**（先建设备、再连模型），完成状态各自有「已完成」文案；
  2. **Twin** 页：空态提示 + 消息列表 + 输入区（未就绪时输入被禁用并给出原因）；
  3. **Phone** 页：无设备空态 + 创建入口；
  4. **模型连接**页：API 地址 / Key / 模型三项 + 「获取模型」「连接」。
- 文案取自最新版资源表的同名键（`twin_boot_*`、`twin_phone_*`、`twin_provider_*`、`twin_agent_*`），
  中英各 43 条；新增 `docs/TWIN_UI_MAP.md`：把 285 条 `twin_*` 按前缀分成 64 组，作为后续界面复刻的蓝图。
- CI 运行 45916035367 构建通过（先前 45915427818 因 `foundation.layout.weight` 的非法 import 失败，已删）。
- 明确边界：**界面目前不驱动真实来宾**——`Twin` 页的回复是占位串（"The Twin connection is unavailable."），
  创建设备/连接模型只改本地状态，不落盘。这些要等运行时接上。

## 阶段一：JNI 注册表的静态恢复（本轮，负结论）

- 目标：拿到 `nativeResolve` 的函数地址，进而读 988 字节布局。
- 手段：新写 `tools/ghidra/find_jni_table.py`，解析 ELF64 + 解开 `R_AARCH64_RELATIVE`，
  扫描「指向方法名字符串的指针」，按 `{name, sig, fn}` 还原 `RegisterNatives` 表。
- 结果（run 45915015830）：`nativeHandle`、`native-fence` 字符串存在，但 **`nativeResolve` 等 9 个名字
  在 `.rodata` 里根本不存在**；`R_AARCH64_RELATIVE` 为 0 条；没有任何数据指针指向这些字符串。
- 结论：注册表不在静态数据里（名称运行时拼装 / 表在 `.bss` 由构造器写入 / 走自定义绑定）。
  **静态路线到此为止**，下一步只能是反编译 `JNI_OnLoad` 找它传给 `RegisterNatives` 的第三个参数，
  或真机动态挂钩。
- 工具保留：`find_jni_table.py` 对将来任何「静态表」的 so 都可一条命令列出全部注册项。

## 阶段一补漏：camera opcode 由 smali 解出（本轮）

- 起因：上一版 `HAL_PROTOCOL.md` 把 camera opcode 列为空缺，理由是 jadx 跳过了两个方法。
  换手段就能拿到——**apktool 解 smali** 不丢方法体。
- 新增 `.github/workflows/smali-focus.yml`：只解 smali（`--no-res`），定位
  `io/twoyi/hal/g0.smali`（4027 行）与 `a8/d2.smali`（9794 行），抽全部 `const` 与 write 上下文，
  并把两个完整 smali 作为 artifact 上传供离线阅读。
- **实证结果**（run 45914008675，`g0.smali` 的 `g(f0,i,OutputStream)`）：
  - 请求 opcode 来自来宾（`iget v0, v3, Lio/twoyi/hal/i;->a:I`），**不是宿主常量**；
  - 宿主分支覆盖 **2 / 3 / 4 / 5 / 6**，未知值走 fallthrough 仍写应答，不报错；
  - 应答 opcode = 请求 `| 0x8000`（`const v2, 0x8000` + `or-int/2addr`，同方法三处）。
- **同时纠错**：上一版把 `a8.d2` 当成 camera 目录 payload 的来源是误判——`d2.smali` 是混淆过的 UI 类
  （含 `EditText`/`Parcel`/`AttributeSet`），与 camera 无关。已在文档里写明更正。
- `docs/STAGE1_STATUS.md` 与 `docs/HAL_PROTOCOL.md` §7/§10 同步更新；剩下的 camera 空缺收紧为
  「opcode 2..6 的语义」与「目录 payload 偏移」。

## 阶段二第八轮：resolver 信封（新增 5 项，合计 24 项）

- 新增 `tools/miniloader/src/resolver.rs`：把 `docs/HAL_PROTOCOL.md` §5 里 Java 层的硬事实落成代码——
  请求**定长 360**、应答**严格 988**（`nativeResolve` 返回值长度不等于 988 就抛 `Invalid resolver response`）、
  一问一答一连接。`serve_once` 在写之前就校验长度，`request_once` 要求读满 988。
- 新增 `tests/resolver.rs` 5 项：全量会话、短请求报 `ShortRequest{got:100}` 而非作答、987 与 989 字节应答都被拒、
  长度常量 360/988 钉死。
- CI 运行 45913642626 全绿：handshake 5 + plan 8 + proto 6 + resolver 5 = **24 项**。
- 同时新增 `docs/STAGE1_STATUS.md`：把阶段一每一项按**实证 / 推断 / 空缺**三档列出，并给出各自证据来源。
  结论一句话：loader 的**请求**侧是指令流级实证，**应答**侧是推断；`libtwoyi.so` 的 Java 层实证、native 层空缺。
- 另加 `.github/workflows/guest-capture.yml`（只在专用设备 runner 上跑）：装好即可用于把推断变事实，
  目前无设备，所以它是**未运行**的工具而非证据。

## 阶段二第七轮：`B1F2` 应答被过度简化（本轮纠错，无新代码）

- 复核 `FUN_00116520` 的调用序列时发现，我之前把应答读成「魔数/版本/tag/序号/status/flags/保留」，
  漏掉了 `FUN_00117758` 接收侧的第 4 个参数语义：那个槽**非负时是 SCM_RIGHTS 送来的 fd**。
- 因此应答偏移 12 是**双关槽**：成功时是 fd，失败时（-1）才是 errno；伪 C 里那一长串
  `bVar1..bVar10` 的布尔判断，其实就是 Ghidra 把 `fd >= 0 && fstat(fd)==0 && st_size > 0` 展开成视图的产物，
  源码逻辑本身很短。
- 偏移 20 也不是「保留」：它是后端能力位，`0xf` 是特殊后端值，且此时 bit0 必须为 0。
- 已改 `docs/LOADER_PROTOCOL.md` §8 的应答表，并新增 §9 说明这个双关槽与匿名后端位图。
- 本轮**没有新增测试**（仍 19 项），因为这是文档语义修正，编码布局未变。下一轮要把「偏移 12 携带 fd」
  写成回归用例，并明确标注它仍是**静态推断**、待真机 `recvmsg` 抓包证实。

## 阶段二第六轮：`TBD1` 布局反汇编定案（19 项单测全绿）

- 伪 C 的栈槽类型矛盾无法闭环，改为**直接读指令流**：新增 `.github/workflows/disasm-focus.yml`，
  用 `aarch64-linux-gnu-objdump` 反汇编 `FUN_00116df4`。
- 首跑失败：宿主 `objdump` 不认识 AArch64（`can't disassemble for architecture UNKNOWN`），
  换 `binutils-aarch64-linux-gnu` 后通过（run 45842864007）。
- **定案结果**（推翻前两版推断）：
  - `strh w8, [x24, #6]`，`w8 = 0x28` → 偏移 6 是**恒定 tag**，不是"头长 40"；
  - `mov x0, x24 + 0x78` 与 `str w26, [x24, #68]` → **路径长度在偏移 68，路径字节在偏移 120**；
  - `mov w9, #0xc78` → 请求 **3192** 字节；`add x9, x8, #0x878` → 应答 **2168** 字节（此前误读为 0x888=2184）；
  - 失败 `errno = 0x47` = 71 = `EPROTO`。
- 代码随之重写：`proto.rs` 的 `Tbd1`/`Tbd1Reply` 按上表；`broker.rs` 改读定长帧并回显 tag+序号。
- CI 运行 45843980364 全绿：handshake 5 + plan 8 + proto 7 = **19 项**。

## 阶段二第五轮：`TBD1` 请求负载的硬证据（22 项单测全绿）

回到 `FUN_00116df4` 开头逐行读，拿到六条硬证据（伪 C 行 4890-4940）：

| 证据 | 含义 |
|---|---|
| `*__string == '/'` 否则 `EINVAL(22)` | 负载里的路径**必须是绝对路径** |
| `strnlen(__string, 0x401)`，空→`ENOENT(2)`，超长→`ENAMETOOLONG(36)` | 路径上限 **1024 字节** |
| `memset(local_d68, 0, 0xc70)` | 发送前清零 **3184 字节**（= 3192 − 8） |
| `local_d6a = 0x28` | 偏移 **6** 处 u16 = **40** |
| `local_d2c = sVar16` | 某处 u32 = **路径长度** |
| `__memcpy_chk(..., 0xc00)` + 发送 0xC78 | 拷贝上限 3072，报文**定长 3192** |

- 代码侧新增 `tbd1_request_for_path(seq, role, path)`：校验绝对路径与 1024 上限，负载为 `{u32 长度, 路径字节}`，并**在命名与文档里标明路径在帧内的偏移未证实**。
- CI 运行 45841968202 全绿：handshake 5 + plan 8 + proto 9 = **22 项**（新增 `tbd1_request_for_path_carries_an_absolute_path`）。
- 仍不能确定：偏移 6 的 `0x28` 是"头长 40"还是别的标志；路径起始偏移（0 还是 40）。Ghidra 的栈槽类型在两处互相矛盾，单靠伪 C 无法定案。

## 阶段二第四轮：应答包络（21 项单测全绿）+ 一次自我纠错

- `Tbd1Reply` 加到 `proto.rs`：32 字节包络（magic/版本/序号/role/status/flags/3 保留字），`status==0 && flags==1` 视为成功；broker 现在回的是完整包络而不是裸 u32。
- **纠错**：上一轮我把"应答里带 fd 大小"当结论写进文档。复查伪 C 的栈帧偏移后确认——Ghidra 把请求头（`0x15f4/0x15f8/0x15fa/0x15fc`）与收发缓冲（`&local_d70` 0xC78、`&local_1600` 0x888）混在同一帧，**无法据此断定 0x888 是应答长度、0x47 是 EPROTO、fd 随应答传入**。已把该节降级为「推测，未证实」，代码里 `size` 不再上线路由（真实实现是对收到的 fd 做 `fstat`）。
- CI 运行 45841412028 全绿：`handshake 5 passed`、`plan 8 passed`、`proto 8 passed`（含新增 `tbd1_reply_carries_status_size_and_echoes`、`tbd1_reply_rejects_bad_envelopes`），合计 **21 项**。
- 期间被云端拦下一次真实 bug：我把 `size` 编码到 offset 24，而该位置在协议里是保留字，broker 回包被自己的解码器判为 `ReservedNotZero`。

## 阶段二第三轮：通道两端跑通（19 项单测全绿）

- 新增 `tools/miniloader/src/broker.rs`：`HandshakeClient`（attach → exec）与 `Broker`（应答握手、校验并回执 `TBD1`）两端，传输泛型 `Read+Write`，将来换成官方 `socketpair`+`SCM_RIGHTS` 无需改协议层。
- 新增 `tests/handshake.rs`：用 `UnixStream::pair()` 起两个线程，真实收发字节。
- CI 运行 45840005667 全绿，三组用例合计 **19 项**：
  - `handshake` 5 项：`handshake_then_exec_succeeds`、`exec_before_attach_is_refused_locally`、`attach_version_mismatch_is_detected`、`zero_handle_reply_is_rejected`、`oversized_declared_payload_is_refused_by_the_broker`
  - `plan` 8 项、`proto` 6 项（前两轮既有）
- 未验证：真机上的 `socketpair` + fd 传递；`TBD1` 负载里 3160 字节的具体切分仍未知。

## 阶段二第二轮：握手协议编码（6 项新单测全绿）

- 新增 `tools/miniloader/src/proto.rs`：把 `docs/LOADER_PROTOCOL.md` 的字段表落成可执行契约。
  - `B1F2`：32 字节，魔数 `0x32463142`、版本 `2`、kind `1`、status `3`、两个回显字、两个句柄字；`handles_present()` 对应 loader「两个句柄必须非 0」的校验。
  - `TBD1`：32 字节头（魔数 `0x31444254`、版本 `1`、序号 u16、role u32、负载长度 u32、flags 必须 `1`、三个保留字必须 0）+ 负载 ≤ `0xC00`，总长 `< 0x1000` 与 loader 的边界一致。
  - 解码逐项校验：魔数/版本/kind/status/flags/保留字/长度，长度声明与实际不符双向报错。
- CI 运行 45839431324 全绿：`test result: ok. 8 passed`（既有）+ `test result: ok. 6 passed`（新增 `b1f2_request_has_the_documented_layout`、`b1f2_round_trips_and_requires_both_handles`、`b1f2_rejects_wrong_magic_version_kind_status_and_size`、`tbd1_encodes_header_and_payload`、`tbd1_rejects_bad_inputs`、`tbd1_limits_match_the_documented_bounds`）。
- 未验证：真实 socket 上的收发（需要设备与宿主端实现）。

## 阶段二首轮：最小 loader 骨架（8 项单测全绿）

- 新增 `tools/miniloader/`（Rust，无外部依赖）：`elf.rs`（ELF64 校验 + PT_LOAD 提取）、`map.rs`（预留/逐段映射/bss/权限计划）、`stack.rs`（argc/argv/envp/auxv 初始栈镜像，`AT_PAGESZ` 强制、1 MiB 上限）。
- CI 运行 45838817417 全绿：`test result: ok. 8 passed; 0 failed`，逐条 `parses_a_single_executable_segment / rejects_malformed_images / plans_page_aligned_mappings_with_bss / plan_rejects_bad_pages_and_empty_inputs / prot_flags_map_to_the_expected_bits / stack_layout_is_ordered_aligned_and_terminated / stack_rejects_oversized_and_incomplete_inputs / empty_argv_is_still_a_valid_image`。
- 过程中被云端抓出四个真问题：栈镜像尾部未对齐、测试里 entry 落在段外、`0x1000` 已是页对齐（预留起点算错）、`ExitCode` 属 Rust 1.61 而声明的 MSRV 是 1.60；另有三条 clippy 规则（`len` 缺 `is_empty`、单分支 `match`、`Default::default()` 后逐字段赋值）被拦下。
- `rustfmt --check` 改为**建议性**检查（只报告 154 行建议，不阻塞），避免格式问题掩盖正确性门禁。

## 阶段一/二 首轮：loader 契约、HAL 线格式、gfxstream、ROM 打包器

三个子任务并行完成，结论分别归档：

- `docs/LOADER_RE.md`（576 行）：`libloader.so` 262 个函数逐个看。`.init_array` 只有 1 项 `_INIT_0 @ 0x11f208`，做的是**自建私有导入表**（GOT→`0x12fa40..0x12fc50`），这解释了为何 `fork`/`clone` 找不到 xref。37KB 的 `FUN_00107598` **不是 ELF 解析器**，是顶层编排（解析 auxv → dlopen/dlsym → getenv 分支 → ELF 装载 → 搭来宾初始栈 → 擦 environ → 间接跳转）；真正的 ELF 机件是 `0x113a70`/`0x114330`/`0x1141b4`/**`0x114560` 段映射**。握手两级：`0x116520` 的 32 字节 `B1F2` 报文 + SCM_RIGHTS 传 fd；`0x116df4` 的 3192 字节 `TBD1` 报文。
- `docs/HAL_PROTOCOL.md`（415 行）：`@twoyi_hal` 是**同一监听 + 首包 4 字节小端 int32 类型**，除 camera 与响应外无长度前缀/校验和；sensors 列表 156 字节/条、事件 28 字节；camera 帧头 24 字节 `W,H,'NV21',fpsMin,fpsMax,frameBytes`，wire 上无 stride/时间戳/fd；camera 在 0.7.5 已并入 `@twoyi_hal` type 5/6（`twoyi_camera_transport` 是 0.5.4 的叫法）。
- `docs/GFXSTREAM.md`：gfxstream（AOSP 13 vulkan-cereal）与来宾侧 renderControl 解码器**都静态编在 libtwoyi.so**；缓冲走 ColorBuffer + AHardwareBuffer；宿主启动幂等并有 host callback table。
- `docs/ROMPACK.md` + `tools/rompack/`（1559 行）：自写 USTAR 写入器 + zstd skippable BOM 帧，`pack/unpack/verify`。
  CI 证据（run 45823660954 全绿）：`[ok] container.single_zstd_frame`、`[ok] container.zstd_checksum`、`可复现性：两次 pack 输出逐字节一致（3146577 字节）`，sha256 `e080d489e55d071244b6f8575585537eff57cd20e14bc4a8532301edd368ae58`。

未解已逐条写进各文档；下一轮补 `.got.plt` 与编码数据段 hex（新增 `tools/ghidra/dump_ranges.py`，已接入 `ghidra-loader.yml`）。

## 最新版逻辑复原（全量反编译）

- 云端全量 jadx（run 45813416528）：`io.twoyi` 155 类、顶层 309 包、4705 个 java 文件；产物为 3 天期 artifact（2.6 MB），未入库。
- 关键结论写入 `docs/NEWEST_LOGIC.md`：启动改为 `DirectLauncher.prepare(loader, cmdline, …)` + `waitForExit()`，实例号经 `androidboot.twoyi.instance`；渲染改为 `configureEndpoints` + `prepare` + `attachWindow` 并换成 **gfxstream/Vulkan**；`handleTouch` 不再带窗口尺寸。
- 重要修正：`RomManager.getRootfsDir(Context, v7.a)` 直接返回共享 rootfs，说明 0.7.5 **不是每槽一份系统**，我此前按槽隔离 rootfs 的假设不成立。
- 端点仍是全局固定常量（`TWOYI_SOCK`、`twoyi_hal`、`rootfs/gfxstream-vulkan`），且进程内只允许配置一次，与“socket 名不可按槽改名”的结论一致。
- 未解问题已列清单：共享 rootfs 下的实例数据隔离、gfxstream 目录的生成与消费、loader 如何无内核拉起 init、resolver HAL 988 字节响应结构。

## 最新版 ROM 实测

- 云端 `rom-inspect` 任务（run 45812271640）下载官方 0.7.5 APK 并解出 `assets/rootfs.tar.zst`：212,205,950 字节 → 626,176,000 字节，单 zstd 帧（64 MiB 窗口、XXH64）+ 2 个 skippable 帧，其中一个是 JSON `bomVersion` 物料清单。
- 载荷为归一化的 USTAR tar（uid/gid 全 0、时间戳 1970），顶层仅 `rootfs/`，2077 条目、1553 文件、332 符号链接、577 ELF。
- 构建标识 `Android/twoyi_arm64/twoyi_arm64:8.1.0/OC/builde11130054:user/test-keys`：AOSP 8.1.0 arm64、user、test-keys，产品名 `twoyi_arm64`；`init` 在根目录、vendor 在 `/system/vendor`（system-as-root）。
- 专有部分仅 `hwcomposer.twoyi.so` 与改过的硬件能力声明；其余 HAL 为 goldfish 系，共 31 个模块。
- 结论与可行性分层写入 `docs/ROM_FORMAT.md`：重打包可行、AOSP 派生可行但成本高、“无内核运行”依赖闭源 `libloader.so`，不重写它无法产出等价自有 ROM。
- APK 与 ROM 内容均未入库，只保留报告。

## 转向最新版 UI 栈（Compose + Material 3）

- 按用户要求，不再以 0.5.4 界面为目标。构建体系已加入 Kotlin 1.6.10 + Compose 编译器 1.1.1 + Compose 1.1.1 + Material3 1.0.0-alpha11，云端 `assembleDebug` 通过（run 45808288015）。
- 新增 `ui/twin/TwinActivity`：Material3 外壳，顶部标题 + 双页（Twin / Phone）+ 底部 `NavigationBar`，内容先用文案卡片占位；字符串取自最新版同名键（`twin_tab_*`、`twin_boot_*`、`twin_phone_empty_*`），中文为自行翻译。可从设置页「打开新界面（预览）」进入。
- 已知限制：`material3:1.0.0-alpha11` 没有 `TopAppBar`，当前用标题文本代替，升级到稳定版后替换；外壳尚未接管来宾控制，仍走原 Java 路径。
- 未做的事（需要用户决定）：最新版 ROM（`rootfs.tar.zst`）来源；以及“完全照搬”与“不复制对方美术/代码资源”的边界。

## 启动闪退诊断（真机反馈）

- 用户安装 `baseline-11260913` 后反馈启动即闪退。没有堆栈时任何猜测都不可靠，因此新增 `CrashDiary`：`attachBaseContext` 的第一条语句就装默认未捕获异常处理器，把时间、设备、线程和完整堆栈写入 `filesDir/last-crash.txt` 与外部私有目录同名文件，自检对话框也会附上最近一次崩溃。
- 同时消除一个可能直接致命的启动路径：槽位解析或控制 socket 配置抛异常时，现在记录并回落到槽 0，而不是让进程死掉。
- 新增 `baseline-11260940`：https://github.com/ylh440104/twoyi-baseline/releases/tag/baseline-11260940 ，应用 APK 211594701 字节，SHA-256 `325b36545e48cd793f75010da6c4c4667f7a6008cf4efb0d161fbc2669fd6c2f`（与打包报告一致）。
- 该结论尚未闭环：仍需真机重装后取回 `last-crash.txt` 才能确定根因。

## 新版本产物下载核对

- 发布 https://github.com/ylh440104/twoyi-baseline/releases/tag/baseline-11260913 ：应用 APK 与测试 APK 均为该次云端构建产物（run 45789376869，development commit 059f1dbd）。
- 从发布地址重新下载应用 APK 后本地核对：211592821 字节，SHA-256 `cb5b31ed4dfc56fa6257c13138411278fe3ceacbd9542703d5ee1570096cc023`，与打包报告一致；测试 APK `30ecdb6654d15b860dc91c7266b45f972376a7caaaf6d530a901fc7d9fa13f8f`。
- 该版本相较 `baseline-11260131` 新增：实例创建/按槽装系统/切换/释放、设置页显示磁盘就绪状态、设备自检入口。
- 仍是 debug 构建，**设备行为未验收**（设备任务因缺专用 ARM64 runner 一直 skipped）。

## 自检覆盖端点与随包 ROM

- 自检新增三类检查：槽 0 端点全部位于数据目录内且日志/输入路径互不相同、控制 socket 仍是 ROM 内编译的名字、随包 `rootfs.7z` 的 7z 头（`37 7A BC AF 27 1C`）与 `rom.ini` 能解析出有效版本。
- 目的很实际：ROM 资源缺失或截断时，安装会晚到解包阶段才报错；自检能在装之前就告诉用户“随包系统在不在、是否完整”。
- 新增代码遵守 Java 8 源码级别（未使用 `Files.writeString`/`var`），`tools/check_slot_paths.py` 守卫通过。
- Actions 全部通过：host 十四项 PASS（https://github.com/ylh440104/twoyi-baseline/actions/runs/45789376837 ），APK 构建成功（同批 run `.../45789376869`）。
- 自检仍需在真机运行才能取证；其检查项都只读或写入缓存临时目录，不会动已安装的来宾。

## 设备自检入口

- 新增 `GuestSelfCheck` 与设置项“运行基座自检”：在没有 CI 设备的机器上，用真实沙箱验证槽位布局、注册表持久化、启动闸门、嵌套槽清理与“数据目录本身不可删”等不变量，并把逐条结果直接显示在对话框里。所有写入都落在缓存临时目录，不碰已安装的来宾。
- 同时修掉一个只在云端暴露的问题：应用模块以 Java 8 源码级别编译，而我写的 `Files.writeString` 与 `var` 属于 Java 9/10+，`assembleDebugJavaWithJavac` 直接报 `cannot find symbol`。已改为 Java 8 可用写法。
- `tools/check_slot_paths.py` 增加 Java 版本守卫：禁止主代码出现 `Files.writeString/readString`、`List.of/Map.of/Set.of`、`var`。并做过反向验证——故意插入 `var probe` 后脚本报 `FAIL` 返回 1，恢复后 `PASS`。
- Actions 全部通过：host 十四项 PASS（https://github.com/ylh440104/twoyi-baseline/actions/runs/45783451503 ），APK 构建成功（同批 run `.../45783451511`）。
- 自检本身尚未在真机跑过；它的输出就是给用户自己在设备上取证用的。

## 控制 socket 重试上限与破坏性操作前置条件

- 上一轮确认 socket 名必须在所有槽之间共用，于是出现新风险：另一个来宾进程若已占用该名字，本进程 `bind` 会失败，而原实现每 1 秒无限重试，既耗电又刷日志，来宾永远连不上。
- 新增 `ControlSocketRetry`：最多 3 次、指数退避（250/500/1000 ms），用尽后记录“另一个来宾可能占用了该名称”并停止重试；`EADDRINUSE` 与非抢占错误分开记录，便于排查。
- 同时把“装系统 / 清数据”这两个破坏性操作的前置条件从 `isStarted()` 收紧为 `phase() == STOPPED`：之前只挡“已经在跑”，挡不住“正在启动”，仍可能和启动过程抢同一个槽。
- Actions 全部通过（十四项 PASS），含 `PASS: control socket retries are bounded and collisions recognised`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45780543842 ，同批 APK 构建成功。
- 未验收：真实多进程场景下的 socket 占用提示、退避在实际设备上的表现。

## Socket 名不可按槽改名（自我纠正）

- 前几轮我把控制 socket 改成 `TWOYI_SOCK1..3` 以避免抽象名冲突。这是个设计错误：该名字编译在随包 ROM 的 loader 里，改名后来宾会连到不存在的监听，启动永远收不到完成通知，表现为启动超时。
- 已改回：**所有槽共用 `TWOYI_SOCK` / `twoyi_hal`**，隔离只靠文件（rootfs、日志、输入设备）。代价是同一设备同一时刻只能有一个来宾在跑——这与当前单进程实现一致，也不比现状更差；真正的多实例必须先有 Binder 或改来宾侧。
- 顺带修掉一个 API 陷阱：`GuestEndpoints.forSlot(slot, dir)` 的第二参是**槽目录**而非数据目录。之前传错会静默得到槽 0 的路径。现在槽 ≥1 要求目录形如 `<data>/slots/<n>`，否则直接抛错；用例覆盖传数据目录、传别的槽号、传 `slots/shared` 三种误用。
- Actions 全部通过（十三项 PASS），运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45779051507 ，同批 APK 构建成功。
- 影响记录：`docs/PARITY_STATUS.md` 已更新，把“socket 名按槽隔离”从设计目标改为明确的不可能项与前提说明。

## 新槽位目录缺陷

- 发现并修复一个真实缺陷：ROM 事务开始时会在槽根目录写日志临时文件，而槽 1…3 的 `data/slots/<n>` 在首次安装前并不存在，`Files.createTempFile(parent, …)` 会直接 `NoSuchFileException`，即“给新实例装系统”必然失败。槽 0 因为是数据目录本身，一直没暴露这个问题。
- 修复方式：`GuestSlotLayout.ensureBaseDirectory(dataDir, slot)` 创建并复核槽根目录（幂等，槽 0 直接返回数据目录），`RomManager.installRootfsInto` 改为先按槽号准备目录再开事务；安装入口也统一成按槽号调用，不再传目录，避免调用方传错目录。
- 回归新增：新槽创建成功、槽 0 不移动、重复调用幂等、槽位置被普通文件占用时拒绝而不是覆盖。
- Actions 全部通过（十三项 PASS），运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45775675640 ，同批 APK 构建成功。
- 该修复本身仍待真机验证（真实解包会用到这条路径）。

## 实例列表现状与设备用例

- 设置页的实例列表同时显示磁盘真实就绪情况（`GuestSlotLayout.isReady`）而不只显示注册表里的状态，并用 `*` 标出活动实例：注册表状态可能滞后于磁盘，用户需要看到“到底装没装系统”。
- 新增 `InstanceLifecycleTest`（instrumentation）：在真实应用数据目录上创建实例、校验槽位范围、重新加载确认可见、按磁盘实际情况核对启动闸门结论、带清理释放并确认只有该槽子树被删除，同时确认槽 0 仍是旧的数据目录。用例已加入 ARM64 设备套件清单。
- 公开构建：APK 云端构建成功（https://github.com/ylh440104/twoyi-baseline/actions/runs/45773515392 ），其中 `assembleDebugAndroidTest` 通过，说明新设备用例可编译；host 回归同批通过。
- 设备用例尚未执行（需要专用 ARM64 runner），因此“真实权限下创建/释放/就绪判定”仍属未验收。

## 切换实例（界面）

- 设置页新增“切换来宾实例”：先列实例，选中后要求该实例通过 `GuestInstanceGate`（必须已注册且槽内有可启动 ROM），再写入活动实例并持久化，然后延时杀掉宿主进程。
- 之所以要杀进程：来宾是进程级单例，只有新进程才会按活动实例重新绑定槽位（`GuestStartupSlot`）。界面会提示“正在重启”。
- 不能启动的实例不会被设为活动实例——否则重启后要么无 ROM 可启动，要么仍绑回旧槽，用户会看到“切换没生效”。
- 公开构建：APK 云端构建成功（https://github.com/ylh440104/twoyi-baseline/actions/runs/45771938031 ），host 回归同批通过。
- 未验收：真实切换（重启后来宾确实跑在目标槽）、切换前台服务与渲染器回收、失败回退提示，均需 ARM64 真机。

## 实例切换语义

- 渲染器、输入服务与来宾 init 都是进程级单例，所以“切换实例”不可能在同一进程内完成；正确语义是让**下一个进程**绑定目标槽。`GuestStartupSlot` 定义了优先级：显式槽位负载 > 注册表的活动实例 > 槽 0。
- `GuestProcessProfile` 启动时据此解析：读注册表失败只是一个警告并回落到槽 0，不会让应用起不来；解析结果同时决定端点、socket 名、ROM 与日志路径。
- 一个重要修正：清单里 `GUEST_SLOT=0` 是默认声明，若把它当成“显式负载”，每个进程都会被钉在槽 0，活动实例就永远不可达。现在默认值被当作“无负载”，由注册表决定；逐槽服务走 `initializeForSlot`，不经过这条判断。
- 用例覆盖：空注册表回落槽 0、活动实例选中其槽、显式负载覆盖活动实例、非法负载抛错、释放活动实例后回落、孤儿活动实例被拒绝加载。
- Actions 全部通过（十三项 PASS），含 `PASS: startup slot honours the payload, then the active instance`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45770816054 ；同批 APK 构建成功。
- 未验收：真实切换实例（设活动实例 → 重启进程 → 来宾跑在目标槽）需要 ARM64 真机；切槽后旧槽实例的前台服务与渲染器回收也需实测。

## 按槽安装系统（界面入口）

- `GuestInstanceInstaller.installBundledRom` 把随包 ROM 装到指定实例的槽：先确认没有来宾在运行、通过 `GuestMaintenance` 串行化，再调用 `RomManager.installRootfsForSlot`，成功后把实例状态置为 `READY`。
- 已就绪的槽直接返回，不重复解包；解包完成后重新加载注册表再写状态，避免“解包期间实例被释放”把已释放的实例写回来。
- 设置页新增“为实例安装系统”，先列实例再在后台执行，结束刷新列表并提示结果。
- 公开构建：APK 云端构建成功（https://github.com/ylh440104/twoyi-baseline/actions/runs/45769214940 ，产物 `opensource-baseline-build` 211402557 字节），host 回归同批通过，说明安装器与界面代码可编译打包。
- 未验收：解包本身依赖 Android assets 与 p7zip，只能在设备上验证；槽 1…3 的实际安装、耗时与失败清理均待 ARM64 真机。
- 仍未实现：把来宾跑在 `:guestN` 进程里、设备侧多实例并存、zst ROM 换代。

## 设置页实例入口

- 设置页新增“新建来宾实例”和“释放来宾实例”两项，均走 `GuestInstanceManager`：新建时自动挑选未被占用的 `twin-<n>` 名称并分配槽位，释放时先列出实例再二次确认，确认后释放并删除该槽系统目录。
- 所有读写都在后台线程完成，回调返回时校验 Activity 仍是当前实例，避免退出页面后弹 Toast 或改界面。
- 公开构建验证：APK 云端构建成功（https://github.com/ylh440104/twoyi-baseline/actions/runs/45767935110 ），说明新增界面代码、字符串与资源都可编译打包；host 回归同批通过。
- 设备侧尚未验收：按钮实际点击、创建后的槽位提示、释放确认与目录删除效果仍需 ARM64 真机。
- 仍未实现：创建实例后按槽安装 ROM 的引导、`:guestN` 进程承载、多实例并存。

## 实例创建与释放

- `GuestInstanceManager.create` 分配最低空闲槽并立即持久化；保存失败会回滚内存中的占用，避免内存与磁盘不一致、下次启动看不到该实例。
- `GuestInstanceManager.release(..., wipeData)` 释放槽位，可选删除该槽的 `rootfs`，让槽回到“没有安装 ROM”而不是看起来可启动；`RUNNING` 状态拒绝释放；未知实例是空操作，绝不猜测槽位去删数据。
- 用例覆盖：首次分配得槽 0、重新加载仍可见、不删数据的释放、复用槽 0、槽 1 嵌套子树的定点删除（不删 `slots` 容器）、未知实例空操作、运行中拒绝释放、参数非法。
- Actions 全部通过（十二项 PASS），含 `PASS: instance manager allocates, frees and wipes exactly one slot`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45766740615 ；同批 APK 构建成功。
- 仍未实现：设置页里的创建/释放入口（当前只在设置页做只读列表）、按槽解包的真实设备验证、`:guestN` 进程承载、设备侧多实例并存验收。

## 按槽安装与路径守卫

- `RomManager.installRootfsForSlot(context, slot, thirdParty)` 允许把 ROM 装到指定槽；`installRootfs` 仍装到当前进程的槽。两者共用一个按目录参数化的私有实现，事务目录就是该槽的根目录，因此准备第二个槽不会碰到当前槽。
- `tools/check_slot_paths.py` 作为回归守卫：禁止再用 `getDataDir()` 直接拼 `rootfs` / `log.txt` / `last_kmsg.txt` / `loader64` / `socket`，也禁止把 ROM 事务锚在数据目录上；同时要求 `getSlotBaseDir(Context, int)`、`installRootfsForSlot`、`LogEvents` 的槽路径调用存在。守卫本身做过反向验证：把 `LogEvents` 改回数据目录写法后脚本报 `FAIL` 并返回 1，恢复后重新 `PASS`。
- Actions 全部通过（十一项 PASS），含 `PASS: guest state paths are derived from the slot layout only`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45765462711 ；同批 APK 构建成功。
- 按槽安装的真实解包仍只能在设备上验证（依赖 p7zip 与 Android assets），未验收。
- 仍未实现：把来宾跑在 `:guestN` 进程里、设备侧多实例并存验收、zst ROM 换代、Compose 界面重写。

## 实例启动闸门

- `GuestInstanceGate` 把“注册表说这个实例占哪个槽”和“该槽确实有可启动的 ROM”两件事合并判断：未注册实例返回 `UNKNOWN_INSTANCE`，注册了但槽里没有 `init` + `system` 返回 `NO_ROM`，两者都不会放行到 `init`。
- 实例被释放后立即回到 `UNKNOWN_INSTANCE`，不会因为槽目录还存在而被误判可启动。
- 用例覆盖：未知实例、空槽、槽 0（数据目录）、槽 1（`data/slots/1`）、`init` 是目录、释放后失效、参数非法。
- Actions 全部通过（十项 PASS），含 `PASS: start gate requires a registered instance with a bootable slot`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45764213556 ，同批 APK 构建成功。
- 仍未实现：按槽安装 ROM 的入口（现在只装到当前进程的槽）、把来宾跑在 `:guestN` 进程里、设备侧多实例并存验收。

## 多进程槽位骨架

- 清单新增 `GuestInstanceService$Slot0..Slot3`，分别声明在 `:guest0`…`:guest3`；与最新版一致的四进程结构。当前全部 `android:enabled="false"`，因为来宾启动路径尚未按槽分发，提前启用会在没有 Surface 的进程里启动第二个渲染器。
- 槽位取自具体服务类名而非清单值：清单值对整个应用是同一个，无法按进程区分。`GuestSlotSelection.fromServiceClass` 只接受规范的 `$Slot0..$Slot3`，`Slot00`、`Slot4`、`Slot-1`、无后缀一律拒绝。
- `GuestProcessProfile.initializeForSlot` 让进程绑定到明确槽位，与“从清单读默认值”的路径共存。
- 云端曾两次打回：`foregroundServiceType="specialUse"` 需要 compileSdk 34（本树为 31），已移除，待 SDK 升级时再加；另一处仍是某个用例的 `javac` 少了 slot 依赖。后者改为用 `SLOT_SOURCES` 环境变量共享同一份编译集合，同类问题只会在一个地方出现。
- Actions 全部通过（九项 PASS），运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45762768217 ，同批 APK 构建成功。
- 仍未实现：把来宾真正跑在 `:guestN` 进程里（渲染器、init、控制服务都需按进程重建）、按槽安装 ROM、设备侧多实例并存验收。

## 实例注册表

- `GuestInstanceRegistry` 决定哪个实例占用哪个槽、哪个是活动实例、进展到哪一步（`RESERVED` / `READY` / `RUNNING`）。它不存来宾数据，也不启动进程，只负责防止两个实例拿到同一个槽。
- 分配取最低空闲槽；释放后可被复用；释放活动实例会清空指针；不存在或状态非法的注册表文件一律拒绝加载，不会静默丢数据。
- 状态文件写在 `data/instances.tsv`（各槽共享，因此放在数据根目录），采用临时文件 + 原子改名，崩溃只会留下旧版或新版之一。
- 设置页新增“来宾实例”项，后台读取注册表并以 `id → slot n (状态)` 形式列出；读取失败时显示错误而不是空列表。APK 云端构建通过，说明设置页与注册表代码可编译。
- Actions 全部通过，含 `PASS: instance registry allocates slots, persists atomically and rejects bad state`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45760331731 。
- 新增 `pure-java-compile` 任务：一次编译全部无 Android 依赖的主代码类，避免再次出现“某个用例的 `javac` 少了依赖”这类只在云端才暴露的问题。
- 仍未实现：实例创建后按槽安装 ROM、真正的 `:guestN` 多进程服务、设备侧多实例并存验收。

## 进程级槽位配置

- `GuestProcessProfile` 在 `attachBaseContext` 阶段一次性解析槽位（清单元数据 `io.twoyi.GUEST_SLOT`，缺省 0），并把端点缓存下来。控制监听、渲染初始化、ROM 路径、日志路径、清数据全部读取同一份结果，不再各自从数据目录推导——之前各调用方独立推导，一旦某处忽略槽位就会跨槽串用路径。
- `RomManager.getSlotBaseDir` 成为唯一入口：`rootfs`、`loader64`、`log.txt`、`last_kmsg.txt`、ROM 安装事务、可用空间检查都经由它。
- `GuestDataWipe` 的越界判据由“必须是数据目录直接子项”改为“必须在数据目录内且不等于数据目录”，这样 `slots/<n>/rootfs` 这类嵌套路径可以清除，同时 `data` 本身和外部路径仍被拒绝；不存在的目标按幂等语义跳过。
- Actions 全部通过（七项 PASS），运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45757815330 ，同批 APK 构建成功。
- 仍未实现：真正的 `:guestN` 多进程服务、按槽安装 ROM、设备侧多实例并存验收。

## 实例目录隔离

- `GuestSlotLayout` 决定每个槽的根目录：0 槽继续使用应用数据目录（已安装的旧版本仍可启动），1…3 槽位于 `data/slots/<n>`。
- 槽位不能共享一个 rootfs：旧版输入设备就落在 `rootfs/dev/input`，两个在跑的来宾会写同一批路径。因此额外槽必须各自安装一份 ROM，`isReady` / `requireReady` 会检查 `rootfs/init` 是文件且 `rootfs/system` 是目录，缺一就拒绝启动。
- `GuestSlot.forDataDir` / `GuestSlot.legacy` 把槽位目录解析统一走布局，避免调用方各自拼路径。
- Actions 全部通过，含 `PASS: slot layout keeps slot 0 legacy and requires a complete ROM per slot`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45754470075 。APK 构建同批成功。
- 仍未实现：按槽安装 ROM、按槽清除数据、多进程服务承载、设备侧多实例并存验收。

## 实例端点隔离

- `GuestEndpoints.forSlot(slot, dataDir)`：0 槽保持 `TWOYI_SOCK` / `twoyi_hal` 原名，1…3 槽分别为 `TWOYI_SOCK1..3` / `twoyi_hal1..3`。同一设备上的抽象 socket 名不能重复，这是多实例并存的前提。
- `TwoyiSocketServer` 与 `TwoyiMessenger` 的控制 socket 名改为进程内一次性配置：监听开始前可设置，开始后拒绝更改，避免外来宾连到无人监听的名称。
- `GuestSlotSelection` 从进程参数解析槽位：缺省为 0 槽，空串与非数字/越界值一律抛错，不静默回落到 0 槽。
- `GuestSlot.requireDistinct` 改为按来宾目录判断冲突，不再依赖端点全等，因此仅 socket 名不同的两槽也会被正确识别为共享目录。
- Actions 全部通过：`PASS: slot paths, process names and endpoint isolation`、`PASS: guest slot selection rejects malformed and out-of-range values`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45752429638 。APK 构建同批成功。
- 仍未实现：真正的多进程服务（`:guestN`）、按槽 ROM 安装与按槽清除数据；设备侧多实例并存未验收。

## 硬件桥接证据

- 从 0.5.4 配套 ROM 的 HAL 字符串确认：audio/lights/vibrator/sensors/gps 均连接抽象 socket `twoyi_hal`，camera 走独立 `twoyi_camera_transport`，hwcomposer 走渲染器共享内存。详见 docs/HAL_BRIDGE.md。
- 只有字符串，缺少帧头长度、版本字段取值与结构体布局，因此**不绑定** `twoyi_hal`，旧 ROM 保持自带降级路径（例如传感器返回 0 个）。
- `HalConnectionRegistry` 记录每模块一次连接与最终结果，上限 16 条；Actions 输出 `PASS: HAL connections are tracked once, bounded and finalized`（运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45748261762 ）。
- HAL 端到端桥接仍未实现，未验收。需要来宾内抓 `twoyi_hal` 首包字节后才能继续。

## 清除全部数据

- 设置新增“清除所有数据”，先在确认对话框中确认，再在后台线程检查来宾未运行且未在解包 ROM，然后只删除来宾数据目录下的 `rootfs`。
- `GuestDataWipe` 拒绝任何不是数据目录直接子项的路径，不跟随符号链接，删除后复核目录确实不存在；`tools/GuestDataWipeTest` 在 Actions host 回归中通过（`PASS: wipe stays inside the data directory and removes only guest roots`，运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45745581035 ）。
- 该版本仍未做数据目录整体重建与多实例场景下的按槽清除；设备侧确认对话框和重启行为待 ARM64 验收。

## 共享文件操作

- `65feed9` 通过 Android 文件提供程序创建、写入、重命名并删除共享文件，检查重命名后内容完整且旧路径已消失。托管模拟器和专用 ARM64 设备任务都要求通过全部三项文档测试。
- 已检查工作流语法和用例数量，云端设备测试待执行。

## 尚未验证

- `9ba3d7c` 将控制 socket 用例接入 ARM64 设备工作流。用例等待监听就绪，检查同 UID 客户端连接，并连续验证对端结束发送后服务端关闭连接。旧版来宾自身的重连仍需在完整启动用例中另行验证。
- `f6de280` 为触摸和按键 socket 设置写入超时，并加入真实 Unix socket 字节流回归，检查帧字节顺序和关闭行为。静态检查已通过，Actions 回归尚待执行。
- 旧 ROM 实际启动、按键释放、多指触控和窗口重连。Unix socket 字节流测试无法确认来宾应用是否收到触摸事件，这一项需要独立设备验收。
- 新版功能等价性。

这些 host 回归测试不代表完整 Android/ROM 集成测试通过。验证分支只发布测试必需源文件，尚非完整开发分支。
