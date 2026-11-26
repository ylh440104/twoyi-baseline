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

## 5. HAL 桥接

- 各 HAL 模块连 `twoyi_hal`（audio/lights/vibrator/sensors/gps），camera 走自有 `twoyi_camera_transport`，HWC 走 gfxstream 共享内存。
- 另有两个特殊 HAL：`TwoyiResolverHal`（读 360 字节请求、返回固定 988 字节响应，进入 native `nativeResolve`）与 `KernelSuHal`（把 socket fd 复制后交给 native `nativeHandle`）。
- 说明“完全复刻 HAL”需要 native 侧实现，不是纯 Java 能做到的。

## 6. 与我们当前实现的差异结论

| 项目 | 0.5.4 基座（现状） | 0.7.5（目标） |
|---|---|---|
| 来宾启动 | Rust 里 `Command::spawn("./init")`，用 `TYLOADER` 环境变量 | `DirectLauncher.prepare(loader, cmdline, …)`，实例号走内核命令行 |
| 渲染 | `init(surface, …)` 一次性 + `libOpenglRender` | `configureEndpoints` + `prepare` + `attachWindow`，**gfxstream/Vulkan** |
| 触摸 | `handleTouch(event, w, h)`，Java 侧算坐标 | `handleTouch(event)`，native 侧缩放 |
| 多实例 | 每槽一套目录与端点名 | 共享 rootfs，实例号经 cmdline，进程按槽拆分 |
| 后台屏 | 无 | `VirtualDisplay` + `HostBackdropService` |

## 7. 未解问题（下一步验证）

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