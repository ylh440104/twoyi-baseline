# ROM 打包工具 `tools/rompack`（阶段二）

自研的 ROM 容器打包 / 解包 / 结构校验工具，目标是产出**结构上与官方最新版
`assets/rootfs.tar.zst` 同构**的归档，并保证同一棵输入树重复打包得到**逐字节相同**
的输出。它只是归档器：不做 AOSP 构建，也不涉及 loader（"无内核跑 Android"那一层
仍是闭源资产，见 `docs/ROM_FORMAT.md`）。

- 官方格式的实测依据：`docs/ROM_FORMAT.md`（run `45812271640`）
- 工具源码：[`tools/rompack/rompack.py`](../tools/rompack/rompack.py)
- 自检脚本：[`tools/rompack/selftest.py`](../tools/rompack/selftest.py)
- CI：[`.github/workflows/rom-pack.yml`](../.github/workflows/rom-pack.yml)

## 1. 容器布局

```
偏移 0                      skippable frame #0：BOM JSON（magic 0x184D2A50）
紧随其后（可选）            skippable frame #1：零填充（--pad-skippable N）
之后到文件尾               恰好 1 个 zstd 帧（内部 1 个或多个 block）
```

zstd skippable frame 的字节格式（官方与本文相同，因为这是 zstd 规范）：

| 偏移 | 长度 | 内容 |
|---|---|---|
| 0 | 4 | magic，小端 `0x184D2A50 + N`（N=0..15），即字节 `50 2a 4d 18` |
| 4 | 4 | 载荷长度，小端 u32 |
| 8 | len | 载荷（BOM 就是这里的 UTF-8 JSON） |

BOM 帧放在最前面，因此任何 zstd 解码器（`zstd -d`、`tar --use-compress-program=unzstd`）
都能直接跳过它、照常解出 tar，不需要认识 BOM。

## 2. 用法

工具只要 Python 3 标准库（3.8+）；压缩/解压调系统 `zstd` CLI。缺 `zstd` 时用
`--zstd-cmd` 指定任意等价实现（可以带参数，例如 `--zstd-cmd "/usr/bin/zstd -T2"`）。

### 打包

```bash
# 最小用法：<srcdir> 的内容成为容器里的 rootfs/，BOM 自动生成
python3 tools/rompack/rompack.py pack my-rootfs/ rootfs.tar.zst

# 用自己的 BOM、窗口对齐官方 64 MiB、多线程（牺牲跨机可复现）
python3 tools/rompack/rompack.py pack my-rootfs/ rootfs.tar.zst \
    --bom bom.json --window-log 26 --threads 0

# 保留中间 tar 便于排查
python3 tools/rompack/rompack.py pack my-rootfs/ rootfs.tar.zst --keep-tar /tmp/payload.tar
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `srcdir` | — | 源目录；它本身不入档，其**内容**成为 `rootfs/` |
| `output` | — | 输出 `.tar.zst`；先写 `.tmp` 再 `rename`，不会留下半个文件 |
| `--bom FILE` | 自动生成 | BOM JSON；必须是 JSON 对象且含字符串 `bomVersion` |
| `--level N` | `19` | zstd 压缩级别 |
| `--window-log N` | `27` | 传给 zstd `--long=N`；官方实测是 26（64 MiB） |
| `--threads N` | `1` | `-T N`；`0` = 用满所有核 |
| `--pad-skippable N` | `0`（不加） | 在 BOM 后追加一个 N 字节零填充 skippable frame |
| `--zstd-cmd` | `zstd` | zstd 可执行文件（可带参数） |

打包时做的归一化（与官方实测一致）：

| 项目 | 处理 |
|---|---|
| 顶层 | 只有一个目录 `rootfs/`（目录条目名带尾斜杠） |
| uid / gid | 全部 `0` |
| uname / gname | 全部留空（不写入宿主机用户名） |
| mtime | 全部 `0`，即 `1970-01-01T00:00:00Z` |
| 权限 | 常规文件 `0644`；带任一可执行位 → `0755`；目录 `0755`；符号链接 `0777` |
| 符号链接 | 目标原样保留，**不解析**（绝对目标也照抄） |
| 内容 | 常规文件原样；硬链接展开成独立常规文件；设备/管道/套接字直接报错 |
| 顺序 | 条目按名称（UTF-8 字节序）排序 |
| 尾部 | 两个 512 字节零块，再补零对齐到 10240 字节（GNU tar 记录大小） |

### 解包

```bash
# 打印 BOM 与容器布局，再把 rootfs/ 整层解到 out/
python3 tools/rompack/rompack.py unpack rootfs.tar.zst out/

# 同时写一份清单 JSON（文件名/类型/权限/mtime/链接目标/容器参数）供后续比对
python3 tools/rompack/rompack.py unpack rootfs.tar.zst out/ --manifest out.json

# 目标目录非空时必须加 --force
python3 tools/rompack/rompack.py unpack rootfs.tar.zst out/ --force
```

安全性：解包只做**词法**路径判断——绝对路径条目、含 `..` 的条目直接拒绝；路径按分量
拼在输出目录下，不可能写到输出目录之外。这里刻意**不**跟随符号链接（官方 ROM 里
`/system/bin/sh` 这类绝对链接是常态，跟随会把合法条目误判成逃逸）。目录的权限与
mtime 在最后统一设置（写子项会刷新父目录 mtime），所以解出来的目录时间戳也是 0。

不恢复属主：非 root 无法 `chown`。头里的 uid/gid 会被校验（必须全 0）并记进清单，但
落盘属主是当前用户。

### 校验

```bash
python3 tools/rompack/rompack.py verify rootfs.tar.zst            # 不合格 → 退出码 1
python3 tools/rompack/rompack.py verify rootfs.tar.zst --report verify.json
```

`verify` 的检查项（任一条 FAIL 即退出码 1，并在 `[FAIL]` 行后面写原因）：

| 检查项 | 要求 |
|---|---|
| `container.skippable_frames` | 至少 1 个 skippable frame |
| `container.bom_json` | 其中恰好有 1 个可解析成 JSON **对象** |
| `container.bom_version` | BOM 里有非空字符串 `bomVersion` |
| `container.bom_before_payload` | skippable frame 都在 zstd 帧之前（与官方布局一致） |
| `container.single_zstd_frame` | 恰好 1 个 zstd 帧 |
| `container.zstd_checksum` | 帧头声明 XXH64 内容校验 |
| `container.window` | 报告窗口大小/windowLog/内容长度/块数（信息项） |
| `container.no_trailing_bytes` | zstd 帧正好覆盖到文件末尾 |
| `tar.entries` | 条目数 > 0，并给出各类型计数 |
| `tar.ustar_format` | 每个头都是 `ustar\0` + `00`（不接受 pax/GNU 扩展头） |
| `tar.header_checksums` | 每个头的校验和自洽 |
| `tar.top_level_rootfs` | 顶层只有一个目录，且是 `rootfs/`（存在目录条目本身） |
| `tar.no_absolute_path` | 没有以 `/` 开头的条目 |
| `tar.no_parent_component` | 没有 `..` 路径分量 |
| `tar.no_duplicate_entries` | 归一化（去尾斜杠）后无重复条目 |
| `tar.no_device_or_special` | 没有设备文件/管道/硬链接/未知类型 |
| `tar.symlink_targets` | 符号链接都有目标（目标可以是绝对路径） |
| `tar.normalized_owner` | uid/gid 全为 0 |
| `tar.normalized_mtime` | mtime 全为 0 |
| `tar.terminator` | 两个零块结束（缺失只 warn） |
| `bom.entry_count_matches` | BOM 的 `entryCount` 与载荷实际条目数一致（字段不存在则 skip） |
| `bom.tar_bytes_match` | BOM 的 `tarBytes`/`payloadBytes` 与解压长度一致（同上） |
| `payload.zstd_integrity` | zstd 能完整解压到结尾（含 XXH64 校验） |

zstd 帧的定位与"单帧"判定不靠解压：工具自己走帧头（descriptor → 可选窗口描述符 →
可选字典 ID → 可选内容长度）和每个 block 头（last/type/size），因此能在不解压的前提下
精确知道帧边界、块数、窗口大小和是否有 XXH64，也能立刻发现"第二个帧"或"尾部垃圾"。

### 自检脚本

```bash
python3 tools/rompack/selftest.py                 # 全部（需要 zstd）
python3 tools/rompack/selftest.py --skip-zstd     # 只跑不需要 zstd 的纯层
python3 tools/rompack/selftest.py --tree DIR      # 追加 pack/unpack 往返断言
```

- **纯层**（无 zstd）：归一化、排序、长路径 name/prefix 切分、头部校验和、两次构建
  逐字节一致，并用标准库 `tarfile` 独立交叉解析一遍；另外手搓合法 zstd 帧（raw block）
  单测容器解析器，以及直接喂 tar 字节给载荷检查，验证 8 类畸形载荷都被拒。
- **容器层**（需 zstd）：造出缺 BOM、绝对路径、`..`、重复条目、设备文件、2 个 zstd 帧、
  顶层不是 `rootfs/`、载荷被改坏、尾部垃圾的容器，逐个要求 `verify` 退出码非 0 且失败项
  正是预期那条。

## 3. BOM JSON

默认生成的 BOM（`--bom` 未给出时；无任何时间戳，保证可复现）：

```json
{"bomVersion":"dsh-rompack-1","dirCount":4,"entryCount":9,"fileCount":4,
 "generator":"dsh-rompack/0.1.0","mtimeEpoch":0,"ownerGid":0,"ownerUid":0,
 "payloadFormat":"ustar","symlinkCount":1,"tarBytes":20480,"topDir":"rootfs",
 "totalBytes":4133,"zstdLevel":19,"zstdWindowLog":27}
```

（实际是紧凑单行，键按字典序；这里为了可读性折行。）

| 字段 | 含义 |
|---|---|
| `bomVersion` | 物料清单版本标识；`--bom` 必须提供 |
| `generator` | 生成器版本（`dsh-rompack/<版本>`） |
| `payloadFormat` | 固定 `ustar` |
| `topDir` | 固定 `rootfs` |
| `entryCount` / `fileCount` / `dirCount` / `symlinkCount` | 条目数分类统计 |
| `totalBytes` | 常规文件内容字节总和 |
| `tarBytes` | 载荷 tar 流长度（= zstd 解压后长度） |
| `mtimeEpoch` / `ownerUid` / `ownerGid` | 归一化目标值（0/0/0） |
| `zstdLevel` / `zstdWindowLog` | **请求**的压缩参数（不是帧头里的实际窗口，见 §4.1） |

序列化固定为 `json.dumps(..., sort_keys=True, separators=(",", ":"), ensure_ascii=True)`
——同样内容的 BOM 必然序列化出同样字节。`--bom` 给的文件按内容原样使用，**不会**注入
统计字段；需要统计字段就自己写进 JSON，或者不带 `--bom` 用自动生成的版本。

## 4. 与官方实测格式的差异（逐条）

按重要性排列。前四条都会导致"和官方字节不同"，但都不影响容器结构兼容性（都能被
`zstd -d` / `tar` 正常解开，也都能通过本工具的 `verify`）。

### 4.1 压缩窗口：官方 64 MiB，本工具默认 128 MiB

官方实测是 **64 MiB 窗口**（`windowLog=26`）。任务书里约定的命令是
`zstd -19 --long=27`，而 `--long=N` 就是 `windowLog=N`，27 = **128 MiB**，所以默认值
按任务书取 27。要对齐官方请显式 `--window-log 26`。

补充事实：zstd 会把窗口收缩到实际内容长度，载荷小于窗口时帧头甚至没有窗口描述符
（single-segment 帧，窗口=内容长度）。因此小载荷（CI 的 3 MB 合成树）**看不到**
128 MiB 窗口，只有载荷大于窗口时 `--long` 才真正生效。`verify` 打印的
`container.window` 是帧头里的**实际**窗口，BOM 里的 `zstdWindowLog` 是**请求值**，
两者不一致是正常的。

### 4.2 压缩级别与并行度：官方参数未知

官方容器 212,205,950 → 626,176,000 字节（比例 **2.95**）。这个比例与 `-19` 量级的
结果相当，但**没有实测证据**证明官方的具体级别，所以无法判定"同为 19"。本工具取
`-19`（任务书约定）。

线程数上，本工具默认 **`-T1`**（单线程），理由：多线程压缩的 job 切分参数与版本、
核数相关，只有单线程才谈得上"换一台机器也逐字节一致"。官方若用 `-T0`，字节必然
可能不同。要速度就用 `--threads 0`，但那时只保证**同一次 CI 内**可复现。

### 4.3 zstd 版本：字节级输出强依赖版本

同一个 zstd 版本 + 同一份输入 + 同一组参数，输出是确定的；**换版本就可能不同**
（Huffman/FSE 表构造、块切分的细微调整都会变字节）。所以：

- CI 的"两次 pack 逐字节一致"是**同一个 runner、同一个 zstd** 内的比对，结论有效；
- "和官方容器逐字节一致"**从未承诺**，也用不着——只要结构兼容；
- 跨机器比较时固定 `zstd --version`；官方容器是哪个版本压的，我们未知。

### 4.4 skippable frame 数量：官方 2 个，本工具 1 个

官方帧前有 **2 个** skippable frame，其中第一个是 JSON（`{"bomVersion":"release-2…`）。
第二个的内容**未实测**，可能是签名/占位/压缩参数记录，也可能只是对齐填充。本工具默认
只写 BOM 这一个；需要凑帧数时用 `--pad-skippable BYTES` 追加一个零填充帧，但**不要**
假装知道官方第二个帧的语义。`verify` 允许 2 个及以上 skippable frame（只要求恰好 1 个
是 JSON）。

### 4.5 BOM 字段：只保证 `bomVersion` 存在

官方 BOM 只被解码到开头 `{"bomVersion":"release-2…`，其余字段**未知**（在 runner 上
可以完整导出，但当前报告只记录了前缀）。因此：

- 本工具的默认 BOM 是自有 schema（`dsh-rompack-1`），字段名与官方**很可能不同**；
- 只把"存在非空字符串 `bomVersion`"当作硬约束；
- 要和官方对齐就用 `--bom` 注入自己的字段集，但字段语义不要臆造；
- JSON 序列化方式（键序/缩进/是否 ASCII 转义）也可能与官方不同，本工具选**可复现**的
  那种（键排序 + 紧凑 + ASCII 转义）。

### 4.6 BOM 帧的 magic 下标：本工具用 0x184D2A50

zstd 规范允许 `0x184D2A50..0x184D2A5F` 共 16 个下标；本工具固定用下标 0。官方用的是
哪个下标，现有实测记录里没有逐字节确认。

### 4.7 USTAR 细节：官方未逐项实测，本工具按可复现方式固定

| 细节 | 本工具 | 官方 |
|---|---|---|
| magic / version | `ustar\0` + `00` | 实测是"普通 USTAR" |
| `uname` / `gname` | 留空 | 未实测（uid/gid=0 已实测） |
| 目录条目名 | 带尾斜杠 `rootfs/etc/` | 未实测（`tar -tv` 不显示） |
| 尾部 | 2 个零块 + 对齐到 10240 | 未实测对齐方式 |
| 条目顺序 | 名称字节序 | 未知（可能是打包时的遍历序） |
| 设备号字段 | 全 0 | 无设备文件，应同样为 0 |
| 硬链接 | 展开为独立常规文件 | 官方 2077 条目统计里没有单列硬链接 |

如果将来要把"官方字节级复刻"作为目标，需要先在 runner 上导出官方归档的**全部头字段**
（例如 `tar --list --verbose --verbose` 或直接解析 512 字节头）再逐项对齐；现在这属于
已知缺口，不是已证事实。

### 4.8 能力边界：只处理 USTAR 能表示的内容

- 单文件上限：USTAR `size` 字段 12 字节八进制 = 8 GiB；路径上限 `name` 100 + `prefix`
  155 字节（超了会报错而不是退回 pax/GNU 扩展头）；
- 不支持 >8 GiB 文件、超长路径、稀疏文件、ACL/xattr、设备文件、硬链接（展开）；
- 官方最大条目 1.3 MB（`rootfs/init`），现有树完全落在 USTAR 能力内。

## 5. 验证方式

三层，代价递增：

1. **本机纯层**（不需要 zstd，秒级）：
   `python3 -m py_compile tools/rompack/rompack.py tools/rompack/selftest.py` +
   `python3 tools/rompack/selftest.py --skip-zstd`。
   覆盖 USTAR 写入、归一化、排序、长路径切分、容器解析器（手搓 raw-block 帧）、
   载荷检查的 8 类畸形输入。当前 36 项全过。
2. **CI 往返**（`.github/workflows/rom-pack.yml`，ubuntu-22.04，timeout 15 分钟）：
   装 `zstd` → 构造合成树（两级目录、3 MB 随机文件、空目录、可执行脚本、相对符号链接、
   绝对目标符号链接、中文文件名、故意设置的 0600/0777 权限）→ `pack` → `verify` →
   `unpack` → 用 `selftest.py --tree` 逐条比对文件列表/权限位/mtime → **再 `pack` 一次并
   `cmp` 要求逐字节一致** → 用显式 `--bom` 再走一遍并比对条目清单 → 跑负例容器 →
   把 sha256/条目数写进 job summary。
   该工作流由 `tools/rompack/**` 或自身文件变更触发，也支持 `workflow_dispatch`：

   ```bash
   gh workflow run rom-pack.yml --ref <分支>
   gh run watch          # 或 gh run view --log
   ```
3. **对真 ROM 做结构校验**（需要官方 ROM 在 runner 上，不入库）：
   ```bash
   unzip -p twoyi.apk assets/rootfs.tar.zst > rootfs.tar.zst
   python3 tools/rompack/rompack.py verify rootfs.tar.zst --report official.json
   ```
   预期：BOM/单帧/顶层/路径/归一化各项通过；BOM 的统计字段因字段名不同而多数 skip。
   这一步能把 §4.7 里"未实测"的几项补成事实，是下一步要做的。

## 6. 已知限制

- **不为字节级复刻背书**：容器结构兼容 ≠ 与官方容器逐字节相同（§4.1–4.6）。
- **只保证同机同版本可复现**：跨 zstd 版本、跨 `--threads 0`、跨 CPU 数量都不保证。
- **不恢复属主**：解包时 uid/gid 只读不写（非 root 无法 `chown`）。
- **不还原 ACL/xattr/稀疏文件**：USTAR 里本就没有这些信息。
- **BOM 语义不校验**：只校验存在性与统计一致性，不校验渠道/版本号是否真实。
- **`verify` 会完整解压一遍**：结构与内容校验（XXH64）必须读完全部数据，626 MB 的
  官方 ROM 在 runner 上要几十秒，不是"只看头"的廉价检查。
- **单线程 level 19 打包大 ROM 很慢**：626 MB 用 `-T1` 可能几十分钟；要速度用
  `--threads 0`，并接受"只在本机本版本可复现"。
- **不做 AOSP 构建**：这层只是归档器，ROM 内容怎么来是另一个问题（`docs/ROM_FORMAT.md`）。

## 7. 下一步

1. 在 runner 上把官方 `rootfs.tar.zst` 完整解出 BOM JSON 与几个 512 字节 tar 头，
   把 §4.4/§4.5/§4.7 的"未实测"逐项变成实测结论。
2. 用真 ROM 跑一次 `verify`，确认工具对官方产物的判定（预期全绿或仅有 skip）。
3. 有了自有 `rootfs`（AOSP 8.1 arm64 派生）之后，把 `pack` 接到构建产物上，
   并让宿主侧安装逻辑（`RomArchiveBudget`/`RomInstallTransaction`）认识新容器。