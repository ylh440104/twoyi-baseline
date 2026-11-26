# 阶段一完成度盘点（0.7.5 闭源逻辑还原）

截止本轮，先把「已经知道的」和「还不知道的」分开写清楚，避免把推断当成事实继续往下建。

判定标准只有三档：

- **实证**：有反汇编指令流或全新裸字节可复现，任何人按文档里的命令都能取得同样的结果。
- **推断**：依据伪 C、字符串或上下文推出，与现有证据自洽，但**没有闭环证据**。
- **空缺**：完全没有线索。

---

## 1. loader（`libloader.so`）

| 项 | 结论 | 等级 | 证据 |
|---|---|---|---|
| 入口与分析链 | `entry → FUN_00105e44 → __libc_init(..., PTR_FUN_00129d90, ...)`；`.init_array` 仅 `_INIT_0 @ 0x11f208` 一项 | 实证 | `LOADER_RE.md`：Ghidra 伪 C + `readelf -x .init_array` |
| `_INIT_0` 作用 | 把 GOT 中的 libc 指针拷进私有导入表 `0x12fa40..0x12fc50`（IAT 擦洗），故 `fork/clone` 无直接 xref | 实证 | `LOADER_RE.md`；`loader-code.txt` 段表 |
| `main` 的 GOT 槽 | `0x129d90 → 0x5f20`，即 `main = FUN_00105f20` | 实证 | `.rela.dyn` 50 条 `R_AARCH64_RELATIVE`，`LOADER_PROTOCOL.md` §4 |
| 真正 ELF 机件 | `0x113a70`（Ehdr/Phdr 校验）、`0x114330`（vaddr→PT_LOAD）、`0x1141b4`（`pread64` 循环）、`0x114560`（mmap 段映射 + mprotect 合并） | 实证 | `LOADER_RE.md` |
| 顶层编排函数 | `FUN_00107598`（37 KB）：解析启动栈 → AT_PAGESZ → argv[0] 校验 → dlopen/dlsym → getenv 分支 → 装载 → 重建初始栈 → 擦 environ → 间接跳转 | 实证 | `LOADER_RE.md` |
| 字符串混淆 | 逐字节与固定常数异或，写法伪装成 `~((c\|k)&(~c\|m))` 等 | 实证 | `LOADER_DECODE.md`：解出全表 |
| 环境变量名 | `TWOYI_ROOTFS`、`TWOYI_SECCOMP_ENDPOINT`、`TWOYI_LINKER_BOOT_DATA_FD`、`TWOYI_GRB_ATTACH_VERSION`、`TWOYI_GUEST_UID|GID`、`TWOYI_CODE_REPORT_FD`、`TYLOADER` | 实证 | 同上，字符串明文 |
| 来宾入口 | `/system/bin/init`，rootfs 固定 `/data/data/io.twoyi/rootfs` | 实证 | 同上 |
| 宿主依赖 | 需要 `libdl_android.so` 与一批「host libc bridge symbols」，缺失即 `required host libc bridge symbols are unavailable` 致命退出 | 实证 | 同上 |
| 不是内核替身 | 自己发 `svc` 数为 0；无 ptrace/mount/namespace 相关导入 | 实证 | `loader-code.txt`；`LOADER_RE.md` |
| 系统调用转发方式 | 通过 `TWOYI_SECCOMP_ENDPOINT` 指向的通道转交宿主 | 推断 | 只有变量名与「bridge」错误串，未见 `seccomp()`/`prctl` 安装过滤器 |
| `B1F2` 请求帧 | 32 字节：0 魔数 `0x32463142`、4 u16 版本 2、6 u16 kind 1、8 u32 status 3、12/16 回显字、20/24 两个句柄 | 实证（部分） | 伪 C + 指令流；魔数/偏移可核 |
| `TBD1` 请求帧 | **3192 字节定长**：0 魔数 `0x31444254`、4 版本 1、6 tag `0x28`、8 序号、68 路径长度、**120 起路径字节**（绝对路径，≤1024），其余清零 | 实证 | `disasm-focus.yml` 的 `aarch64-linux-gnu-objdump` 指令流 |
| `TBD1` 应答帧 | **2168 字节定长**；6 tag 回显、8 序号回显、12 fd（-1 失败）、16 flags（bit0 必需、bit19 后端位）、20 后端位图、24/28 保留 | 推断 | 伪 C 栈槽，光看偏移分不清线上/本地 |
| `B1F2` 句柄语义 | 未知（两个 u32 的「handle」） | 空缺 | — |
| 来宾 ELF 的 relocation 由谁做 | 未定（loader 里未见 `R_AARCH64_RELATIVE` 处理） | 空缺 | — |
| 初始栈之外还传了什么 | 未知（auxv 覆盖项？at_* 改写？） | 空缺 | — |

---

## 2. `libtwoyi.so`（gfxstream / HAL / resolver）

| 项 | 结论 | 等级 | 证据 |
|---|---|---|---|
| 依赖 | 仅 `libandroid/liblog/libc/libm/libdl`，导出仅 `JNI_OnLoad` | 实证 | `linkage.txt` |
| gfxstream | AOSP 13 vulkan-cereal **静态编入**；构建路径含 `stream-servers/{RendererImpl,CompositorVk,DisplayVk,FrameBuffer,PostWorker,address_space_graphics}.cpp` | 实证 | `strings` 命中 |
| 来宾侧驱动 | `renderControl_decoder_context_t`、`rcSetColorBufferVulkanMode(2)` 就在同一个 so 里 | 实证 | `strings` 命中 |
| 缓冲通路 | ColorBuffer + `AHardwareBuffer`（`gfxstream AHB acquire/rejected` 等） | 实证 | `strings` 命中 |
| 端点目录 | Java 传 `rootfs/gfxstream-vulkan`，进程内只能配置一次 | 实证 | `io/twoyi/o.java:21` |
| 目录内文件 | 未知（未找到文件名格式串） | 空缺 | 需设备上 `ls` 或 `strace openat` |
| host callback 表 | 存在（`invalid or unsupported gfxstream host callback table`） | 推断 | 只有错误串 |
| `@twoyi_hal` 分流 | 首包 4 字节小端 `int32` 定模块，连接级回显 ACK | 实证（Java 层） | `HAL_PROTOCOL.md` §1 |
| camera 帧 | 32 字节信封（`TWC2`/version/headerSize/seq）+ 24 字节头 `W,H,NV21,fpsMin,fpsMax,frameBytes`；wire 上无 stride/时间戳/fd | 实证（Java 层） | `HAL_PROTOCOL.md` §3 |
| sensors | 列表 156 B/条；事件 28 B（type i32、ns i64、accuracy i32、3×f32） | 实证（Java 层） | `HAL_PROTOCOL.md` §2 |
| camera opcode 号 | **未知**（jadx `Method dump skipped`） | 空缺 | 需 `--show-bad-code` 或 smali |
| `TwoyiResolverHal` 请求 | 360 字节定长，来宾无魔数无长度 | 实证（Java 层） | `HAL_PROTOCOL.md` §5 |
| `TwoyiResolverHal` 应答 | 988 字节定长，`nativeResolve` 返回，长度严格相等 | 实证（Java 层） | 同上 |
| 360/988 的字段布局 | **未知**，在 native；有 3 个候选模型未证伪 | 空缺 | 同上 |
| `KernelSuHal` | `int32 16` 握手 + 进程内 `dup()` 传 int fd，无 SCM_RIGHTS | 实证（Java 层） | `HAL_PROTOCOL.md` §6 |
| `nativeHandle` 的语义 | 未知 | 空缺 | — |

---

## 3. ROM

| 项 | 结论 | 等级 | 证据 |
|---|---|---|---|
| 容器 | `rootfs/` 单目录 + 归一化 USTAR（uid/gid 0、mtime 1970）+ zstd 单帧（64 MiB 窗口、XXH64）+ 前置 skippable BOM 帧 | 实证 | `ROM_FORMAT.md`；run 45812271640 |
| 构建身份 | `Android/twoyi_arm64/...:8.1.0/OC/builde11130054:user/test-keys` | 实证 | `/system/build.prop` |
| 专有件 | `hwcomposer.twoyi.so`；其余 HAL 为 goldfish 系 | 实证 | `hw/*.so` 清单 |
| 打包器 | `tools/rompack/`，CI 往返验证通过、两次 pack 逐字节一致 | 实证 | run 45823660954 |
| 与官方容器逐字节一致 | 未尝试，不承诺（zstd 版本/参数决定字节） | 空缺 | — |

---

## 4. 一句话结论

**loader 的「请求」这一半已经是指令流级的实证；「应答」和「它让宿主做什么」是推断；
`libtwoyi.so` 的 Java 层接口是实证，native 层（resolver 布局、HAL 帧细节、gfxstream 端点文件）是空缺。**

阶段一继续推进的**唯一高杠杆手段是真机抓包**：一次 `strace/recvmsg` 就能把应答语义、resolver 布局、
`B1F2` 句柄、gfxstream 文件名全部从「推断」变「实证」。继续在本机读伪 C 的边际收益已经很低。
