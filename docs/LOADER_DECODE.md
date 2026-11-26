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

## 已解出的明文（截至本轮）

| 地址（file vaddr） | 明文 | 意义 |
|---|---|---|
| `0x2e0d0` | `invalid guest ex…` | 校验失败时的错误串（后续字节待补） |
| `0x2e36a` | `…OMP_END` | 疑似 `BOOTSTRAP_END` 或 `…COMP_END` 尾部 |
| `0x2e3d2` | `…valid` | 同上族错误串 |
| `0x2e426` | `… attach` | 与 attach 握手相关 |
| `0x2e430` | `…est roo…` | 疑似 `test rootfs…` |
| `0x2ef19` | `/proc/self/fd` | fd 回收路径前缀 |
| `0x2ef50` | `TWOYI_LINKER_BOOT_DATA_FD` | **握手 fd 的环境变量名** |
| `0x2ef90` | `TWOYI_LINKER_BOOT_DATA_FD` | 同上（另一处用法） |
| `0x2efd0` | `TWOYI_GRB_ATTACH_VERSION` | **attach 协议版本号变量**，值须为 `"2"` |
| `0x2effc` | `TWOYI_GUEST_UID` | 来宾 uid |
| `0x2f01c` | `TWOYI_GUEST_GID` | 来宾 gid |
| `0x2f034` | `error: ` | 诊断输出前缀 |
| `0x2f071` | `…WOYI_CODE_REPORT_FD` | 疑似 `TWOYI_CODE_REPORT_FD`，代码报告 fd |

结论层面的收获：loader 与宿主的**契约是环境变量 + fd**，而不是配置文件或固定路径。握手要点是
`TWOYI_LINKER_BOOT_DATA_FD`（SCM_RIGHTS 传进来的 fd）、`TWOYI_GRB_ATTACH_VERSION="2"`、
`TWOYI_GUEST_UID/GID`、`TWOYI_CODE_REPORT_FD`。这与 `docs/LOADER_RE.md` 里 `0x116520` 的 32 字节 `B1F2` 报文、
`0x116df4` 的 3192 字节 `TBD1` 报文正好互为表里。

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