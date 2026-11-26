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
