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

## 5. 仍未定

1. `local_15fa`/`local_15f8` 的比较对象来源（应为握手应答里的两个字段，需在 `FUN_00116520` 的调用链上确认）。
2. 负载 3160 字节里各字段的切分（路径、argv、env、auxv 覆盖项？）。
3. 应答包结构：目前只确认「回显长度」，其余字节语义未知。

## 5. 复现

```bash
# CI: .github/workflows/ghidra-loader.yml
# 产出 loader-decompiled.txt 后，用同一套伪 C 地址检索：
grep -n 'FUN_00116520\|FUN_00116df4\|0x31444254\|0x32463142' loader-decompiled.txt
```