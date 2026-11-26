# 最新闭源版（0.7.5-11131322）架构对照

来源：`https://dl.twoyi.ai/twoyi_0.7.5-11131322.apk`，SHA-256 `86ed3afaa447104f131d906c42ed8a07e207959c7869fda54bd245f62db14f4e`。
分析记录见 `ylh440104/twoyi-apk-reverse` 仓库的 `report/` 与 `decompiled/sources/`。本文件只记录可复核的结构事实，不代表运行行为已验证。

## 与最后开源版的结构差异

| 项目 | 开源版 0.5.4 | 最新版 0.7.5 | 影响 |
|---|---|---|---|
| ROM 载体 | `assets/rootfs.7z` + `rom.ini` | `assets/rootfs.tar.zst`（212 MB），另有 `rootfs.version` 与第三方 `rootfs_3rd.zst` | 安装器、预检、解包方式完全不同；最新版只接受 direct USTAR 负载 |
| 解包实现 | libp7zip + `P7ZipApi` | 原生 `twoyi.so` 解 USTAR，Java 侧 `RomManager` 走原生事务 | 旧 Java 解包路径不可直接复用 |
| 宿主进程模型 | 单进程，原生渲染器为进程单例 | `:guest0`…`:guest3` 四个来宾进程 + 每槽 `GuestInstanceService$SlotN` | 实例隔离依赖多进程，不是单进程内的端点区分 |
| 前台服务 | `GuestSessionService`（主进程） | 每槽 `foregroundServiceType=camera\|location\|specialUse` 的服务 + `host.HostDisplayService`（`specialUse`） | 后台存活与权限声明不同 |
| 窗口 | `Render2Activity` + `SurfaceView` | `ui.twin.MainActivity`（Compose）+ `host.HostBackdropActivity`（隐藏后台屏，`taskAffinity=io.twoyi.backdrop`） | 后台屏是独立 Activity，支持“来宾任务在宿主屏上运行”的新模式 |
| UI 技术栈 | AppCompat + XML 布局 + `com.afollestad.materialdialogs` | Jetpack Compose + Material 3（`androidx.compose*`、`m3c_*`、`Theme.Twoyi.Twin`）、`androidx.window` | 控件、主题、字符串与交互全部重写 |
| 控制通道 | 抽象 `TWOYI_SOCK` SEQPACKET 文本命令 | 每槽 Binder 服务 `GuestInstanceService`，`io.twoyi.extra.INSTANCE_ID` 选槽；`DirectLauncher` 原生接口 | 控制面从文本 socket 变为 Binder + 实例 ID |
| 能力范围 | 纯容器 | 另含 AI 助手产品层（`twin_*` 字符串：模型连接、任务、记忆、通知、PIP 控制） | 这部分不是容器基座，属于新产品功能 |

## 最新版组件清单（来自 apktool 解出的 manifest）

- Activity：`io.twoyi.ui.twin.MainActivity`（LAUNCHER，`singleTop`，支持 PIP）、`io.twoyi.ui.SelectAppActivity`、`io.twoyi.ui.SettingsActivity`、`io.twoyi.host.HostBackdropActivity`（`process=:guest0`）。
- Service：`io.twoyi.GuestInstanceService$Slot0..Slot3` 分别位于 `:guest0`…`:guest3`；`io.twoyi.host.HostDisplayService` 位于 `:guest0`。
- Provider：`FileProvider`、`TwoyiDocumentsProvider`（authority `io.twoyi.documents`）、`rikka.shizuku.ShizukuProvider`。
- 权限新增：相机、录音、定位、蓝牙、传感器高频采样、前台服务 camera/location/specialUse、`POST_NOTIFICATIONS`、`QUERY_ALL_PACKAGES`。

## 实例模型要点

- `GuestInstanceClient` 持有 4 个静态槽位和一个默认槽位，`bind` 时按槽位选择服务类；槽位占用与实例 ID 通过共享状态记录，无空槽时报 `no free guest slot for instance`。
- 每槽数据目录、日志、socket 与 init 进程各自独立；渲染器因此必须是每个来宾进程一份，这与开源版“原生渲染器为进程单例”的前提直接冲突，必须靠多进程解决。

## 本仓库的追赶策略

1. 先完成单实例基座的全部验收（启动、退出、后台、窗口、输入、ROM、文件与应用、HAL）。
2. 以纯 Java 的槽位描述类先行落地路径/端点隔离与校验，再用多进程服务承载，逐槽接入。
3. UI 对齐 Compose + Material 3 需要整体重写界面层；在基座验收前不改 UI 框架，只补齐设置项与状态展示等可复用部分。
4. ROM 换代到 `rootfs.tar.zst` 需要新的原生解包接口，属于独立阶段，不能沿用 `libp7zip`。

## 已验证

- 云端托管 arm64 runner 探测成功（`ubuntu-24.04-arm`，aarch64，无 `/dev/kvm`），可用于纯编译与单元回归，不能跑模拟器。
- `GuestSlot` 槽位模型已在 Actions host 回归中通过（`PASS: slot paths, process names and endpoint isolation`），运行 https://github.com/ylh440104/twoyi-baseline/actions/runs/45740222922 。
- 新仓库常规构建连续成功：https://github.com/ylh440104/twoyi-baseline/actions/runs/45739426196 、https://github.com/ylh440104/twoyi-baseline/actions/runs/45737974938 。

## 尚未验证

- 最新版实例创建、槽位切换、后台屏与 PIP 的真实运行行为。
- 最新版 HAL 桥接协议与旧 ROM 的兼容关系。
- `rootfs.tar.zst` 的具体封装参数与校验流程。
