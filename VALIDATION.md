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
