# loader 与宿主的握手协议（阶段一 · 协议）

本篇把 `docs/LOADER_RE.md` 里两个握手函数拆到字段级。全部依据 Ghidra 伪 C（run 45819687672 / 45826364742 的 artifact），地址为伪 C 地址。

## 1. 第一级：`B1F2` broker 握手（`FUN_00116520`）

- 建通道：`socketpair(1, 0x80005)` → `AF_UNIX` + `SOCK_SEQPACKET | SOCK_CLOEXEC`。
- 发 32 字节请求，首 4 字节 `42 31 46 32` = `"B1F2"`（小端 int32 = `0x32463142`）。
- 用 **SCM_RIGHTS** 附带一个 fd（`FUN_001175b8`：`cmsg level=1 type=1 len=20`）。
- 收 32 字节应答并取回 fd，校验：
  - 魔数 `0x32463142`（"B1F2"）
  - `[4:6] == 2`（版本）
  - `[6:8] == 1`（类型）
  - `[8:12] == 3`（状态/能力）
  - 回显两字段、且两个句柄非 0
- 收发前用 `fstat` 断言对端是 socket（`S_IFSOCK`）并 `getsockname/getpeername`（`FUN_00117360`）。

**语义推断（依据字符串表，见 `LOADER_STRING`/`LOADER_DECODE.md`）**：这是与 **guest rootfs broker / libc bridge** 建立控制通道，返回的 fd 就是后续 seccomp 通知与资源请求用的句柄。对应环境变量 `TWOYI_LINKER_BOOT_DATA_FD`、`TWOYI_GRB_ATTACH_VERSION="2"`。

## 2. 第二级：`TBD1` 执行参数块（`FUN_00116df4`）

每次 ELF 装载前调用（由 `FUN_00110808` 触发），发 **0xC78 = 3192 字节** 单包（SEQPACKET 语义，非流）。

栈上布局（伪 C 局部变量 → 偏移）：

| 偏移 | 宽度 | 变量 | 校验/取值 | 含义 |
|---:|---|---|---|---|
| 0x00 | 4 | `local_1600` | 必须 `0x31444254`（"TBD1"） | 魔数 |
| 0x04 | 2 | `local_15fc` | 必须 `== 1` | 版本 |
| 0x06 | 2 | `local_15fa` | 与 `sVar7` 比较（本地回显） | 序号/标签 |
| 0x08 | 4 | `local_15f8` | 与 `iVar12` 比较（本地回显） | 关联 id |
| 0x0c | 4 | `local_15f4` | 必须 `< 0x1000`；某分支置 `0x47` | 负载长度（≤4095） |
| 0x10 | 4 | `local_15f0` | 必须 `== 1` | 标志位（"attach"/"exec"） |
| 0x14 | 4 | `local_15ec` | 必须 `== 0` | 保留 |
| 0x18 | 4 | `local_15e8` | 必须 `== 0` | 保留 |
| 0x1c | 4 | `uStack_15e4` | 必须 `== 0` | 保留 |
| 0x20 | 3160 | `auStack_cf8` | 由 `__memcpy_chk(..., 0xc00)` 填充（上限 3072） | 负载：路径/参数等 |

要点：
- **头是 32 字节**，与 `B1F2` 的 32 字节对称；两个协议都以「4 字节魔数 + 版本 + 保留位」开头，属于同一套设计。
- 载荷长度字段受 `< 0x1000` 约束，且 `memcpy` 上限 `0xC00`，说明**单包上限就是 4096**。
- 应答方向：函数末尾把 `local_15f4` 写回 `*puVar17`（调用方传入的响应结构），即**应答回显长度**。

## 3. 与 Java 层的对照

- 控制面 `@TWOYI_SOCK` 是**文本命令**（`PING`、`BOOT_COMPLETED` 等，见 `docs/HAL_PROTOCOL.md`），与这两个二进制协议不是一条通道。
- `@twoyi_hal` 的 `int32 type` 分流也与这两个协议无关：`B1F2`/`TBD1` 只存在于「loader ↔ 宿主 broker」之间。

## 4. `main` 的 GOT 槽已解（`.rela.dyn` 实测）

`.rela.dyn`（vaddr `0x1370`）共 50 条 `R_AARCH64_RELATIVE`（type 1027），实测节选：

| 槽位（file offset） | addend | 结论 |
|---|---|---|
| `0x29be8` | `0x1f208` | `.init_array[0] = _INIT_0`（与 `LOADER_RE.md` 一致） |
| `0x29d80` | `0x5e30` | `entry` |
| `0x29d88` | `0x5e30` | 与 entry 同址（备用槽） |
| **`0x29d90`** | **`0x5f20`** | **`main = FUN_00105f20`（此前为推断，现已证实）** |
| `0x29da8` | `0x2fa40` | 自建导入表基址（`LOADER_RE.md` 的 `_INIT_0` 目标） |
| `0x29bd0` | `0x2ee40` | 指向字符串 `TYLOADER=` 的槽 |
| `0x29bd8` | `0x2ee5c` | 指向 `TWOYI_ROOTFS=` 的槽 |

也就是说：`__libc_init(..., PTR_FUN_00129d90, ...)` 的第三参确实是 **main 槽**，`entry → main = FUN_00105f20(argc, argv)` 成立，不再是推测。
另外可见 GOT 里直接存放了 `TYLOADER=`、`TWOYI_ROOTFS=` 等字符串的地址，说明这些环境项是由 **main 直接拼装** 的（配合 `LOADER_DECODE.md` 的解码结论）。


## 5. 「seccomp 端点」到底是什么（本轮修正）

对两个 `.so` 做了完整的导入符号核查后，结论需要修正：

| 事实 | 证据 |
|---|---|
| `libloader.so` **没有** `connect/bind/listen/accept/prctl/ioctl` 导入 | 伪 C 里这些符号引用数为 0；只有 `sendmsg/recvmsg/fcntl/readlink` |
| `libloader.so` 唯一的 `socketpair` 用于 `B1F2` 握手 | 伪 C 行 4774 |
| `libtwoyi.so` **没有** `seccomp` 导入，也没有任何 `seccomp` 字符串 | 导入表只有 `prctl/ioctl/socketpair/sendmsg/recvmsg`；`strings` 里 seccomp 命中数为 0 |
| `libtwoyi.so` 只有 `prctl(0x26,1)`（`PR_SET_NO_NEW_PRIVS`）与 `prctl(4,1)`（`PR_SET_DUMPABLE`） | 过滤反编译结果行 51366 / 49359 |

也就是说：**没有任何一方安装 seccomp 过滤器**。结合 loader 里的致命错误串
`required host libc bridge symbols are unavailable`，更自洽的解释是：

> 这不是内核 seccomp，而是 **libc 符号桥（libc bridge）**：loader 把来宾
> `/system/bin/init` 的 libc 依赖重定向到宿主提供的实现，经 `B1F2` 建立的控制通道
> 请求服务；`TWOYI_SECCOMP_ENDPOINT` 只是这条通道在环境里的名字（用于让来宾的
> libc 初始化路径认为自己运行在受限环境），`PR_SET_NO_NEW_PRIVS` 由宿主侧面设置。

这条修正影响自研路线：**我们不需要实现 seccomp user-notify**，而是要实现一个
「宿主 libc 服务端 + loader 侧的导入重定向」。工作量比 seccomp 方案小，且完全在用户态。

## 6. 宿主侧反编译流水线（新增）

`ghidra-tinyi.yml` 用 `DECOMP_FILTER` 只反编译引用了 `prctl/socketpair/recvmsg/sendmsg/ioctl/JNI_OnLoad`
的函数：1.34 分钟级排队后约 20 分钟完成，产出 2.5 MB 伪 C、12,489 个函数头，其中
`JNI_OnLoad @ 0x2c5310 size=94920`。这份产物用于继续定位 `@twoyi_hal` 服务端与 gfxstream transport。


## 7. `TBD1` 布局的还原过程（已被 §10 取代）

先前的两版推断（应答语义、路径落在偏移 0 或 40）都建立在 Ghidra 伪 C 的栈槽类型上，
而伪 C 对这些槽位的判定自相矛盾、闭环不了。结论以 §8 的**指令流**为准：
路径在偏移 120，tag 在偏移 6，请求 3192 字节、应答 2168 字节。
这里不再保留推测内容，避免被误用。

## 8. `TBD1` 定案（反汇编实证）

上一节标记为「未证实」的两个问题，已由 `aarch64-linux-gnu-objdump` 的指令流定案
（run 45842864007，`libloader.so` 的 `FUN_00116df4`，Ghidra 地址 0x116e.. 对应 vaddr 0x16e..）：

```
16ef0c:  mov  x9, #0x11ad
16f10:   mov  w8, #0x28
16f1c:   strh w8, [x24, #6]      <- 偏移 6：恒定 tag 0x28
16f2c:   str  w26, [x24, #68]    <- 偏移 68：路径长度
16f3c:   bl   __memcpy_chk      <- 目的 x0 = x24 + 0x78 = 偏移 120：路径字节
16f64:   mov  w10, #0x4254 ; movk w10, #0x3144  -> 0x31444254
16f7c:   str  w10, [x24]         <- 偏移 0：魔数
16fbc:   strh w12, [x24, #4]     <- 偏移 4：版本 1
16fac:   str  w8,  [x24, #8]     <- 偏移 8：序号
16f9c:   mov  w9, #0xc78 ; ... x2 = x9      <- 发送长度 3192
16ff4:   x2 = 0x878              <- 接收长度 2168
```

**请求帧（3192 字节）**

| 偏移 | 宽度 | 含义 |
|---:|---|---|
| 0 | u32 | 魔数 `0x31444254`（"TBD1"） |
| 4 | u16 | 版本 `1` |
| 6 | u16 | 恒定 tag `0x28` |
| 8 | u32 | 序号 |
| 68 | u32 | 路径长度（字节） |
| 120 | … | 路径字节（绝对路径，≤1024） |
| 其余 | — | 0（发送前 `memset` 0xC70） |

**应答帧（2168 字节）**

| 偏移 | 宽度 | 含义与校验 |
|---:|---|---|
| 0 | u32 | 魔数须为 `0x31444254` |
| 4 | u16 | 版本须为 `1` |
| 6 | u16 | tag 回显（与请求一致，取值为本地变量 `local_d6a`） |
| 8 | u32 | 序号回显 |
| 12 | u32 | 状态；`0` 为成功，非 0 即 errno |
| 16 | u32 | 标志；成功时须为 `1` |
| 20/24/28.. | — | 保留，必须全 0 |

失败时 loader 把 `errno` 置 **`0x47` = 71 = `EPROTO`**。

> 与上一节的差异说明：上一节把偏移 6 的 `0x28` 说成"可能是头长 40"，把路径放到了 40。反汇编证明
> `strh` 写入偏移 6、路径长度写偏移 68、`memcpy` 目的为 `x24+0x78`，**路径起始于 120，`0x28` 只是一个
> 恒定 tag**。同时接收长度是 **0x878 = 2168**，不是 0x888 = 2184（上一节读的是栈帧基址而非长度）。
> 依据指令流，本章为准。

## 9. 复现

```bash
# CI: .github/workflows/ghidra-loader.yml
# 产出 loader-decompiled.txt 后，用同一套伪 C 地址检索：
grep -n 'FUN_00116520\|FUN_00116df4\|0x31444254\|0x32463142' loader-decompiled.txt
```