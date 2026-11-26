# 最新版来宾 ROM 的构成（云端实测）

来源：官方 `https://dl.twoyi.ai/twoyi_0.7.5-11131322.apk`，SHA-256 `86ed3afaa447104f131d906c42ed8a07e207959c7869fda54bd245f62db14f4e`。
实测运行：GitHub Actions `45812271640`（`.github/workflows/rom-inspect.yml`）。APK 与 ROM 只在 runner 上存在，仓库不保存其内容，只保留本报告。

## 容器格式

- `assets/rootfs.tar.zst` 212,205,950 字节，解压后 626,176,000 字节（约 597 MiB，比例 2.95）。
- 单个 zstd 帧：窗口 64 MiB，XXH64 校验，帧前另有 **2 个 skippable frame**。
- 首个 skippable frame 内是 JSON，开头 `{"bomVersion":"release-2…` —— 即“物料清单”元数据（渠道/版本/摘要），不是压缩数据。
- 载荷是**普通 USTAR tar**，所有条目 uid/gid 均为 `0/0`、时间戳统一为 1970-01-01，即打包时做了归一化。

## 树结构

- 顶层只有一个目录 `rootfs/`，共 2077 个条目：1553 个普通文件、332 个符号链接、193 个目录、577 个 ELF。
- 典型的 Android 8.1「system-as-root」布局：

```
rootfs/init                  1,344,728 字节 ELF（不是 /system/bin/init）
rootfs/init.rc / init.goldfish.rc / init.zygote64.rc …
rootfs/default.prop
rootfs/fstab.goldfish        只有 22 字节
rootfs/sepolicy              271,461 字节
rootfs/plat_*_contexts       完整的 SELinux 上下文表
rootfs/system/…              /system 与 /system/vendor 实体目录（vendor 不是链接）
rootfs/system/etc/permissions/twoyi.xml
rootfs/system/vendor/lib64/hw/*.so   31 个 HAL 模块
```

- 构建标识（`/system/build.prop`、`/default.prop`）：

```
ro.build.fingerprint=Android/twoyi_arm64/twoyi_arm64:8.1.0/OC/builde11130054:user/test-keys
ro.build.id=OC
ro.build.version.release=8.1.0
ro.product.device=twoyi_arm64
ro.build.type=user
ro.build.version.sdk=27
```

即：**AOSP 8.1.0（Oreo）arm64、user 变体、test-keys**，产品名改成 `twoyi_arm64`，构建时间戳嵌在 fingerprint 里。

- 专有两处：`system/vendor/lib64/hw/hwcomposer.twoyi.so`（自绘渲染 HWC），以及改过的 `handheld_core_hardware.xml` / `twoyi.xml` 权限声明。其余 HAL 全是 goldfish 系（audio/camera/gps/sensors/lights/vibrator/gralloc 等）。

## 它是怎么“做”出来的

1. 用 AOSP 8.1 的 arm64 目标编译出 system/vendor 树（goldfish 系 HAL 是 AOSP emulator 目标自带）。
2. 换成 system-as-root 布局，把 vendor 放进 `/system/vendor`，把 init 及 rc 提到根目录。
3. 加自有的 `hwcomposer.twoyi.so`，改硬件能力声明。
4. 归一化属主与时间戳后打成 USTAR tar，再用 zstd（64 MiB 窗口）压缩，前面塞入 BOM 元数据帧。

## 我能不能做一个

分三层，结论不同：

| 层次 | 能否做 | 说明 |
|---|---|---|
| **重新打包**（tar + zstd + BOM 帧，格式兼容） | 能，成本低 | 只是归档器；可在 CI 里用合成树做往返测试验证 |
| **用 AOSP 8.1 源码编出同款 rootfs** | 理论上能，成本极高 | 需要完整 AOSP 树与数小时构建，且必须替换成 twoyi 的内核替身（loader） |
| **连“无内核运行 Android”一起复刻** | 目前不能 | 关键在 `libloader.so`：它把 init/zygote 需要的 mount、/proc、/dev 等系统调用在用户态伪造出来。没有它，标准 AOSP init 起不来。它是闭源二进制，公开源码只带预编译产物 |

也就是说：**镜像可以打包、可以从 AOSP 派生，但“能在普通 App 进程里跑起来”的那一层是 twoyi 的核心资产**，不重写 loader 就无法做出等价的自有 ROM。

## 因此的现实路线

1. 宿主 App 按最新版 UI/行为重写（Compose + Material 3，已起步）。
2. 来宾镜像阶段先用官方 ROM（构建时从官方地址下载并校验，不入库、不随产物再次分发），把功能跑通。
3. 若要完全自有：把 `loader + init 适配 + 根文件系统生成` 作为一个独立研究阶段，先做格式与最小可启动验证，再谈完整系统。

## 复现方式

```bash
# 需要网络；产物不入库
curl -L -o twoyi.apk https://dl.twoyi.ai/twoyi_0.7.5-11131322.apk
unzip -p twoyi.apk assets/rootfs.tar.zst > rootfs.tar.zst
zstd -lv rootfs.tar.zst
tar --use-compress-program=unzstd -tvf rootfs.tar.zst | head
```