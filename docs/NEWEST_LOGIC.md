# 最新版 0.7.5 逻辑复原（云端全量反编译）

来源：官方 APK 全量 jadx（run 45813416528，`io.twoyi` 155 个类、顶层 309 个包、4705 个 java 文件）+ apktool 资源。
反编译产物只作为 3 天期 artifact 存在，不入库；本文件只记录结论。

## 1. 启动链（与 0.5.4 完全不同）

`io/twoyi/n.java`：

```java
RomManager.initRootfs(context);
RomManager.bootTwoyi(context);
String loader = RomManager.getLoaderPath(context);
v7.a inst = v7.b.e(context).b();                       // 实例身份
DirectLauncher.prepare(loader,
    "androidboot.hardware=goldfish androidboot.serialno=" + inst.serial +
    " androidboot.twoyi.instance=" + inst.id,
    width, height, xdpi, ydpi, fps);
new Thread(() -> DirectLauncher.waitForExit(), "direct-guest-lifetime").start();
```

`io/twoyi/DirectLauncher.java` 只有四个 native：

```java
native void prepare(String loader, String cmdline, int w, int h, float xdpi, float ydpi, int fps);
native void stop();
native int  waitForExit();          // 返回值是 waitStatus，非 0 视为异常退出
```

要点：
- 来宾 **init 由 native 在同进程内拉起**，loader 路径 + 内核命令行作为参数；实例号通过 `androidboot.twoyi.instance=<8位hex>` 传入，而不是分开的数据目录。
- 退出判定统一走 `waitForExit()`：返回非 0 时抛 `IllegalStateException("direct guest init exited: waitStatus=…")`。

## 2. 渲染链

`io/twoyi/Renderer.java`：

```java
native void configureEndpoints(String rootfs, String touch, String key,
                               String halSock, String controlSock, String gfxstream);
native void prepare(int w, int h, float xdpi, float ydpi, int fps);
native void attachWindow(Surface surface);
native void resetWindow(Surface surface, int top, int left, int w, int h);
native void removeWindow(Surface surface);
native void handleTouch(MotionEvent event);   // 不再传窗口宽高
native void sendKeycode(int keycode);
```

`io/twoyi/o.java` 一次性配置端点，进程内只允许一次，重复配置直接抛 `"guest endpoints already configured: …"`：

```
rootfs      /data/data/io.twoyi/rootfs
touch       /data/data/io.twoyi/rootfs/dev/input/touch
key         /data/data/io.twoyi/rootfs/dev/input/key0
hal         twoyi_hal
control     TWOYI_SOCK
gfxstream   /data/data/io.twoyi/rootfs/gfxstream-vulkan
```

要点：
- 端点名仍是**全局固定常量**，与 ROM 内 loader 编译值一致；这印证了“socket 名不能按槽改名”的判断。
- 渲染由旧的 `libOpenglRender` 换成 **gfxstream（Vulkan）**：`rootfs/gfxstream-vulkan`。
- `handleTouch` 不再接收窗口尺寸，缩放改在 native 侧完成。

## 3. 实例身份

`v7/a`：

```java
Pattern id = "[0-9a-f]{8}";
fields: id, ??, long, serial, int
```

- 身份以 JSON（`schema=1`）持久化，字段含 `id` 与 `serial`；`id` 必须是 8 位十六进制。
- 关键发现：`RomManager.getRootfsDir(Context, v7.a)` **直接返回共享的 `rootfs`**，即 4 个槽**共用同一份根文件系统**，实例差异靠 `androidboot.twoyi.instance` 与各自的 `data/user` 目录区分。
  - 这修正了我此前“每槽一份 rootfs”的实现假设：0.7.5 并不是按槽复制系统。
  - 具体 guest 内部如何隔离 user 数据（`/data/user/<n>`?）尚未证实，列为本轮未解问题。

## 4. 进程与显示模型

- 清单：`GuestInstanceService$Slot0..3` 分处 `:guest0..:guest3`，`HostDisplayService` 在 `:guest0`。
- `HostBackdropActivity`（`:guest0`）配合 `DisplayManager`/`VirtualDisplay` 承担“后台屏”，`host/u.java`、`host/k.java`、`host/d0.java` 都在用 `VirtualDisplay` API —— 即最新版是靠**虚拟显示器**把来宾画面投到宿主窗口，而不是把 Surface 直接交给渲染器。

## 5. 原生层实测（本轮新增）

对 `libtwoyi.so`（9,850,096 字节）、`libloader.so`（147,768）、`libloader32.so`（146,256）做了依赖、符号与字符串分析（run 45815673066、45816199208）：

- **libtwoyi.so 只依赖** `libandroid / liblog / libc / libm / libdl`：没有 libvulkan、没有 libEGL/GLES 依赖，只导出 `JNI_OnLoad` 一个符号。说明 **gfxstream 是静态编进 libtwoyi.so 的**，不是外挂库。
- 字符串里出现 `/data/twoyi-13/device/generic/vulkan-cereal/...`（含 `stream-servers/RendererImpl.cpp`、`glestranslator/EGL/*`、`address_space_graphics.cpp`、`WindowSurface.cpp`、`ChannelStream.cpp`）——构建路径直接暴露了：**宿主渲染器是 AOSP 13 的 vulkan-cereal（gfxstream）**，宿主侧做 GLES→Vulkan 转换。
- 还出现 `zstd` 符号（weak，静态链入）→ 解包 zstd 也在 native，不在 Java。
- **libloader.so / libloader32.so 是 PIE 可执行文件**（`Type: DYN`，有 `.interp`，入口 0x5e30/0x5370），只依赖 libc/libdl，导出 0 个符号，`objdump` 里 **`svc` 指令数为 0**（不自己发系统调用）。
- loader 里的关键字符串：`/system/bin/linker64`、`/system/bin/linker`、`__libc_init`、`sigaltstack`、`TWOYI_ROOTFS_EXEC=GRB_V2:CODE_OPEN:EXEC_SEALED`。
  → 它不是内核，而是**借用来宾自带的 linker 来装载并执行来宾 init**，并用 `TWOYI_ROOTFS_EXEC` 的 `GRB_V2:CODE_OPEN:EXEC_SEALED` 形式表示执行策略/密封模式。
- loader 导入 `dlopen/dlsym/fork/clone/exec*` 以及 `getaddrinfo/gethostbyname/getpeername/getsockopt` 等一批 libc 函数，符合“用户态运行时垫片”的角色。
- libtwoyi 里还能看到 `ANDROID_EMU_SANDBOX` 字符串（沙箱相关），以及 vulkan device 选择的告警文案，说明渲染设备在宿主侧挑选。

尚未解开（这才是“完全一样”的真正门槛）：

1. `GRB_V2:CODE_OPEN:EXEC_SEALED` 的具体语义与 loader 与 init 之间的握手指令流。
2. `gfxstream-vulkan` 目录里谁创建文件、格式如何、与 `address_space_graphics` 的共享内存约定。
3. `twoyi_hal` 各模块（audio/sensors/gps/lights/vibrator）的帧结构；camera 的 `twoyi_camera_transport` 头定义。
4. `TwoyiResolverHal` 988 字节响应的字段布局。
5. 共享 rootfs 下实例数据的隔离点（`androidboot.twoyi.instance` 被谁消费）。

第 1、2 项需要反汇编 + 设备侧动态追踪；第 3、4 项可以靠“对着旧 ROM 的 HAL 客户端抓真实字节”反推。**这已经不是靠字符串和清单能完成的工作量**，属于二进制逆向阶段。

## 6. HAL 桥接

- 各 HAL 模块连 `twoyi_hal`（audio/lights/vibrator/sensors/gps），camera 走自有 `twoyi_camera_transport`，HWC 走 gfxstream 共享内存。
- 另有两个特殊 HAL：`TwoyiResolverHal`（读 360 字节请求、返回固定 988 字节响应，进入 native `nativeResolve`）与 `KernelSuHal`（把 socket fd 复制后交给 native `nativeHandle`）。
- 说明“完全复刻 HAL”需要 native 侧实现，不是纯 Java 能做到的。

## 7. loader 初步反编译（研究路线，进行中）

云端用 Ghidra 11.0.3 headless 对 `libloader.so` 做全量反编译（run 45819687672，产物 560 KB 伪 C，14231 行，7 天期 artifact）。

已确认的事实：

- 入口只有两步：`entry → FUN_00105e44 → __libc_init(argc, 0, PTR_FUN_00129d90, &stack)`，即它是一个**自带 ELF 装载器的运行时**，不依赖系统 linker。
- 它自己实现了动态链接所需的机制：导入 `dlopen/dlsym/dlclose`、`mmap/mprotect/pread64`、`syscall`、`socketpair`、`sigaltstack`、`strtol/strtoul/router` 等，说明**段映射、重定位、异常信号栈**都由它自己做。
- 体积分布：`FUN_00107598`（37 KB）、`FUN_00119008`（17 KB）、`FUN_00118028`（4 KB）、`FUN_00110 808`（6.7 KB）是主要逻辑块，应为 ELF 解析/重定位/初始化；这与“无内核跑 init”的分工吻合：loader 负责把来宾可执行文件搬进内存并跳转，系统调用层由 libtwoyi 侧接管。
- 字符串 `/system/bin/linker64` 仅作为引用出现一次，没有二次调用，说明它**替代**而非复用来宾 linker。

下一步要回答的问题（按顺序）：

1. `FUN_00105e44` 之后的初始化数组 `PTR_FUN_00129d90` 依次调用了什么（决定它先建什么运行时状态）。
2. 37 KB 的 `FUN_00107598` 是 ELF 解析还是重定位循环，函数内对 `PTR_`/`DAT_` 的引用分布。
3. loader 与 libtwoyi 的握手参数（`TWOYI_ROOTFS_EXEC=GRB_V2:CODE_OPEN:EXEC_SEALED` 由谁读、读后做什么）。
4. syscall 垫片在哪一侧（loader 内或 libtwoyi 内）——目前看 loader 自己发 `syscall`，需要确认拦截点。

结论：这条路线可行但要按“函数簇”逐个啃，属于周级工作；本轮已经建立了**可重复的云端反编译流水线**，后续每轮产出可读的伪 C 片段并归档结论。

## 8. 与我们当前实现的差异结论

| 项目 | 0.5.4 基座（现状） | 0.7.5（目标） |
|---|---|---|
| 来宾启动 | Rust 里 `Command::spawn("./init")`，用 `TYLOADER` 环境变量 | `DirectLauncher.prepare(loader, cmdline, …)`，实例号走内核命令行 |
| 渲染 | `init(surface, …)` 一次性 + `libOpenglRender` | `configureEndpoints` + `prepare` + `attachWindow`，**gfxstream/Vulkan** |
| 触摸 | `handleTouch(event, w, h)`，Java 侧算坐标 | `handleTouch(event)`，native 侧缩放 |
| 多实例 | 每槽一套目录与端点名 | 共享 rootfs，实例号经 cmdline，进程按槽拆分 |
| 后台屏 | 无 | `VirtualDisplay` + `HostBackdropService` |

## 9. 未解问题（下一步验证）

1. 共享 rootfs 下 guest 内部如何隔离实例数据（是否 `/data/user/<n>`、`androidboot.twoyi.instance` 被 init 读取后做了什么）。
2. `gfxstream-vulkan` 目录内文件的生成与消费方式。
3. `DirectLauncher` 如何在无内核环境拉起 init（loader 侧行为，属闭源 native）。
4. `TwoyiResolverHal` 988 字节响应的结构。

## 复现

```bash
# CI 里执行，产物不入库
curl -L -o twoyi.apk https://dl.twoyi.ai/twoyi_0.7.5-11131322.apk
unzip -p twoyi.apk assets/...            # 见 docs/ROM_FORMAT.md
# jadx 全量反编译见 .github/workflows/decompile-full.yml
```