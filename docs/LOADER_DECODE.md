# loader 字符串解码（阶段一 · 突破）

`libloader.so` 的 `getenv` 参数在 `.data` 里被逐字节混淆，静态看不到明文。本篇记录**如何把它还原出来**，以及已经解出的内容。

## 混淆方式（已实证）

`FUN_00105f20` 内是一串逐字节赋值，形式上各不相同，但**每个字节都等价于与该位置的一个固定常数异或**：

```c
DAT_0012e0f0 = DAT_0012e0d0 ^ 0x59;
DAT_0012e0f1 = ~((DAT_0012e0d1 | 0xb) & (DAT_0012e0d1 ^ 0xff | 0xf4));   // == c ^ 0xf4
DAT_0012e0f3 = (DAT_0012e0d3 ^ 0xff) & 0x7d | DAT_0012e0d3 & 0x82;       // == c ^ 0x7d
DAT_0012e0f5 = DAT_0012e0d5 ^ 0xa2;
DAT_0012e0f6 = ~DAT_0012e0d6 & 0xef | DAT_0012e0d6 & 0x10;               // == c ^ 0xef
```

推导例：`~((c|k) & (~c|m))` 展开为 `(~c & ~k) | (c & ~m)`，取 `k=0x0b, m=0xf4` 即 `(~c & 0xf4) | (c & 0x0b) = c ^ 0xf4`。其余几类同理，都是异或的伪装写法。**每个字符串的常数序列各不相同**（不能跨字符串复用 keystream，已实测验证）。

## 还原方法

1. 从 Ghidra 伪 C 里抓所有形如 `DAT_dst = <只引用一个 DAT_src 的表达式>;` 的赋值；
2. `DAT_src` 的地址减去 image base（`0x100000`）就是文件虚拟地址，从 `tools/ghidra/dump_ranges.py` 导出的 hex 里取该字节；
3. 把表达式里的 `DAT_src` 替换成字节值、`~` `&` `|` `^` 按位求值，得到该位置的明文字节；
4. 连续目的地址拼成字符串。

这一步完全在文本层完成，不需要再跑 Ghidra。

## 已解出的明文（全表，本轮补全 `.data` 窗口后）

| file vaddr | 明文 | 意义 |
|---|---|---|
| `0x2e0f1` | `invalid guest executable path` | 入口校验失败 |
| `0x2e1c0` | `invalid initial process stack` | 启动栈校验 |
| `0x2e200` | `invalid or missing AT_PAGESZ` | 依赖 auxv 的 `AT_PAGESZ` |
| `0x2e290` | `invalid transport argument block` | 传输参数块 |
| `0x2e300` | `required host libc bridge symbols are unavailable` | **宿主 libc 桥**缺失即致命 |
| `0x2e360` | `TWOYI_SECCOMP_ENDPOINT` | **seccomp 端点环境变量** |
| `0x2e3d0` | `invalid seccomp endpoint` | 端点参数不合法 |
| `0x2e426` | `cannot attach guest rootfs broker: errno %d` | 连接 **rootfs broker** 失败 |
| `0x2e470` | `/data/data/io.twoyi/rootfs` | 固定 rootfs 路径 |
| `0x2e4b0` | `cannot open guest rootfs` | 打开 rootfs 失败 |
| `0x2e500` | `android_set_application_target_sdk_version` | 从宿主 libc 取的符号 |
| `0x2e560` | `guest initial stack is too large` | 初始栈上限 |
| `0x2e5c0` | `cannot rebuild guest initial stack` | 重建初始栈失败 |
| `0x2e610` | `/system/bin/init` | 被装载的来宾入口 |
| `0x2e660` | `cannot reset process state for guest exec` | exec 前状态复位 |
| `0x2e6b0` | `libdl_android.so` | 被 dlopen 的宿主库 |
| `0x2e710` | `cannot resolve guest ELF %s: error %d` | 来宾 ELF 解析失败 |
| `0x2e780` | `cannot inspect broker ELF %s: error %d` | broker 侧 ELF 解析失败 |
| `0x2e840` | `cannot map guest ELF %s: error %d` | 段映射失败 |
| `0x2eda0` | `TWOYI_SECCOMP_ENDPOINT=` | 环境项原型 |
| `0x2edc0` | `TWOYI_ROOTFS=/data/data/io.twoyi/rootfs` | 环境项原型 |
| `0x2ee20` | `/proc/self/fd/%d` | fd 路径构造 |
| `0x2ee40` | `TYLOADER=` | **与 0.5.4 开源实现同名的环境变量** |
| `0x2ef50` | `TWOYI_LINKER_BOOT_DATA_FD` | 握手 fd 的环境变量名 |
| `0x2efd0` | `TWOYI_GRB_ATTACH_VERSION` | attach 协议版本（值须为 `"2"`） |
| `0x2effc` | `TWOYI_GUEST_UID` / `TWOYI_GUEST_GID` | 来宾身份 |
| `0x2f071` | `TWOYI_CODE_REPORT_FD` | 代码报告 fd |

## 由此确立的架构结论

loader 不是“内核替身”，而是一个 **ELF 装载器 + seccomp 客户端**：

1. 它把 `/system/bin/init` 按 ELF 段映射进内存（`0x113a70`/`0x114560`），重建初始栈，然后跳转；
2. 所有“内核缺失”的系统调用，靠 **`TWOYI_SECCOMP_ENDPOINT` 指向的 seccomp user-notify 端点**交给宿主的 **rootfs broker / libc bridge** 处理；
3. 宿主侧要提供 `libdl_android.so` 及一批 **libc bridge 符号**，否则 loader 直接 `required host libc bridge symbols are unavailable` 致命退出；
4. 两个 fd 通过环境变量与 SCM_RIGHTS 传进来：`TWOYI_LINKER_BOOT_DATA_FD`（启动数据）与 `TWOYI_CODE_REPORT_FD`（代码上报）。

这解释了此前所有“loader 里 `svc` 数为 0”“找不到 ptrace/mount”的观察：**它不是自己伪造 syscall，而是让 seccomp 把 syscall 转给宿主**。同时也说明 `docs/LOADER_RE.md` 里 `0x116520` 的 32 字节 `B1F2` 报文就是 **broker 握手**，`0x116df4` 的 3192 字节 `TBD1` 报文是**执行前的参数块**。

## 仍缺

1. 未补全的短串（`invalid guest ex…` 的后半、`…OMP_END` 的前半）：`.data` 里 `0x2e1a0-0x2e340`、`0x2e420-0x2e590`、`0x2e700-0x2e900`、`0x2ed00-0x2ef00` 这几段上一轮没导出，已扩大 dump 范围重跑。
2. `main` 的 GOT 槽 `0x129d90` 指向：`.got.plt` 里是未重定位的 `0x25860`（PLT0），真值在 `.rela.dyn`；已加 `.rela.dyn` dump。
3. `TBD1` 3192 字节负载的字段语义：需要把这 3192 字节与 loader 侧组装代码对上。

## 复现

```bash
# CI 里 (ghidra-loader.yml) 产出 loader-bytes.txt 后，本地/云端均可跑同一套解码逻辑
python3 tools/ghidra/dump_ranges.py libloader.so --ghidra-base=0x100000 0x12e000-0x12f300
```

解码脚本尚未入库（本轮是临时脚本）；下一轮会把它固化成 `tools/ghidra/decode_strings.py` 并给出 CI 校验。