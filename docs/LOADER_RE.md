# libloader.so 启动契约还原（阶段一 · loader）

来源：官方 `https://dl.twoyi.ai/twoyi_0.7.5-11131322.apk` 中的 `lib/arm64-v8a/libloader.so`。
素材：GitHub Actions run `45819687672` 的 artifact `loader-decompiled`
（Ghidra 11.0.3 headless 全量伪 C，560,874 B / 14,231 行 / 262 个函数），以及同一流程产出的
`loader-code.txt`（readelf 摘要：段表、导入符号、`.init_array` hex、裸 `svc` 计数）、
`loader-strings.txt`、`linkage.txt`。
APK／`.so`／伪 C 全文都不入仓，本文只保留必要片段。

**地址约定**：伪 C 中 Ghidra image base = `00100000`，故 `Ghidra 地址 = ELF 虚拟地址 + 0x100000`
（例：ELF entry `0x5e30` ↔ `00105e30`）。下文的“地址”默认指伪 C 里的地址。

---

## 1. 结论摘要

1. **`.init_array` 只有一项**：`_INIT_0 @ 0011f208`。它的职责是把 GOT 里的 libc 函数指针
   （`dladdr` / `dl_iterate_phdr` / `malloc` 族 / `pthread` 族 / `fork` / `clone` / `exit` /
   `getaddrinfo` 族…）拷进私有表 `0012fa40..0012fc50`，即**自建导入表（IAT 擦洗）**，
   供后续所有间接调用使用——这也是为什么伪 C 里 `fork`/`clone`/`dl_iterate_phdr` 找不到直接 xref。
2. `entry → FUN_00105e44 → __libc_init(raw_args, 0, PTR_FUN_00129d90, &structors)` 是标准 bionic
   `_start`；第三参 `00129d90` 是 `.got.plt` 里 **main 的 GOT 槽**（紧邻 `00129d98=dlopen`、
   `00129da0=dlclose`）。按实参与行为判断，`main = FUN_00105f20(argc, argv)`。
3. init 之后实际依次执行：`_INIT_0`（导入表）→ `main=FUN_00105f20`（解码 30 字节错误串、
   调 `FUN_00106a9c` 一/两次）→ `FUN_00107598(argv[0], argc, argv)`（不返回）。
   `FUN_00105f20` 末尾的 `FUN_00117c3c(1,&DAT_0012e0f0)` 只用于**异常兜底**（`argc<=0`
   或 `argv[0]==NULL` 等），正常流程走不到；`_exit` 在 `FUN_00117c3c` 内。
4. **`FUN_00107598`（37,488 B）不是 ELF 解析器，是 loader 的顶层编排**：解析启动栈 →
   `AT_PAGESZ` → 校验 argv[0] → `dlopen`/`dlsym`(RTLD_NOW) → 读环境 → 二选一分支
   （“打开 rootfs 目录后按路径装载”／“先握手再装载”）→ 组装来宾入口参数块 → 搭来宾初始栈 →
   擦 environ → fd/信号卫生 → 间接跳进来宾入口。
   真正的 ELF 机件在 `00113a70`（Ehdr/Phdr 校验）、`00114330`（vaddr→PT_LOAD 定位）、
   `001141b4`（`pread64` 循环）、`00114560`（**mmap 段映射 + mprotect**）。
5. 读环境变量的地方共 5 处（+1 个通用包装）：`00107598`（`DAT_0012e360`）、`0011634c`（`0012ef50`）、
   `00116520`（`0012efd0`、`0012ef90`）、`00117240`（`param_1` 包装）、`00117dc8`（`0012f070`）。
   字符串在 `.data` 里逐字节 XOR 解码后才拿去做 `getenv`，所以静态看不到 `getenv("TWOYI_…")`。
6. `TWOYI_ROOTFS_EXEC` 这类 sealed 变量的分支在 `00107598` 内（伪 C 行 1656–1760）：
   `strncmp(val, &DAT_0012ed90, 0xE)` **且** `strlen(val) <= 0xE7` 才通过；通过后把
   `0012eda0..0012edaf` 的 16 字节常量拼进 `0012f918/0012f920/0012f927`，并把值
   `__memcpy_chk` 到私有缓存 `0012f92f`（容量 0xE9）；不通过走 `0010df78` 的 `FUN_00117c3c` 致命退出。
   稍后用 `memcmp(p, &DAT_0012e610, 0x11)` + `FUN_0011505c` 把匹配前缀的**环境变量原项清零擦除**。
7. **loader 的 `.text` 里没有裸 `svc`**（`loader-code.txt`：`raw syscall instructions (svc #0)` = `0`）。
   唯一的 `syscall(...)` 在 `FUN_001162fc`：`syscall(0x86, sig, act, oldact, 8)`，
   `0x86 = 134 = __NR_rt_sigaction`（aarch64），第 5 参 8 = sigsetsize。
8. loader 自己只做“卫生”级信号处理：`FUN_00115f80` 先扫 `/proc/self/fd` 关掉带 `FD_CLOEXEC`
   的继承 fd，再把 sig 1..0x40 里“处置不是 SIG_DFL/SIG_IGN”的信号**复位为默认处置**，
   最后 `sigaltstack()` 装静态备用栈（`ss_sp=_DAT_00101d18`, `ss_size=DAT_00101d28`）。
   **没有 `sigaction` 导入**——即 loader 不注册自己的信号处理函数。
9. **loader↔宿主有两级握手**（后续重写的入手点）：
   - `FUN_00116520`：`getenv` 读模式/端口/凭据 → 校验 fd 是 socket → `socketpair(1,0x80005,…)`
     （`SOCK_SEQPACKET|SOCK_CLOEXEC`）→ 发 **32 字节 "B1F2" 报文**（`42 31 46 32 23 00 01 00 …`）
     并用 `SCM_RIGHTS` 传 fd → 收 32 字节回包 → 取回一个 fd 并校验魔数/版本/回显。
   - `FUN_00116df4`：发 **0xC78 = 3192 字节 "TBD1" 报文**（`54 42 44 31`，头 `short 0x28`、
     `short 1`、序号自增）→ 校验回包魔数、序号、`0x28`、`1`、两个字段为 0、`<0x1000` 的计数、`==1` 的标志。
     每次 ELF 装载前由 `FUN_00110808` 调用。
10. 分工边界：**loader = 启动栈解析 / 页大小 / ELF 读取与段映射 / 来宾初始栈 / fd 与信号卫生 /
    与宿主握手 / environ 擦除**；**libtwoyi.so = HAL・日志・RIL・传输**（`libandroid`,`liblog`,`libm`,`libdl`,
    `ZSTD_trace_decompress_*`, `__android_log_*`, `syslog`, JNI `io/twoyi/hal/KernelSuHal`,
    `io/twoyi/hal/ril/TwoyiModemNative`, `native-fence`/`nativeHandle`,
    `(Landroid/net/LocalSocket;Ljava/lang/String;)I`）。

---

## 2. 证据

### 2.1 入口链：ELF 结构与 `_start`

`linkage.txt`（对真机 `.so` 跑 readelf 的结果，非伪 C）：

```
## libloader.so
  Type:                              DYN (Position-Independent Executable file)
  Machine:                           AArch64
  Entry point address:               0x5e30
-- needed
 0x0000000000000001 (NEEDED)  Shared library: [libdl.so]
 0x0000000000000001 (NEEDED)  Shared library: [libc.so]
```

`loader-code.txt`（同一 `.so`）：

```
# raw syscall instructions (svc #0)
0
...
# init_array entries
Hex dump of section '.init_array':
  0x00029be8 00000000 00000000                   ........
```

即 `.init_array` 只有 **8 字节 / 1 项**，文件内容为 0（由 `R_AARCH64_RELATIVE` 在装载时填入），
与 Ghidra 给唯一初始化函数命名为 `_INIT_0` 一致：**整个 .so 只有一个 init 项 = `0011f208`**。

### 2.2 `entry` / `FUN_00105e44` / `__libc_init`

`// ==== entry @ 00105e30`（伪 C 行 276）与 `// ==== FUN_00105e44 @ 00105e44`（行 287）：

```c
void entry(void) { FUN_00105e44(); return; }

void FUN_00105e44(undefined8 param_1)
{
  uStack_28 = 0; local_30 = (undefined *)0x0; uStack_18 = 0; uStack_20 = 0;
  uStack_38 = 0; local_40 = 0;
  if (0 < (long)PTR_entry_00129d88 - (long)PTR_entry_00129d80) {
    local_30 = &DAT_0012e0b0;                       /* 空 preinit 区间 → 不生效（恒假分支） */
  }
  UNRECOVERED_JUMPTABLE = (code *)__libc_init(param_1,0,PTR_FUN_00129d90,&local_40);
  if (UNRECOVERED_JUMPTABLE != (code *)0x0) { (*UNRECOVERED_JUMPTABLE)(); }
}
```

判读依据：
- 这就是 bionic `crtbegin_so.S` 的 `_start` 形态：`__libc_init(raw_args, onexit=0,
  slingshot, structors)`；`local_40..local_18` 是栈上零初始化的 `structors_array_t`。
- `PTR_*` 的命名说明数据槽地址落在 `.got.plt`：`00129d80/00129d88`（bionic 的
  `__preinit_array_start/end` 比较，此处两值指向同一地址 → 差为 0 → 分支永不成立）、
  `00129d90`（**main 的 GOT 槽**，Ghidra 只给出 `PTR_FUN_<addr>` 说明它没解析到符号名）、
  `00129d98=dlopen`、`00129da0=dlclose`（见 `_INIT_0` 里 `puVar5 = PTR_dlclose_00129da0;`）。
- `__libc_init` 返回非 0 的分支若成立即调用它（`main` 不返回所以实际走不到）。

> **确认 `main` 目标**需要 `.got.plt` 内容（见 §5 代跑命令）；本报告据 `FUN_00105f20` 的
> `(int argc, long *argv)` 形态、`if (0 < param_1)` / `*param_2` 用法、以及它在 `_INIT_0` 之后
> 立刻接手并调用 `FUN_00107598(argv[0],argc,argv)` 判断 `main = FUN_00105f20`。

### 2.3 `FUN_00105f20`（= main）：解码错误串 + 转交 `FUN_00107598`

```c
void FUN_00105f20(int param_1,long *param_2)          /* 伪 C 行 371 */
{
  if (DAT_0012f900 == 0) {            /* one-time：就地解码 30 字节串到 0012e0f0 */
    DAT_0012e0f0 = DAT_0012e0d0 ^ 0x59;
    DAT_0012e0f1 = ~((DAT_0012e0d1 | 0xb) & (DAT_0012e0d1 ^ 0xff | 0xf4));
    ...  /* 逐字节 XOR/等价变换，写到 0012e0f0..0012e10d */
  }
  DAT_0012f900 = 1;
  if (...) { FUN_00106a9c(param_2); }     /* 不透明谓词包裹，可能执行 1 次 */
  if (0 < param_1) {
    if (*param_2 != 0) {
                    /* WARNING: Subroutine does not return */
      FUN_00107598(*param_2,param_1,param_2);      /* ← argv[0], argc, argv */
    }
  }
                    /* WARNING: Subroutine does not return */
  FUN_00117c3c((uVar1 | 1) ^ uVar1,&DAT_0012e0f0); /* 用上面解出的串报错 + _exit */
}
```

`FUN_00106a9c`（`00106a9c`，2,812 B）在伪 C 里只剩不透明谓词与空桩调用
（`FUN_00113400` / `FUN_001133e8` / `__stack_chk_fail`），**没有任何实际副作用可证**，
列入 §4 未解问题。`FUN_00117c3c` 是“打印 + `_exit`”，`_exit` 经 `PTR__exit_0012a090` 间接调用。

### 2.4 `_INIT_0`（`0011f208`，唯一 init 项）：自建导入指针表

```c
void _INIT_0(void)                                     /* 伪 C 行 9515，节选 9606-9637 */
{
  FUN_001256a4(); FUN_001257ac(); FUN_0012571c();      /* 三个跳板：填表辅助 */
  ...
      DAT_0012fa78 = PTR_dl_iterate_phdr_00129e68;
      DAT_0012fa98 = PTR_calloc_00129dc0;
      DAT_0012faa0 = PTR_free_00129db8;
      DAT_0012faa8 = PTR_mallinfo_00129e70;
      DAT_0012fab0 = PTR_malloc_00129db0;
      DAT_0012fab8 = PTR_malloc_usable_size_00129e78;
      DAT_0012fac0 = PTR_memalign_00129dc8;
      DAT_0012fac8 = PTR_posix_memalign_00129e80;
      DAT_0012fad8 = PTR_realloc_00129e88;
      DAT_0012faf0 = PTR_pthread_key_create_00129e90;
      ...  /* 一路到 0012fb40：pthread_* 全套 */
      DAT_0012fb90 = PTR_getpid_00129e08;
      DAT_0012fb98 = PTR_gettid_00129e10;
      DAT_0012fba8 = PTR_fork_00129e20;
      DAT_0012fbb0 = PTR_clone_00129e28;
      DAT_0012fbb8 = PTR_exit_00129e30;
      DAT_0012fbd0 = PTR_getaddrinfo_00129e38;
      ...
      DAT_0012fc00 = FUN_0012176c;  DAT_0012fc10 = FUN_00122b90;
      DAT_0012fc18 = FUN_00123330;  DAT_0012fc20 = FUN_00123610;
  ...
  FUN_00125638();
}
```

结论：`_INIT_0` = **反混淆的导入表初始化**（把 `.got`/`.got.plt` 槽位复制到 `.data` 的
`0012fa40..0012fc50` 私有数组，含 `dladdr`、`dl_iterate_phdr`、malloc/pthread 全族、
`fork`/`clone`/`exit`、DNS 族，以及内部函数 `0011fc70`/`00120c70`/`00120d84`/`0012108c`…）。
这解释了为什么 `fork`/`clone`/`dl_iterate_phdr` 在全文里“只有自身 PLT 桩”的 xref——
真实调用点全部经由这张私有表（`PTR_*` 槽）完成。

### 2.5 `FUN_00107598`（37,488 B）：编排，不是 ELF 解析

把该函数的字符串解码块与不透明谓词滤掉后，调用序列（伪 C 行号）如下：

| 行 | 动作 |
|---|---|
| 1474 / 1488 | `FUN_00114cc0(raw_args,…)` 解析 argc/argv/envp/auxv；`FUN_00114dd0(…,6,…)` 取 **AT_PAGESZ(6)** |
| 1494 | `FUN_00113484(psz)` 校验页大小（`0xfff < psz` 且 2 的幂） |
| 1511–1522 | `FUN_001134e0(argv[0])` 校验路径（绝对、长度≤0x1000、无 `.`/`..` 分量） |
| 1539–1551 | `strlen` + `__memcpy_chk(buf, argv[0], len+1, 0x1000)` |
| 1561 | `FUN_00114e4c(argc, argv, dest)` 把 argv 串搬到目标栈区 |
| 1584–1623 | `dlopen(&DAT_0012e6b0, 2)` → `dlsym(handle,&DAT_0012e500)` → `(*fn)(0x1b)` → `dlclose` |
| 1626 | `FUN_00118028()`（0 表示成功，否则致命退出） |
| 1657–1756 | `getenv(&DAT_0012e360)` + sealed 判定（见 §2.7） |
| 1777 | `FUN_0011634c()`（另一个 env 开关的布尔） |
| 1811 / 1834 | 开关=0 → `FUN_001135c8(dir, &DAT_0012e470)` 打开 rootfs 目录；`FUN_00112248(...)`；`FUN_00113898(dir)` 关闭 |
| 1861 / 1896 | 开关≠0 → `FUN_00116520(...)`（“B1F2”握手）→ 失败 `FUN_00117c3c(1,&DAT_0012e420,errno)`；成功 → `FUN_00110808(...,1)` 装载 ELF |
| 1912 / 1927 | 若装载结果有第二个 fd：再 `FUN_00110808(...,0)`；`FUN_00117208(...)` 关 fd |
| 1947–1963 | 组来宾入口参数块 `puVar26[0..6]`：`PTR_DAT_00129da8`、握手返回值、`e_phentsize(0x40)`、`e_phnum`、`*(lVar16+0x98)`、`uVar27`、`*(lVar16+0x90)`（装载基址） |
| 1984–1992 | `FUN_00115a44(...)` 搭建来宾初始栈（配 `FUN_00115670`/`FUN_001150f8`/`FUN_001159a0`） |
| 2005–2012 | `memcmp(p,&DAT_0012e610,0x11)` + `FUN_0011505c(env 数组, 是否相等)` → **擦 environ** |
| 2033–2046 | `FUN_00114a44(ElfReader*)` 释放（`free(phdr)`+`close(fd)`） |
| 2059–2062 | `FUN_00115f80()` fd/信号卫生（见 §2.9） |
| 2087–2091 | `FUN_0011341c(...,FUN_00113260,0x1105b0)`；`FUN_00125840(...)` → 间接跳转（不返回） |
| 2118–2129 | 任何失败路径：`FUN_00117c3c(status, puVar18)` 致命退出 |

`FUN_00107598` 自身**没有任何 ELF 魔数/Phdr 校验**（全文的 `\x7f`/`'E'`/`'L'`/`'F'` 只出现在
`00113a70`），它只是把活派给 `00110808`/`00112248`。

### 2.6 真正的 ELF 机件

**`FUN_00113a70`（Ehdr/Phdr 校验，类 AOSP `ElfReader::Read`）**：

```c
  if (-1 < sStack_110.st_size && iVar9 == 0) {                 /* fstat 成功 */
    uVar8 = *(char *)(piVar16 + 2) == '\x7f';                  /* ELF magic */
    ... *(char *)((long)piVar16 + 9) == 'E' ... 'L' ... 'F'
    ... *(char *)(piVar16 + 3) == '\x02'                       /* EI_CLASS = ELFCLASS64 */
    ... *(char *)((long)piVar16 + 0xd) == '\x01'               /* EI_DATA  = LSB */
    ... *(char *)((long)piVar16 + 0xe) == '\x01'               /* EI_VERSION */
    ... piVar16[7] == 1                                        /* e_version */
    ... *(short *)((long)piVar16 + 0x1a) == 0xb7               /* e_machine = EM_AARCH64 */
    ... (*(ushort *)(piVar16 + 6) & 0xfffe) == 2               /* ET_EXEC/ET_DYN */
    ... *(short *)(piVar16 + 0xf) == 0x40                      /* e_phentsize = 64 */
    ... *(ushort *)(piVar16 + 0x40) [e_phnum] * 0x38 …         /* 读 phdr 表 */
```

**`FUN_00114330`（vaddr → PT_LOAD 定位）**：入参是 ElfReader 结构（`+0x48` phdr 表、
`+0x50` phnum、`+0x58` 上界、`+0x60` min_vaddr、`+0x68` max_vaddr、`+0x78` pagesize），
`phdr` 步长 `0x38`，只认 `*piVar3 == 1`（`PT_LOAD`），命中返回 `{kind=1, offset}`，
否则 `{kind=2, …}`——与 AOSP `linker_phdr.cpp` 的 `ElfReader::LoadSegment`/`FindPhdr` 结构一一对应。

**`FUN_00114560`（mmap 段映射器，类 `ElfReader::Load`）**：

```c
  pvVar8 = mmap(pvVar13,sVar9,0,iVar5,-1,0);               /* prot=PROT_NONE，先预留 */
  ...
      if (local_b0 == 1) {
        pvVar8 = mmap(pvVar13,sVar9,local_ac,0x12,*piVar7,local_a8);
        /* 0x12 = MAP_PRIVATE|MAP_FIXED，*piVar7=fd，local_a8=p_offset，local_ac=p_flags 派生的 prot */
      } else {
        pvVar8 = mmap(pvVar13,sVar9,3,0x32,-1,0);          /* 3=PROT_READ|WRITE，0x32=ANON|FIXED */
      }
      ... iVar5 = FUN_001141b4(*piVar7, dst, len, src_off); /* pread64 补 .bss 之外的洞 */
      ... memset(dst, 0, gap);                             /* 补 bss */
  ...
  iVar5 = mprotect(base + start, (uVar20 - uVar16) * page, prot);   /* 连续同 prot 页合并 */
```

失败码是自造枚举（`1/2/3/4/5/6/7/9/0xc/0xd/0xe/0xf`），散布在上述函数里。

**`FUN_001141b4`** 只是一层 `pread64` 循环（`EINTR=4` 重试）；**`FUN_001136ec`** 用
`readlink("/proc/self/fd/N")` + `strstr(&DAT_0012edf4)` 校验 fd 的真实路径落在允许范围内；
**`FUN_001135c8`** 用 `__open_2(path,0x84000)`（`O_CLOEXEC|O_DIRECTORY`）+ `fstat`
（`S_IFDIR=0x4000`）打开 rootfs 目录。
`FUN_00110808` = “读+装载一个 ELF”（`FUN_00113a70` → `FUN_00114560`，失败即 `FUN_00117c3c`）；
`FUN_00112248` 是同类但按路径开（`FUN_00114278` = `open`+`Read`）。

### 2.7 环境变量：getenv 站点与 sealed 分支

| 站点 | 缓冲（解码目标） | 长度 | 用途 |
|---|---|---|---|
| `00107598` 行 1657 | `0012e360` | 23 B | sealed 变量（见下） |
| `0011634c` | `0012ef50` | 26 B | 返回“存在且非空”布尔 |
| `00116520` | `0012efd0` | 25 B | 值必须恰为 `"2"`（`*p=='2' && p[1]=='\0'`） |
| `00116520` | `0012ef90` | 26 B | `strtol(…,10)` → 端口/句柄号，须 < 2^31 |
| `00117240` | `param_1` | — | 通用 `getenv`+`strtoul` 包装（被 `00116520` 用于 `0012effc`/`0012f01c`） |
| `00117dc8` | `0012f070` | 21 B | `strtol`+`fcntl` |

sealed 分支（`00107598`，伪 C 行 1656–1760，压缩引用）：

```c
        if (pcVar14 != (char *)0x0) {                       /* getenv(&DAT_0012e360) 返回值 */
          sVar11 = strlen(pcVar14);
          if (sVar11 != 0) {
            strncmp(pcVar14,(char *)&DAT_0012ed90,0xe);     /* 14 字节前缀比对 */
            ...
            uVar19 = *(ulong *)(unaff_x29 + -0x40);         /* = sVar11 */
            bVar8 = 0xe7 < uVar19 != (iVar9 != 0);          /* 与 0xE7 上界比较 */
            if (0xe7 < uVar19) { bVar8 = !bVar8; }
            if (!(bool)(bVar8 ^ (iVar9 == 0 && 0xe7 < uVar19))) {
              uRam000000000012f920 = CONCAT16(... DAT_0012eda0 …);  /* 16 字节常量 → 0012f918/20/27 */
              _DAT_0012f918         = CONCAT17(...);
              DAT_0012f927._0_1_    = DAT_0012edaf;
              __memcpy_chk(&DAT_0012f92f,pcVar14,uVar19 + 1,0xe9);   /* 私有缓存，容量 233 B */
              goto LAB_0010efcc;
            }
            ... /* 不满足 → uVar21 = uVar20|1; goto LAB_0010df78; → FUN_00117c3c 致命退出 */
```

擦除（伪 C 行 2005–2012 + `FUN_0011505c`/`001150f8`）：

```c
  iVar9 = memcmp(__s1, &DAT_0012e610, 0x11);        /* 17 字节前缀 */
  FUN_0011505c(uVar27, iVar9 != 0);
  ...
void FUN_0011505c(long env_array,int param_2)        /* 伪 C 行 4015 */
{
  for (uVar2 = 0; uVar2 < *(ulong *)(env_array + 0x10); uVar2++) {
    __s = *(void **)(*(long *)(env_array + 8) + uVar2 * 8);
    iVar1 = FUN_001150f8(__s);                       /* 与 3 条解码前缀比较 */
    if ((iVar1 != 0) && (param_2 != 0 || FUN_001154ac(__s) == 0)) {
      memset(__s, 0, FUN_00115f78());                /* 原项整条清零 */
    }
  }
}
```

判读：sealed 值被读进 `0012f92f`（≤233 B），原始 `environ` 项随后被**抹掉**，来宾看不到它；
`0012eda0..0012edaf` 的 16 字节常量（另存到 `0012f918`）与 17 字节前缀 `0012e610` 大概率就是
`TWOYI_ROOTFS_EXEC` 及其 `GRB_V2:CODE_OPEN:EXEC_SEALED` 标记的组成部分——
`.so` 里确实存在明文 `TWOYI_ROOTFS_EXEC=GRB_V2:CODE_OPEN:EXEC_SEALED`
（`loader-strings.txt`，两架构都有），但**解码缓冲区的逐字节明文尚缺密文 dump 无法复核**
（见 §4/§5）。可确定的硬事实是：23 字节名缓冲、26 字节名缓冲、14 字节值前缀比较、
`0xE7` 值长度上限、17 字节擦除前缀、16 字节常量。

### 2.8 系统调用：只有 `rt_sigaction`

```c
void FUN_001162fc(ulong param_1,undefined8 param_2,undefined8 param_3)  /* 001162fc */
{
  syscall(0x86,param_1 & 0xffffffff,param_2,param_3,8);
  return;
}
```

调用者 `FUN_00115f80` 的用法（伪 C 行 4489–4520）：

```c
  for (iVar6 = 1; iVar6 != 0x41; iVar6 = iVar6 + 1) {          /* sig 1..0x40 */
    iVar5 = FUN_001162fc(iVar6,0,&local_d0);                   /* rt_sigaction(sig,NULL,&old,8) */
    if (iVar5 == 0) {
      if ((1 < local_d0) && (iVar5 = FUN_001162fc(iVar6,&local_90,0), iVar5 != 0)) {
        iVar3 = -1;                                            /* 处置非 DF/IGN → 装默认处置 */
      }
    }
  }
  iVar6 = sigaltstack(&local_b0,(sigaltstack *)0x0);
```

`local_90`/`uStack_88`/`uStack_80`/`uStack_78` 全 0 → 等价 `sigaction(sig, {SIG_DFL}, NULL)`。
`0x86` = 134 = `__NR_rt_sigaction`（aarch64），末位实参 8 = kernel sigsetsize。

### 2.9 loader 与 libtwoyi 的分工

| 职责 | 归属 | 证据 |
|---|---|---|
| 启动栈解析（argc/argv/envp/auxv）、`AT_PAGESZ` | loader | `00114cc0`、`00114dd0` |
| ELF 读入、Phdr 校验、vaddr→段、mmap/mprotect | loader | `00113a70`、`00114330`、`001141b4`、`00114560` |
| 来宾初始栈（argv/env/auxv 拷贝与重排） | loader | `00115670`、`00115a44`、`00114e4c`、`001150f8` |
| fd 卫生（扫 `/proc/self/fd`，关 `FD_CLOEXEC` 继承 fd） | loader | `00115f80`（`opendir/dirfd/readdir/fcntl/close`） |
| 信号：**只复位继承处置 + 装 altstack**，不注册 handler | loader | `00115f80`+`001162fc`；导入表无 `sigaction`/`signal` |
| 与宿主握手（fd 传递、魔法报文） | loader | `00116520`、`00116df4`、`00117360`、`001175b8`、`00117758`、`00117a00` |
| 擦 environ | loader | `0011505c`、`001150f8`、`001154ac` |
| HAL/日志/RIL/传输/JNI | libtwoyi.so | `linkage.txt`：`__android_log_print`、`syslog`、`ZSTD_trace_decompress_*`（weak）；`jni-names.txt`：`io/twoyi/hal/KernelSuHal`、`io/twoyi/hal/ril/TwoyiModemNative`、`native-fence`、`nativeHandle` |

注：`linkage.txt` 只列了“关注导入”，未做全量导入比对，因此“libtwoyi 不做段映射/信号”目前是
**未发现反面证据**，不是证明（见 §4 第 5 条）。

### 2.10 握手 1："B1F2"（32 字节 + SCM_RIGHTS）

`FUN_00116520`（`00116520`，伪 C 行 4610–4845）：

```c
  pcVar4 = getenv((char *)&DAT_0012efd0);             /* 值必须恰为 "2" */
  if (((pcVar4 == (char *)0x0) || (*pcVar4 != '2')) || (pcVar4[1] != '\0')) { … 失败 … }
  iVar2 = FUN_00117240(&DAT_0012effc,local_c0);       /* env → u32 (strtoul) */
  iVar2 = FUN_00117240(&DAT_0012f01c,local_d0);       /* env → u32 */
  pcVar4 = getenv((char *)&DAT_0012ef90);             /* env → strtol(10) */
  uVar8 = strtol(pcVar4,local_70,10);
  ...
    iVar2 = FUN_00117360(uVar8 & 0xffffffff,0);       /* fd 校验：fstat S_IFSOCK + getsockname/getpeername */
    if ((iVar2 == 0) && (iVar2 = socketpair(1,0x80005,0,(int *)&local_e0), iVar2 == 0)) {
      local_90    = 0x1000232463142;                  /* 42 31 46 32 23 00 01 00 → "B1F2" + u16 0x23 + u16 1 */
      lStack_88   = (ulong)local_c0[0] << 0x20;
      local_80    = (ulong)local_d0[0];
      iVar2 = FUN_001175b8(uVar8 & 0xffffffff,&local_90,0x20,local_e0._4_4_);  /* sendmsg + SCM_RIGHTS */
      ...
        close(local_e0._4_4_);
        iVar2 = FUN_00117758(uVar8,&local_b0,0x20,local_f0);                    /* recvmsg 取回 fd */
        if (iVar2 == 0) {
          in_ZR = (((local_a8 == 3 && local_aa == 1) && -1 < local_f0[0]) && local_ac == 2)
                  && local_b0 == 0x32463142;                                    /* "B1F2" */
          ... local_a4 == local_c0[0] ... local_a0 == local_d0[0]
          ... local_94 != 0 ... local_9c != 0
```

报文布局（小端，伪 C 局部变量顺序 = 内存顺序）：

| 偏移 | 请求（32 B） | 应答（32 B） |
|---|---|---|
| 0..3 | `"B1F2"`（0x32463142） | `"B1F2"` |
| 4..5 | `0x0023`（=35） | `2`（`local_ac`） |
| 6..7 | `0x0001` | `1`（`local_aa`） |
| 8..11 | 0 | `3`（`local_a8`） |
| 12..15 | env#1（`0012effc`） | 回显 env#1 |
| 16..19 | 0 | 回显 env#2 |
| 20..23 | env#2（`0012f01c`） | —（`local_9c` 高半） |
| 24..31 | 0 | `local_9c != 0`、`local_94 != 0` |

fd 传递实现：`FUN_001175b8` 里 `local_88 = 0x100000001` + `local_90 = 0x14`（`cmsg_len=20`,
`cmsg_level=1`=SOL_SOCKET, `cmsg_type=1`=SCM_RIGHTS）+ `local_80 = fd`，走 `sendmsg`；
`FUN_00117758` 用 `recvmsg` + `__cmsg_nxthdr` 取回 fd；`FUN_00117a00` 负责把不用的
`SCM_RIGHTS` fd 全部 `close`。
`FUN_00117360` 先 `fstat(fd)` 断言 `S_IFSOCK`（`0xc000`）再 `getsockname/getpeername` ——
说明这条通道是**已连通的 AF_UNIX 套接字**（宿主先建好，把 fd 交给 loader）。

### 2.11 握手 2："TBD1"（3192 字节，按序号）

`FUN_00116df4`（`00116df4`，每次 `FUN_00110808` 装载 ELF 前调用）：

```c
  memset(local_d68,0,0xc70);
  local_d6a = 0x28;                       /* short @ +2 */
  local_d70 = 0x31444254;                 /* "TBD1" 魔数 */
  local_d68[0] = piVar15[1] + 1;          /* 序号自增 */
  piVar15[1] = local_d68[0];
  local_d6c = 1;                          /* short @ +4 */
  puVar17 = (uint *)FUN_001175b8(*piVar15,&local_d70,0xc78,0xffffffff);   /* sendmsg 3192 B */
  ...
  in_ZR = ((local_15f8 == iVar12 && local_15fa == sVar7) && local_15fc == 1) &&
          local_1600 == 0x31444254;       /* 序号 / 0x28 / 1 / 魔数 全对 */
  ... (uStack_15e4 | local_15ec) == 0; (local_15e8 == 0 && local_15f4 < 0x1000);
  ... local_f0 != -1 ... local_15f0 == 1;
```

`FUN_001175b8(...,0xffffffff)` 的最后一参 < 0 → 走 `sendto(fd,buf,len,0x4000,NULL,0)`
（`0x4000=MSG_NOSIGNAL`），即这条 3192 字节报文不带 fd。

---

## 3. 未解问题

1. **`00129d90` GOT 槽的目标**：本报告推断 `main = FUN_00105f20`，但没有 `.got.plt` 内容，
   无法排除指向 `FUN_00106a9c` 之类的可能。
2. **解码后明文的逐字节确认**：`0012e360`(23B)、`0012ef50`(26B)、`0012ef90`(26B)、
   `0012efd0`(25B)、`0012f070`(21B)、`0012ed90`(15B)、`0012eda0`(16B)、`0012e610`(17B)、
   `0012e6b0`(19B，`dlopen` 目标)、`0012e500`(`dlsym` 符号)、`0012edc0`、`0012e470`、
   `0012e420`、`0012e710`、`0012e780`、`0012e7e0`、`0012e0f0`(30B) 都缺密文。
   已知每个字节是 `dst = f(src)`（`src` 为 `dst-0x20` 或 `0012e3xx` 区），多数等价于 `src ^ k`。
3. `FUN_00106a9c`（2,812 B）在静态视图里只剩不透明谓词 + 空桩，**副作用不明**（占位？反调试？）。
4. `FUN_00118028`（4,064 B）/ `FUN_00119008`（17,828 B）/ `FUN_0011d5c0` 都是
   `dlopen`+`dlsym`+`dlerror` 密集；疑为**符号解析/重定位引擎**，但全文未见
   `R_AARCH64_RELATIVE`(0x403) 之类重定位类型的显式处理，无法确认。
5. 来宾 ELF 的重定位到底由谁完成（loader 自己 / 依赖 `dlopen` 语义 / 宿主进程），未定。
6. `FUN_001150f8` 的 **3 条路径前缀表**（`(&PTR_DAT_00129bd0)[0..2]`）内容未知——
   决定“哪些 so 路径允许/要被从 environ 抹掉”。
7. 两级握手的**字段语义**：`"B1F2"` 的 `[4:6]=35/2`、`[6:8]=1`、`[8:12]=3`；
   `"TBD1"` 3,192 字节负载（`0xc70` 数据区）布局完全未知。
8. 尾部 `FUN_0011341c(...,FUN_00113260,0x1105b0)` + `FUN_00125840(...)` 的间接跳转目标
   （推测为来宾 entry / 线程入口），静态不可确定。
9. `FUN_00113260`（392 B）同样只剩空桩 + 不透明谓词。

---

## 4. 下一步建议看的函数

按优先级（括号内为 `docs` 之外仅用于定位的地址）：

| 优先 | 函数 | 为什么 |
|---|---|---|
| ★★★ | `FUN_00110808`(0x110808) | 装载 ELF 的入口；串起 TBD1 握手 + Read + Load + 双 fd 语义 |
| ★★★ | `FUN_00118028`(0x118028) / `FUN_00119008`(0x119008) | 最可能是符号解析/重定位；决定“loader 是否自己修 relocation” |
| ★★★ | `FUN_00115670`(0x115670) / `FUN_00115a44`(0x115a44) | 来宾初始栈与 auxv 布局（重写时最先要对齐） |
| ★★ | `FUN_00116520` / `FUN_00116df4` / `FUN_00117360` / `FUN_001175b8` / `FUN_00117758` | 两级握手线格式（配合 task-8 的 HAL 线格式对照） |
| ★★ | `FUN_0011d5c0`(0x11d5c0) | `dlopen(param,2)` 的封装，可能带库搜索路径逻辑 |
| ★★ | `FUN_001150f8`/`FUN_001154ac`/`FUN_0011505c` | 3 条前缀表 + environ 擦除策略 |
| ★ | `FUN_00106a9c` / `FUN_00113260` | 静态无副作用的谜团函数 |
| ★ | `_INIT_0` 的 `00125638/001256a4/00125678/0012571c…` 跳板群 | 私有导入表的初始化协议 |

### 需要 lead 在 CI 代跑的命令（本机禁止反编译；不要在本地跑）

```bash
# 1) 确认 main 的 GOT 槽与 _start 实参（最关键的一个待确认项）
readelf -W -x .got.plt libloader.so
objdump -d --start-address=0x5e30 --stop-address=0x5eb0 libloader.so

# 2) 复核“没有裸 svc”，并列出所有导入符号（与 loader-code.txt 对照）
objdump -d libloader.so | grep -c 'svc'
readelf -W --dyn-syms libloader.so

# 3) 段布局 + 重定位（判断 loader 自身 init_array/got 的填充方式）
readelf -W -S -l -d libloader.so
readelf -W -r libloader.so | head -120

# 4) 明文/密文定位：为 §3 第 2 条提供逐字节素材
strings -t x libloader.so | grep -iE 'twoyi|GRB_V2|ANDROID_EMU|libtwoyi'
# 需要 dump 的密文区（.data 内，供离线解码）：0x2e0d0-0x2e1a0、0x2e340-0x2e420、
# 0x2e590-0x2e700、0x2e900-0x2ed00、0x2ef00-0x2f100
# 在 runner 上把上述区间导成 hex 文本（只回传到日志/artifact，不入仓；纯读取，无编译）
python3 - <<'PY'
import struct
buf = open('libloader.so','rb').read()
# 解析 ELF64 段表，把 vaddr 换成文件偏移
e_phoff, = struct.unpack_from('<Q', buf, 0x20)
e_phentsize, e_phnum = struct.unpack_from('<HH', buf, 0x36)
segs = []
for i in range(e_phnum):
    o = e_phoff + i*e_phentsize
    p_type, p_off, p_vaddr, _, p_filesz = struct.unpack_from('<IIQQQ', buf, o)
    if p_type == 1: segs.append((p_vaddr, p_off, p_filesz))
def rd(vaddr, n):
    for v, off, sz in segs:
        if v <= vaddr < v+sz:
            f = off + (vaddr - v)
            return buf[f:min(f+n, off+sz)]
    return b''
for start, end in [(0x2e0d0,0x2e1a0),(0x2e340,0x2e420),(0x2e590,0x2e700),
                   (0x2e900,0x2ed00),(0x2ef00,0x2f100)]:
    d = rd(start, end-start)
    print(f'## {start:#x}-{end:#x}')
    for k in range(0, len(d), 16):
        print(f'{start+k:08x} ' + d[k:k+16].hex())
PY
```

拿到这些 hex 后，把每个解码块（伪 C 里 `DAT_x = f(DAT_y)` 的那几十行）按“字节 i 用源地址
`dst-0x20+i` 或 `0012e3xx+i`”重算一遍，即可还原 §2.7/§2.10/§2.11 里的明文。

若需重跑 Ghidra：`gh workflow run ghidra-loader.yml --repo ylh440104/twoyi-baseline`
（`workflow_dispatch` 即可，无需 push；artifact 编号见 task 描述）。
建议同时给 `tools/ghidra/ExportDecompiled.java` 增加两项输出：
① 数据段 dump（`.data`/`.got.plt`/`.init_array` 的 hex）；② **无 xref 也导出全部字符串及其地址**
（当前只导出了 2 条“有 xref 的字符串”，这正是本次无法给出明文的最主要原因）。
以上改动属于 `tools/`，由 lead 决定是否执行。

---

## 5. 附录：函数速查

| 地址 | 角色 |
|---|---|
| `00105e30` | `entry`（bionic `_start`） |
| `00105e44` | `__libc_init(raw_args,0,main_got,&structors)` |
| `00105eb0/00105ecc/00105e9c` | `__cxa_atexit` 注册、`fini_array` 逆序调用、退出跳板 |
| `00105f20` | `main(argc,argv)`：解码错误串 → `FUN_00106a9c` → `FUN_00107598` |
| `00106a9c` | 不透明谓词空壳（未解） |
| `00107598` | loader 顶层编排（37 KB） |
| `00110808` | 装载一个 ELF（TBD1 握手 + Read + Load） |
| `00112248` | 按路径装载（`00114278`→`00113a70`）+ fstat/校验 |
| `00113260` | 空壳跳板（作为回调传入，未解） |
| `00113400/0011341c/00113430/00113448/0011345c/00113468`、`001133e8` | 调用约定混淆用的“取参”空桩（Ghidra 只剩 `return;`） |
| `00113484` | 页大小校验（`0xfff < psz` 且 2 的幂） |
| `001134e0` | 绝对路径校验（无 `.`/`..`，≤0x1000） |
| `001135c8` | 打开目录（`O_CLOEXEC|O_DIRECTORY`）+ `S_IFDIR` + readlink 校验 |
| `001136ec` | `/proc/self/fd/N` readlink + `strstr` 白名单校验 |
| `001138cc` | `openat(dirfd, path+1, 0x80100)` + `fstat` + readlink 校验 |
| `00113898` / `00117208` / `00114a44` | 关 fd / 关 fd / 释放 ElfReader |
| `00113a70` | Ehdr/Phdr 解析与校验（EM_AARCH64、e_phentsize=0x40…） |
| `001141b4` | `pread64` 循环（EINTR 重试） |
| `00114278` | `open`+`Read` 组合，并置标志 `+4=1` |
| `00114330` | vaddr → PT_LOAD 定位（返回 kind/offset） |
| `00114560` | **mmap 段映射 + mprotect** |
| `00114cc0` / `00114dd0` | 解析 argc/argv/envp/auxv；`getauxval(type)` |
| `00114e4c` / `00114fe0` | 把 argv 串搬进目标栈区 / 计算栈区尺寸 |
| `0011505c` / `001150f8` / `001154ac` | 擦 environ / 3 条前缀匹配 / 第二条前缀判定 |
| `00115670` / `00115a44` / `001159a0` | 来宾初始栈与 auxv 组装 |
| `001162fc` | 唯一的 `syscall(134=rt_sigaction,…)` 包装 |
| `00115f80` | fd 卫生 + 信号处置复位 + `sigaltstack` |
| `0011634c` / `00117240` / `00117dc8` | env 布尔开关 / `getenv`+`strtoul` 包装 / `getenv`+`fcntl` |
| `00116520` | 握手 1（"B1F2"，32 B + SCM_RIGHTS） |
| `00116df4` | 握手 2（"TBD1"，3192 B + 序号） |
| `00117360` | socket fd 校验 / `getsockname`+`getpeername` |
| `001175b8` / `00117758` / `00117a00` | `sendmsg`(+SCM_RIGHTS) / `recvmsg`+`__cmsg_nxthdr` / 关闭多余 fd |
| `00117c3c` | 致命退出（打印 + `_exit`） |
| `00118028` / `00119008` / `0011d5c0` | `dlopen`/`dlsym` 密集（疑符号解析，未解） |
| `0011f208` | `_INIT_0`：私有导入表（唯一 `.init_array` 项） |