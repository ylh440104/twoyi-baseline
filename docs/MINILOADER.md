# 最小 loader 骨架（阶段二）

自研运行时的第一步：把**可以脱离设备验证**的部分先做出来，并让 CI 每轮都能证明它们是对的。

## 模块

| 文件 | 职责 | 与官方 loader 的对应 |
|---|---|---|
| `tools/miniloader/src/elf.rs` | ELF64 解析与校验：magic、`EI_CLASS=2`、`EI_DATA=1`、`e_machine=0xB7`、`e_phentsize=0x38`；收集 `PT_LOAD` 段，拒绝 `memsz<filesz`、越界、截断 | `docs/LOADER_RE.md` 的 `0x113a70`（Ehdr/Phdr 校验）、`0x114330`（vaddr→PT_LOAD） |
| `tools/miniloader/src/map.rs` | 生成映射计划：整跨度 `PROT_NONE` 预留、逐段页对齐映射、`bss` 长度、`prot_for(flags)` | `0x114560`（mmap + mprotect 合并） |
| `tools/miniloader/src/stack.rs` | 构造来宾初始栈镜像：`argc` / `argv` / `envp` / `auxv`（`AT_NULL` 结尾）/ 字符串区，16 字节对齐与上限 | `0x115a44`（来宾初始栈）、错误串 `guest initial stack is too large` |
| `tools/miniloader/src/proto.rs` | `B1F2`（32B 握手）与 `TBD1`（32B 头 + ≤0xC00 负载）编解码与校验 | `LOADER_PROTOCOL.md` 的 `FUN_00116520` / `FUN_00116df4` |
| `tools/miniloader/src/broker.rs` | 通道两端：`HandshakeClient`（attach → exec）与 `Broker`（应答握手、校验 `TBD1`、回执负载长度），传输可为任意 `Read+Write` | `FUN_00116520` / `FUN_00116df4` |
| `tools/miniloader/src/main.rs` | 诊断用 CLI：打印段、预留区间、映射表、初始栈大小 | —— |

## 已实现 / 待做

| 项 | 状态 |
|---|---|
| ELF 校验与段提取 | 已实现 + 单测 |
| 映射计划（含 bss、权限） | 已实现 + 单测 |
| 初始栈布局（含 `AT_PAGESZ` 强制、上限） | 已实现 + 单测 |
| 真正的 `mmap` 与跳转 | 待做（需要设备/特权上下文） |
| `B1F2` / `TBD1` 编解码与校验 | 已实现 + 单测（未接入真实 socket） |
| `TBD1` 应答（状态 + fd 大小 + EPROTO） | 已实现 + 单测；真实 fd 传递待做 |
| 宿主 libc bridge（符号重定向） | 待做，见 `LOADER_PROTOCOL.md` 第 6 节的修正 |

## 测试清单（`cargo test`）

- `parses_a_single_executable_segment`：正常解析、`entry_is_executable`
- `rejects_malformed_images`：截断、魔数、非 64 位、大端、非 aarch64、`phentsize` 不符、`memsz<filesz`、段越界
- `plans_page_aligned_mappings_with_bss`：预留区间、页对齐、bss 计算、`entry_map`
- `plan_rejects_bad_pages_and_empty_inputs`：页大小 0、非 2 的幂、空段
- `prot_flags_map_to_the_expected_bits`：`PF_R/W/X` → `PROT_*`
- `stack_layout_is_ordered_aligned_and_terminated`：布局顺序、16 字节对齐、`argc` 值、`apply_base` 后指针能读回字符串、`AT_NULL`
- `stack_rejects_oversized_and_incomplete_inputs`：超过上限报 `TooLarge`、缺 `AT_PAGESZ` 报 `MissingPageSize`
- `empty_argv_is_still_a_valid_image`：空 argv 仍是合法镜像
- `proto` 追加：`tbd1_reply_carries_status_size_and_echoes`（成功/失败包络、`EPROTO=0x47`）、`tbd1_reply_rejects_bad_envelopes`
- `handshake`（`tests/handshake.rs`，用 `UnixStream::pair()` 起两端线程）：握手→exec 全流程、exec 早于 attach 被本地拒绝、attach 版本不符被发现、句柄为 0 被拒、声明的负载超上限被 broker 拒绝
- `proto`（`tests/proto.rs`）：`B1F2` 布局与魔数、往返、拒绝错误 magic/version/kind/status/长度；`TBD1` 头字段、往返、保留字必须为 0、负载上限 `0xC00`、声明长度与实际不符被拒

## CI

`.github/workflows/miniloader.yml`：stable Rust，`cargo fmt --check` → `build` → `test` → `clippy -D warnings` → 跑一次诊断 CLI（对 x86_64 的 `/bin/ls` 应被 `NotAarch64` 干净拒绝）。

## 下一步

1. 把 `MappingPlan` 接到真正的 `mmap`（Android 侧，`app/rs` 或独立二进制）。
2. 把通道换成官方用的 `socketpair` + `SCM_RIGHTS` 传 fd（现在已按消息边界在 UNIX 流上跑通），并在设备上验证。
3. 设计宿主 libc bridge 的最小符号集（`android_set_application_target_sdk_version`、`__android_log_*` 等已知被取用的符号）。