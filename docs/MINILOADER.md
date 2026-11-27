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
| `tools/miniloader/src/image.rs` | 干跑装载：按映射计划把段摆进一块 arena，复制文件字节、bss 清零、给出 entry 在 arena 内的偏移 | `LOADER_RE.md` 的 `0x114560`（mmap 段映射） |
| `tools/miniloader/src/resolver.rs` | 解析器信封：定长 360 请求 / 严格 988 应答 / 一问一答一连接 | `HAL_PROTOCOL.md` §5（Java 层实证） |
| `tools/miniloader/src/main.rs` | 诊断用 CLI：打印段、预留区间、映射表、初始栈大小 | —— |

## 已实现 / 待做

| 项 | 状态 |
|---|---|
| ELF 校验与段提取 | 已实现 + 单测 |
| 映射计划（含 bss、权限） | 已实现 + 单测 |
| 初始栈布局（含 `AT_PAGESZ` 强制、上限） | 已实现 + 单测 |
| 真正的 `mmap` 与跳转 | 待做（需要设备/特权上下文） |
| `B1F2` / `TBD1` 编解码与校验 | 已实现 + 单测（未接入真实 socket） |
| `TBD1` 应答（12 号槽 fd、20 号后端位图） | 已实现 + 单测；是真机抓包前的静态推断 |
| resolver 信封（360 / 988 / 单次连接） | 已实现 + 单测；字节含义未知 |
| `TBD1` 请求（定长 3192：魔数@0、版本@4、tag 0x28@6、序号@8、路径长度@68、路径@120；绝对路径 ≤1024） | 已实现 + 单测，布局由反汇编实证 |
| `TBD1` 应答（定长 2168：回显 tag/序号，状态 0 成功，flags 1，保留字全 0，失败 `EPROTO`=0x47） | 已实现 + 单测，布局由反汇编实证 |
| 宿主 libc bridge（符号重定向） | 待做，见 `LOADER_PROTOCOL.md` 第 6 节的修正 |

## 测试清单（`cargo test`）

`elf` / `map` / `stack`（`tests/plan.rs`，8 项）
- `parses_a_single_executable_segment`：正常解析、`entry_is_executable`
- `rejects_malformed_images`：截断、魔数、非 64 位、大端、非 aarch64、`phentsize` 不符、`memsz<filesz`、段越界
- `plans_page_aligned_mappings_with_bss`：预留区间、页对齐、bss 计算、`entry_map`
- `plan_rejects_bad_pages_and_empty_inputs`：页大小 0、非 2 的幂、空段
- `prot_flags_map_to_the_expected_bits`：`PF_R/W/X` → `PROT_*`
- `stack_layout_is_ordered_aligned_and_terminated`：布局顺序、16 字节对齐、`argc` 值、`apply_base` 后指针能读回字符串、`AT_NULL`
- `stack_rejects_oversized_and_incomplete_inputs`：超上限报 `TooLarge`、缺 `AT_PAGESZ` 报 `MissingPageSize`
- `empty_argv_is_still_a_valid_image`：空 argv 仍是合法镜像

`proto`（`tests/proto.rs`，6 项）
- `b1f2_request_has_the_documented_layout` / `b1f2_round_trips_and_requires_both_handles` / `b1f2_rejects_wrong_magic_version_kind_status_and_size`
- `tbd1_layout_matches_the_disassembly`：3192 字节定长、魔数@0、版本@4、tag 0x28@6、序号@8、路径长度@68、路径@120、未用区域为 0
- `tbd1_rejects_bad_frames`：魔数/版本/tag/空路径/超长/截断/尾随字节
- `tbd1_reply_carries_descriptor_in_slot_12`：12 号槽是 fd（`-1` 失败）、16 号 flags 的必需位、20 号后端位图规则、24/28 保留字必须 0

`handshake`（`tests/handshake.rs`，5 项，`UnixStream::pair()` 起两端线程）
- 握手→exec 全流程、exec 早于 attach 被本地拒、attach 版本不符被发现、句柄为 0 被拒、声明负载超上限被拒

`resolver`（`tests/resolver.rs`，5 项）
- `one_exchange_delivers_the_full_response`：360 进、988 出、内容一致
- `a_short_request_is_reported_not_answered`：只发 100 字节后关闭 → `ShortRequest{got:100}`
- `a_wrong_sized_response_is_refused` / `an_oversized_response_is_also_refused`：987 / 989 字节被拒
- `lengths_match_the_java_layer`：`360` / `988` 常量钉死

`image`（`tests/image.rs`，4 项）+ 合计 28 项。

## CI

`.github/workflows/miniloader.yml`：stable Rust，`cargo fmt --check` → `build` → `test` → `clippy -D warnings` → 跑一次诊断 CLI（对 x86_64 的 `/bin/ls` 应被 `NotAarch64` 干净拒绝）。

## 下一步

1. 把 `MappingPlan` 接到真正的 `mmap`（Android 侧，`app/rs` 或独立二进制）。
2. 把通道换成官方用的 `socketpair` + `SCM_RIGHTS` 传 fd（现在已按消息边界在 UNIX 流上跑通），并在设备上验证。
3. 设计宿主 libc bridge 的最小符号集（`android_set_application_target_sdk_version`、`__android_log_*` 等已知被取用的符号）。
## 真机实测（Termux = Android bionic aarch64，2026-11-27）

Termux 本身就是 Android + bionic + aarch64，装了 Rust 1.100 后可在**目标环境**跑测试与诊断，
这比 Linux CI 更接近真实。以下都是本机实际输出。

### 单元测试

`cargo test` 在 bionic 上 **71 项全过**；其中 `transport`/`session` 原本被 `cfg(target_os = "linux")`
排除，改成 `cfg(unix)` 后才在 Android 上跑起来——也就是说 **`socketpair` + `SCM_RIGHTS` 传 fd
在 bionic 上确实可用**，接收端能 `pread` 出原文件内容。

### 真实系统二进制

| 目标 | 结论 |
|---|---|
| `/system/bin/toybox` | 4 段、313 个导入、**294 个本机可提供**；缺的 19 个是 `gz*`(zlib)、SELinux 与 `__android_log_write`——它们在**独立的 libz/libselinux/liblog** 里，不是 libc |
| `/system/bin/linker64` | **loadable**：4 段、0 重定位、0 导入 |
| `/system/lib64/libc.so` | 1575 条 `R_AARCH64_RELATIVE`、507 导入（本机全可提供）；被判 blocked 只因为共享库的 `entry=0` 不在可执行段 |
| **真实来宾 `rootfs/init`** | **loadable**：2 段、0 重定位、**0 导入（静态链接）**；映射后入口字节 `e0 03 00 91`（AArch64 `add x0, sp, #0`） |

来宾 `init` 的 ELF 头是 `7f 45 4c 46 02 01 01 … 02 00 b7 00`，即 ELF64 小端、`e_machine=0xB7`(aarch64)。

### `DT_NEEDED`（本轮补上）

`toybox` 那 19 个「缺失」暴露的是同一个问题的两种表现：真实动态链接器会先按 `DT_NEEDED`
加载 `libz/libselinux/liblog` 再解析符号，而我们只在当前进程 `dlsym`。现在：

- `symbols::needed_libraries()` 解析 `PT_DYNAMIC`，读 `DT_NEEDED`(1) / `DT_STRTAB`(5) / `DT_STRSZ`(10)，
  按链接期地址在段表里找到字符串表，返回依赖清单；
- `preflight` 增加 `needed` 字段与 `NeedsLibraries` 阻塞项，**不再把「依赖库没加载」误报成
  「符号缺失」**；`--check` 多打印一行 `check: needed=…` 或 `(static)`。

真机实测：

| 目标 | `needed` |
|---|---|
| `/system/bin/toybox` | `libcrypto.so libz.so liblog.so libprocessgroup.so libselinux.so libc.so libm.so libdl.so` |
| 真实来宾 `rootfs/init` | `(static)` |
| `/system/bin/linker64` | `(static)` |

### 真正加载依赖库（本轮）

`SymbolTable` 现在会**打开**这些库并保持句柄（关掉会让表里的地址全部失效）：

- `open_libraries(sonames)`：`dlopen(RTLD_NOW | RTLD_LOCAL)`；`RTLD_NOW` 让缺失依赖在这里
  就暴露，而不是在来宾代码里崩；
- `resolve_names(names)`：**先查已打开的库，再查全局作用域**；返回仍解析不了的名单；
- `preflight` 的 `NeedsLibraries` 只列出**没被打开的**库——打开了就不算阻塞。

真机（bionic aarch64）实测，`--check` 现在对真实系统二进制：

| 目标 | 依赖 | 结果 |
|---|---|---|
| `/system/bin/toybox` | 8 个（libcrypto/libz/liblog/libprocessgroup/libselinux/libc/libm/libdl） | `libraries opened 8/8`、`imports unresolved 0`、**loadable** |
| `/system/bin/sh` | 1 个 | `1/1`、`unresolved 0`、**loadable** |
| `/system/bin/app_process64` | 13 个（含 libandroid_runtime/libbinder/libhidlbase/libnativeloader/libsigchain/libwilhelm/libc++ …） | `13/13`、`unresolved 0`、**loadable** |
| 真实来宾 `rootfs/init` | 无（静态） | **loadable** |

也就是说：**313 个导入的真实 Android 二进制，我们的 loader 能把它们全部解析出来**。

仍缺：这些库是交给系统的 `dlopen` 打开的，我们**没有实现自己的 `DT_NEEDED` 映射器**
（真机上 bionic 的 linker 替我们做了这件事）；以及 TLS、`dlopen` 语义、跳转执行。

## 跳转执行（本轮打通）

`run::run()` 把镜像真正跑起来，**在 fork 出的子进程里**（真 loader 也是这么起进程的：
来宾的 `exit` 只结束子进程，父进程能如实报告退出码，而且崩掉的镜像不会带走加载器）。

子进程里的顺序是：映射镜像（含重定位与跳转槽）→ 用**实际映射偏置**生成 `argv/envp/auxv`
（auxv 里全是镜像内地址，偏置不知道就全错）→ 把栈镜像拷到 mmap 出来的 1 MiB 栈区、
用 `apply_base` 把字符串指针变成绝对地址 → `mov sp, x9; blr x10`。

**本机 bionic aarch64 实测（`cargo test`，5 项）**：

| 用例 | 结果 |
|---|---|
| 手写 AArch64 来宾 `write("hi\n")` + `exit(42)` | `Exited(42)`，且 `hi` 出现在输出里 |
| 来宾读 `[sp]` 取 `argc` 并以此为退出码 | `Exited(3)` —— **初始栈确实是加载器搭的那个** |
| 来宾执行 `brk #7` | `Signaled(5)` (SIGTRAP)，崩溃被如实上报 |
| 传非 ELF | `Unloadable`，**不 fork**，进程不受影响 |
| 把 stdout 接到管道 | 管道里读到 `captured\n`，来宾的 `write` 生效 |

## 真实二进制跳到哪一步了

| 目标 | 结果 |
|---|---|
| 手写最小来宾 | **跑通**（见上表） |
| `/system/bin/toybox` | 依赖 `8/8` 打开、313 个导入 `0 unresolved`、镜像映射成功，但**跳转后 SIGSEGV** |
| 真实来宾 `rootfs/init` | 映射成功（静态、0 导入），跳转后 **SIGSEGV** |

两者都倒在同一个地方：**没有建立 TLS**。bionic 的程序（静态或动态）在 `__libc_init` 里
要用 `TPIDR_EL0` 指向线程控制块，从 `PT_TLS` 复制初始镜像；我们跳过去时 `TPIDR_EL0` 是垃圾值，
第一条真正的 libc 代码就崩。这就是下一个要实现的东西。

## 崩溃地址取证（本轮）

`run::run_diagnosed()` 在子进程里装 `SA_SIGINFO` 处理器，把 `si_addr` 通过管道送回父进程
（信号处理器里不能分配，所以不走 stderr 之外的复杂路径）。它同时修掉两个真 bug：

1. **栈镜像位置错了**：镜像原本放在栈区**底部**、`sp` 指向它，于是任何一次压栈（包括信号处理器
   自己的）都会写到映射区**下方**；现在镜像贴到区域**顶部**，栈向下有 1 MiB 空间。
2. **处理器退出码掩盖了信号**：处理器 `_exit(128+signal)`，父进程看到的是 `Exited(139)` 而非
   `Signaled(11)`；现在会翻译回去。

真机实测（本机 Android bionic aarch64）：

| 目标 | 结果 |
|---|---|
| 手写来宾 `str x0,[x0]` | `Signaled(11)` + **`address=0x0`**（正是写空指针） |
| 手写来宾 `write+exit` | `Exited(0)`，无故障 |
| 真实来宾 `rootfs/init` | `Signaled(11)`，**`address=0x200000`** |
| `/system/bin/toybox` | `Signaled(11)`，**`address=0x35230`** |

两个真实二进制都停在**很低的地址**上，与「`TPIDR_EL0` 没有被设置成来宾自己的线程控制块」一致：
fork 之后它仍指向宿主 Termux 线程的 TCB，来宾按自己的 TLS 偏移去访问就落进乱七八糟的地方。
这就是 TLS 必须实现的原因，现在有地址为证。

## TLS / TCB：试到哪一步，以及为什么不能硬来

看到崩溃地址后先加了两个能力，再尝试 TLS：

1. `run_diagnosed()` 现在从 `ucontext` 里取 **PC**，`--example runinit` 会把 PC 换算成文内偏移。
   真实来宾 `init` 崩在 `pc=0x5f`、`address=0x200000`；`toybox` 崩在 `address=0x35230`。
2. `Elf::tls`：解析 `PT_TLS`。实测两者都**没有可用的 TLS 镜像**——
   `toybox` 的 `PT_TLS` 是 `memsz=0`，Android 8.1 的静态 `init` 干脆没有 `PT_TLS`。

### 自造 TCB 会把宿主 libc 弄崩

按 variant I 的布局分配块、把 `PT_TLS` 初始镜像拷进去、`msr tpidr_el0` 指过去之后，
**我们的诊断打印自己就崩了**：换成 `Exited(126)`/`SIGSEGV`，且信号处理器都进不去。

原因很直接：bionic 把 `errno` 和它自己的槽位放在**线程指针后面**，一个全零的块不满足这个布局，
于是切过去之后**同一线程里的任何宿主 libc 调用**（哪怕是 `write`）都会踩到坏地址。
自造 TCB 不是 bionic TCB。

### 现在的做法与代价

- TCB 块仍然分配，但**只有来宾确实有 `PT_TLS`（`memsz > 0`）时才切 TP**，并且切换是最后一步、
  之后不再调用宿主 libc；
- 因此 `toybox` 与真实 `init` 保持继承宿主的 TP，行为和上一轮一致（在自己的地方崩，我们能报地址）。

**结论**：真实 bionic 程序要跑起来，需要一个**符合 bionic 布局的 TCB**（`pthread_internal_t`、
`bionic_tls`、`dtv`），这不是猜得出来的，得从 bionic 的头部信息里取；在此之前 TLS 这一项是空缺。

## 第一个真实 bionic 程序跑起来了（本轮）

不再猜 TLS，而是换了一个可控的调试对象：用 Termux 的 clang 编一个**动态链接的 bionic 程序**
（`hello`，5 个导入），它的符号正好由同一套宿主 bionic 提供，于是最小化变量。

1. 加 `run_traced()`：父进程 **ptrace** 子进程，`PTRACE_GETREGSET` 取 `user_regs_struct`
   （`x0..x30/sp/pc`），`PTRACE_GETSIGINFO` 取 `si_addr`。这样**来宾自己装信号处理器也挡不住**取证。
   寄存器一看就清楚了：`pc = entry+0x120`、`x8 = 0`、`si_addr = 0`。
2. 对着反汇编看那条指令：`ldr x0, [x8]`（读 `stdout`）——`x8` 是 `stdout` 的 GOT 槽，值为 0。
3. 根因：`stdout` 是**数据符号**，走 `R_AARCH64_GLOB_DAT`(1025)，而我只处理了
   `R_AARCH64_JUMP_SLOT`(1026)。**数据导入全都没解析**。

补上 `GLOB_DAT` 之后：

```
$ cargo run --example runinit -- hello a b
libraries: 2/2 opened
imports: 0 unresolved of 6
hello from bionic, argc=3          <-- 来宾自己打印的
outcome: Exited(7)                 <-- 来宾自己的退出码
```

**这是第一个真实 bionic 二进制在自研 loader 上跑完并正确退出**，argv 也正确（argc=3）。

## 还有两种真实镜像不能装（已如实报告）

| 目标 | 现状 | 原因 |
|---|---|---|
| 手写 guests、`hello` | **跑通** | 普通 `DT_RELA` 表 |
| `/system/bin/toybox` | preflight **blocked** | 用 **`DT_RELR`**（压缩相对重定位），我们读不了 |
| 真实来宾 `rootfs/init` | preflight **blocked** | **`ET_EXEC`**，必须映射到链接地址 `0x400000` |

`preflight` 现在多了两个阻塞项 `NeedsFixedMapping` 与 `UnreadableRelocations`，
不再是「莫名 SIGSEGV」，而是明确的「这个镜像我还没法装，因为 …」。

## `ET_EXEC` 固定地址映射（本轮）

真实来宾 `init` 是 `ET_EXEC`：它的绝对指针在文件里已经是最终地址，没有任何重定位能补救，
所以**必须映射到链接地址**。新增 `MappedImage::map_fixed()`，用 `MAP_FIXED_NOREPLACE`
（`0x100000`）请求 `reserve_start`：

- 范围已被占用时**请求失败**，不会悄悄替换别人的映射（有测试覆盖）；
- `preflight` 只在链接地址低到不可映射（`< 0x10000`，即基址为 0 的镜像）时才判阻塞。

实测：

```
$ miniloader-plan guestrom/init 4096 --check
check: loadable

$ cargo run --example runinit -- guestrom/init
outcome: Signaled(11) fault: signal=11 address=0x200000 pc=0x4ba430 ...
  pc is at file offset 0xba430
```

`pc=0x4ba430` **落在镜像自己的地址范围里**（上一轮是 `0x5f` 这种垃圾值），说明引导成功、
真实 Android 8.1 `init` 的代码真的在跑；它随后去碰了 `0x200000` 这个未映射地址——
完整启动一个 `init` 还需要 `/dev`、`/proc`、mount 等环境，这属于运行时该做的事。

## 当前三种真实镜像的状态

| 目标 | preflight | 实际运行 |
|---|---|---|
| 手写 guests | loadable | **跑通**（退出码/输出/argv 都对） |
| 动态链接 bionic `hello` | loadable | **跑通**（打印 + `Exited(7)`） |
| 真实来宾 `rootfs/init`（ET_EXEC） | loadable | 引导成功，跑到自身代码后因缺少运行环境而崩 |
| `/system/bin/toybox`（`DT_RELR`） | blocked | 未实现 `DT_RELR` 解析 |
