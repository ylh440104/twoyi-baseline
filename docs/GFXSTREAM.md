# gfxstream 在最新版里的接法（阶段一·渲染）

来源：官方 0.7.5 APK 的 `libtwoyi.so` 字符串与依赖分析（run 45815673066 / 45816199208），以及 Java 层反编译（`io/twoyi/o.java`）。
证据均为字符串或清单，标注了「已确认」与「推测」。

## 已确认

1. **gfxstream 是静态编进 `libtwoyi.so` 的**，不是外挂库：该 so 只 NEEDED `libandroid/liblog/libc/libm/libdl`，仅导出 `JNI_OnLoad`；文件里带 `libvulkan.so`、`libvulkan.so.1` 字符串（运行时 `dlopen` 取宿主 Vulkan）。
2. 构建路径暴露源码来源：`/data/twoyi-13/device/generic/vulkan-cereal/...`，含 `stream-servers/RendererImpl.cpp`、`CompositorVk.cpp`、`DisplayVk.cpp`、`address_space_graphics.cpp`、`WindowSurface.cpp`、`glestranslator/EGL/*`。→ 宿主渲染器与 GLES 翻译层都来自 **AOSP 13 的 vulkan-cereal**。
3. **来宾侧驱动也在同一个 so 里**：存在 `renderControl_decoder_context_t`、`rcSetColorBufferVulkanMode` / `rcSetColorBufferVulkanMode2` 等 renderControl 解码符号，以及 `ANDROID_EMU_vulkan*` 一整套扩展名字符串。→ 宿主与来宾两侧的 gfxstream 协议端都被编译进了同一个库。
4. 缓冲通路是 **ColorBuffer + AHardwareBuffer**：`gfxstream AHB acquire rejected cb=%u size=%ux%u format=%u`、`external-ahb instance cleanup`、`colorBuffer 0x%x does not have Vulkan external memory backing` 等。
5. 自有标记：`TwoyiGfxstream`、`ANDROID_GFXSTREAM_EGL`、`gfxstream host is already started or was stopped`、`invalid or unsupported gfxstream host callback table`。→ 宿主启动是幂等的，并且通过一张 **host callback table** 与外来层对接。
6. Java 侧只给一个路径：`Renderer.configureEndpoints(..., "/data/data/io.twoyi/rootfs/gfxstream-vulkan")`（`io/twoyi/o.java`，进程内只允许配置一次）。

## 推测（待验证）

- 该目录是**宿主与来宾之间的 rendezvous 点**：由 native 在目录内创建 socket/共享内存文件，来宾侧驱动据此连接。依据：Java 只传目录，未传任何端口或文件名；且 gfxstream 的 HostConnection 需要一个可发现的端点。
- 握手顺序应为：`Renderer.configureEndpoints` → 宿主侧建端点 → `DirectLauncher.prepare` 起来宾 init → 来宾驱动在 `init` 阶段连上。依据：配置端点发生在启动来宾之前（`e0.h()` 的调用顺序）。

## 未解

1. 目录内具体文件名（是否抽象 socket、是否 `/dev/shm` 式共享文件）。
2. `AddressSpace` 共享内存的实际布局（`address_space_graphics.cpp` 里的 ring 结构与大小）。
3. host callback table 的函数表内容与版本协商。
4. `renderControl` 的 opcode 全表（目前只确认了两个 `rcSetColorBufferVulkanMode*`）。

## 对自研路线的影响

- 我们**不需要**复刻 gfxstream：自研运行时可以选择更简单的渲染通路（如自绘 Surface + 自己的共享内存 ring），只要 UI 行为一致即可。gfxstream 的细节只在“要与官方产物二进制兼容”时才必须还原。
- 需要复刻的是：**端点目录的创建时机与生命周期**（跟随实例启停），以及**宿主 callback 表**这份“外来层接口”的最小语义。