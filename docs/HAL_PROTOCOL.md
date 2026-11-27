# 两仪 0.7.5 HAL 线格式（阶段一·HAL）

本文件从本机已有的 jadx 全量 Java 反编译产物（`dec.marAjz/src/sources`，对应官方 `twoyi_0.7.5-11131322.apk`）还原**宿主侧** HAL 通信格式。
只描述 Java 层能证明的字节/字段；凡属推断一律标注「推测」并给出依据。反编译源码不进入仓库，本文件只保留类名、方法名、常量值与布局表。

一句话结论：**`@twoyi_hal` 上所有模块共用一个 4 字节小端 `int32` 类型握手，之后每个模块各自定义裸字节流；除 camera 的自描述 32 字节头和控制服务的 9 字节头之外，各模块都没有长度前缀或校验和。**

## 1. 证据来源

| 项 | 值 |
|---|---|
| APK | `twoyi_0.7.5-11131322.apk`（SHA-256 `86ed3afaa447104f131d906c42ed8a07e207959c7869fda54bd245f62db14f4e`） |
| 反编译产物 | jadx 全量 `src/sources`（本机 `/data/data/com.termux/files/home/dec.marAjz`） |
| 原生库符号面 | `native-symbols.txt`：`libtwoyi.so` 仅导出 `JNI_OnLoad`（native 方法走 `RegisterNatives` 动态注册） |
| 旧 ROM 字符串证据 | `docs/HAL_BRIDGE.md`（0.5.4 配套 ROM 的 `stub-strings`） |

## 2. 传输总览

### 1.1 端点

宿主在 `e0.g(String)`（`io/twoyi/e0.java:93-120`）里完成三件事，这就是 HAL 的启动顺序：

1. `Renderer.configureEndpoints(rootfs, touch, key0, "twoyi_hal", "TWOYI_SOCK", gfxstream)`（`io/twoyi/o.java:21`，常量见 `io/twoyi/o.java:41` 的 `toString()`）；
2. `io.twoyi.hal.t0.b(this)` —— 启动 HAL 服务线程；
3. `new y0(this).c("TWOYI_SOCK")` —— 启动控制 socket 服务。

| 抽象名 | 方向 | bind 位置 | 说明 |
|---|---|---|---|
| `@twoyi_hal` | 来宾 → 宿主 | `d8/d.java:72`（`LocalSocketAddress.Namespace.ABSTRACT`） | 所有 HAL 模块复用同一条监听，靠首包 `int32` 分流 |
| `@TWOYI_SOCK` | 来宾 → 宿主（文本） | `io/twoyi/m.java:104`（`y0.c()` 起的线程） | 控制面，文本命令（如 `PING`、`SWITCH_HOST`、`BOOT_COMPLETED`，见 `io/twoyi/y0.java:38-60`） |
| `@twoyi_bt_server` | 宿主 → 来宾（宿主主动 connect） | `io/twoyi/hal/a0.java:31`（客户端 connect） | 蓝牙中继，type 7 在 0.7.5 被直接拒绝（`s0.java:88`） |
| `@twoyi_camera_transport` | — | **Java 层没有** | 全量源码内 grep 不到该串；0.7.5 的 camera 走 `@twoyi_hal` 的 type 5/6（详见 §3.6） |

> 对 `@twoyi_hal` 与 `@TWOYI_SOCK` 的 socket 类型：反编译里分别是 `new LocalSocket(2)`（`d8/d.java:64`）与 `new LocalSocket(3)`（`io/twoyi/m.java:101`、`io/twoyi/x0.java:34`），两者随后都 `bind` + `LocalServerSocket` + `accept()`。`android.net.LocalSocket(int)` 的入参是 socket 类型常量，但**这两个字面值到 `SOCKET_STREAM/DGRAM/SEQPACKET` 的映射在本机资产里无法确证**（见 §8 未解问题 1）；本机实测 AF_UNIX 上 `SOCK_DGRAM` 不能 `listen()`（Termux 返回 `EACCES`，Linux 语义为 `EOPNOTSUPP`）、`SOCK_SEQPACKET` 可以，因此两者都必须是面向连接的类别。

### 1.2 首包与分发（`t0` + `s0`）

连接被 `accept()` 后包进 `s0(0, t0, socket)`（`d8/d.java:79`）丢给缓存线程池执行。`s0.run()` case 0（`io/twoyi/hal/s0.java:38-136`）是**唯一的分发点**：

1. `int type = t0.e(inputStream)` —— 读 **4 字节小端 int32**（`t0.java:238-242`：`ByteBuffer.wrap(4B).order(LITTLE_ENDIAN).getInt()`）；
2. `if (type == 16 && !KernelSuHal.isSupported())` → 直接关闭（本版 `isSupported()` 硬编码 `true`，`KernelSuHal.java:19-21`）；
3. `t0.a(type)` 做**懒初始化并判可达**（`t0.java:248-312`）。不需要初始化的类型（type 2/7/9/16/17 等落在 `default`）直接返回 `true`；只有 `Context` 未就绪或初始化抛 `RuntimeException` 时才返回 `false`（`t0.java:294-311`）；
4. 无论哪条分支，**都先回写同一个 4 字节 int32**：`t0.f(outputStream, type)`（`t0.java:244-246`）。这既是 ACK，也是类型回显；`!t0.a(type)` 时回显后立即关闭；
5. 按 `type` 把 socket 交给模块处理。

**因此：`@twoyi_hal` 的线格式 = `int32 LE type`（请求）→ `int32 LE type`（应答）→ 模块私有负载。没有版本号、没有长度前缀、没有校验和。**

`t0.a(int)`（`t0.java:248-312`）建立了 type → 懒初始化槽位（`c(Context,int)`，`t0.java:75-220`）的映射：

| type | 模块（处理函数） | 初始化槽位 → 初始化内容 |
|---|---|---|
| 0 | audio 播放 `y`（`s0.java:55-59`） | 0 → `y` 存 `WeakReference<Context>`（`t0.java:77-81`） |
| 1 | audio 录音 `x0`（`s0.java:60-70`） | 0 → 同上 |
| 10 | audio 控制 `y.c(LocalSocket)`（`s0.java:97-99`） | 0 → 同上 |
| 2 | `TwoyiResolverHal`（`s0.java:71-74`） | 无（default → 直接可用） |
| 3 | vibrator `c1`（`s0.java:75-77`） | 1 → `Vibrator.hasVibrator()`（`t0.java:82-97`） |
| 4 | lights/背光 `u0`（`s0.java:78-80`） | 2 → Context（`t0.java:98-109`） |
| 5 | camera 目录 `g0.f(LocalSocket)`（`s0.java:81-83`） | 10 → `CameraManager`（`t0.java:188-216`） |
| 6 | camera 会话 `g0.e(LocalSocket)`（`s0.java:84-86`） | 10 → 同上 |
| 7 | 蓝牙（**已拒绝**，`s0.java:87-90`） | 无 |
| 8 | GPS `r0.j(LocalSocket)`（`s0.java:91-93`） | 4 → `LocationManager` + `twoyi_gps_thread`（`t0.java:114-138`） |
| 9 | 时间/闹钟 `k.h(Context,LocalSocket)`（`s0.java:94-96`） | 无 |
| 11 | 宿主广播 `b0.a(LocalSocket)`（`s0.java:100-102`） | 5 → Context（`t0.java:139-141`） |
| 12 | wifi/网络 `e1.x(LocalSocket)`（`s0.java:103-105`） | 6 → `WifiManager`+`ConnectivityManager`（`t0.java:142-159`） |
| 13 | RIL `x0`（`s0.java:106-111`） | 7 → `twoyi_ril_thread`（`t0.java:160-167`） |
| 14 | telephony `b1.a(LocalSocket)`（`s0.java:112-114`） | 8 → `twoyi_telephony_thread`（`t0.java:168-175`） |
| 15 | sensors `a1.b(LocalSocket)`（`s0.java:115-117`） | 9 → `SensorManager` + `twoyi_sensor_thread`（`t0.java:176-187`） |
| 16 | KernelSU `KernelSuHal.handle`（`s0.java:118-121`） | 无 |
| 17 | 来宾控制服务 `j0.a(LocalSocket)`（`s0.java:122-124`） | 无 |
| 其它 | `"unknown hal type: "` 后关闭（`s0.java:125-128`） | — |

> 注意 4/7/9 等类型号在 `t0.e()` 读到的**第一次** `int32` 上就已经生效；模块内部若还要再读 `int32`（如 type 10/12 的命令、type 8 的版本号），那是**第二个** int32。

### 1.3 字节序与帧规则

| 规则 | 值 | 证据 |
|---|---|---|
| 整数/浮点字节序 | **一律 little-endian** | `t0.e/f`（`t0.java:238-246`）、`k.d/l/p/q`（`k.java:108-137,369-482`）、`a1.d`（`a1.java:66-68`）、`e1.C/H`（`e1.java:180-222`）、`j0.b`/`a8.d2.T`（`a8/d2.java:815`） |
| 字符串 | UTF-8，定长缓冲补 `0`，按 UTF-8 码点边界截断 | `a1.a(ByteBuffer,String)`（`a1.java:29-64`，上限 63 字节） |
| 单次请求/应答上限 | camera 请求 payload ≤ **4096**（`k.java:398`）；控制帧 ≤ **1048576**、累计输出 ≤ 32 MiB（`j0.java`、`a8/d2.java` 同值）；camera 错误串 ≤ **1024**（`k.java:443`）；wifi JSON ≤ **65536**（`e1.J`，`e1.java:240-250`）；GPS 帧总长 ≤ 24+6148（`k.java:114`） | 见「证据索引」 |
| 超时 | resolver 5 s（`TwoyiResolverHal.java:14`）；GPS 首包 5 s 后置 0（`r0.java:298,426`）；camera 会话无 SO_TIMEOUT，靠 `poll()` 侦测对端关闭（`s0.java:193-221`） | — |

各模块负载是否为流式（无帧）取决于模块；详见 §3。

## 3. 服务端实现（谁 bind、谁 accept、谁 dispatch）

- 启动：`e0.g()` → `t0.b(Context)`（`t0.java:61-72`，`AtomicBoolean` 保证只启动一次）→ `f5906h.execute(new d8.d(3))`。
- bind：`d8.d` case 3（`d8/d.java:58-97`）`new LocalSocket(2)` → `bind(LocalSocketAddress("twoyi_hal", ABSTRACT))` → `new LocalServerSocket(fd)` → `f5909c = true`、`f5908b.countDown()`（等待就绪的锁存器，其它代码可据此判断 HAL 已就绪）→ `Log.i("t0","HAL socket ready: twoyi_hal")`。
- accept 循环：同一线程 `while (!currentThread.isInterrupted()) t0.f5906h.execute(new s0(0, t0Var, localServerSocket.accept()))` —— **每连接一个线程，阻塞读，无连接数上限**。
- 分发：`s0.run()` case 0（见 §1.2）。每个模块 handler 结束时**自己**关闭 socket（`IOUtils.closeSilently`），分发层不负责回收。
- camera 额外起一个守护线程 `twoyi_camera_v2_peer_close`（`g0.e()`，`g0.java:149-153`）用 `Os.poll()` 监听挂断。

## 4. 逐模块命令表

下表的「命令」就是首包 `int32 type` 之后模块自己读到的字节。**没有**统一长度前缀；多数模块直接读定长 `int32`。

### 3.1 audio（type 0 / 1 / 10）

三条独立连接，三种语义：

| 连接 | 方向 | 请求 | 应答 | 证据 |
|---|---|---|---|---|
| **0** 播放 | 来宾 → 宿主 | 无命令字节，直接是裸 PCM 流 | 无（host→guest 不写） | `s0.java:137-191`（case 1：`inputStream.read(4096)` → `AudioTrack.write`） |
| **1** 录音 | 宿主 → 来宾 | 无命令字节（权限申请成功后开始） | 裸 PCM 流 | `w.java:57-206`（`audioRecord.read(1280)` → `outputStream.write`） |
| **10** 控制 | 双向 | `int32 cmd` | `int32 result` | `y.c(LocalSocket)`（`y.java:78-99`） |

控制通道命令（`y.c` → `y.e(int)`，`y.java:101-130`）：

| cmd | 语义 | 负载 | 应答 | 说明 |
|---|---|---|---|---|
| 1 | `AudioManager.setMode(mode)` | 紧跟一个 `int32 mode` | `0` 成功 / `-22` 非法 mode（不在 0..3）/ `-19` 无 AudioManager / `-5` 运行时异常 | 模式 0..3 = NORMAL/RINGTONE/IN_CALL/IN_COMMUNICATION（`AudioManager`） |
| 其它 | 未知命令 | — | `-22` | `y.java:85-88`，随后关闭连接 |

音频参数是**硬编码**，线上不协商：播放 44100 Hz、`channelMask=12`（`CHANNEL_OUT_STEREO`）、`encoding=2`（`PCM_16BIT`）、缓冲 `max(minBuf,8192)`（`y.b()`，`y.java:46-57`）；录音 8000 Hz、`channelMask=16`（`CHANNEL_IN_MONO`）、`PCM_16BIT`、缓冲 `max(minBuf,1280)`（`w.java:74-87`）。

> **没有 3 条命令**：控制通道在本版只有 cmd 1 一条。播放/录音连接是纯字节流（所以「命令编号」对它们不存在）。这与任务书的「至少 3 条」不符，是**事实**而非遗漏。

### 3.2 sensors（type 15）

`a1.b(LocalSocket)`（`a1.java:75-162`，jadx 给出 smali 级 dump，可逐行读）。循环结构：`t0.d(in, buf4, 4)` → `a1.d(buf,0)` 取 **int32 LE** 命令号。

| cmd | 语义 | 请求负载 | 应答 | 证据 |
|---|---|---|---|---|
| 1 | 取传感器列表 | 无 | 见 §4.3（`4 + 156*N` 字节） | `z0.e()`（`z0.java:183-237`） |
| 2 | 激活/去激活一个传感器 | `int32 type`、`int32 enable`（非 0 生效）、`int32 samplingPeriodUs`，共 12 字节 | 无 | `z0.a(int,int,int)`（`z0.java:61-105`）→ `b(type, period, enable)`（`z0.java:107-135`，`registerListener(..., max(period, sensor.minDelay), handler)`） |
| 3 | flush 标记 | `int32 type` | 无（只往事件流里压一个 28 字节标记帧） | `z0.d(int)`（`z0.java:164-181`） |
| 其它 | 未知 | — | 关闭连接 | `a1.java:121-131`（`"unknown sensor command: "`） |

事件流（host → guest，纯推送，无帧头）每帧固定 **28 字节**（`z0.onSensorChanged`，`z0.java:248-292`）：

| 偏移 | 类型 | 字段 |
|---|---|---|
| 0 | `int32` | sensor type（`Sensor.getType()`；step counter = 19） |
| 4 | `int64` | `SensorEvent.timestamp`（ns） |
| 12 | `int32` | accuracy（flush 标记恒为 1） |
| 16 | `f32×3` | value[0..2]（type==19 时改为 `int64` 步数 + `int32` 0） |

flush 标记帧（cmd 3 触发，`z0.d`）：`int32 0` / `int64 elapsedRealtimeNanos` / `int32 1` / `int32 sensorType` / `int64 0`。
**推测**：偏移 0 = 0 与偏移 12 = 1 一起构成「非真实事件」标志；偏移 16 的 4 字节复用 value[0] 位置存放传感器 type。依据：同一方法内 `allocate(28)` + `putInt(0)`+`putLong(ts)`+`putInt(1)`+`putInt(i)`+`putLong(0)` 的写入顺序，与正常事件的 `putInt(type)`+`putLong(ts)`+`putInt(acc)`+`putFloat×3` 对齐。

### 3.3 gps（type 8）

`r0.j(LocalSocket)`（`r0.java:292-1162`，方法体内内联了多份 `l0` lambda）。握手与命令：

| 阶段 | 值 | 证据 |
|---|---|---|
| 首包 | `int32` wire 版本，**必须 = 2**；不等则 `"unsupported GPS wire version: "` 后关闭 | `r0.java:299-303` |
| ACK | 回写 `int32 2`（`t0.f(outputStream,2)`），随后 `setSoTimeout(0)` | `r0.java:425-426` |
| 命令 | **单字节**（`inputStream.read()`）：`1` = OPEN，`0` = CLOSE | `r0.java:547-548`（`read == 1`）、`r0.java:667`（`read == 0`） |
| 其它单字节值 | 走 `default`：若连接仍有效则等同重复 CLOSE（`"ignore duplicate GPS CLOSE"`） | `r0.java:531-536`（jadx 对同一 lambda 内联了多份，取活动路径） |

> 注意 OPEN=1、CLOSE=0，**与通常习惯相反**，且两条命令都没有附加负载。

host → guest 的 GPS 帧（`k.d(int kind, long seq, long elapsedNanos, byte[] payload)`，`k.java:108-137`）：

| 偏移 | 类型 | 字段 | 约束 |
|---|---|---|---|
| 0 | 4×`u8` | 魔数，原始字节 `"TGPS"`（`{84,71,80,83}`，`k.java:27`） | 注意是**正序字节**，不是 LE int |
| 4 | `u8` | version = 2 | 硬编码 |
| 5 | `u8` | kind：1 = NMEA，2 = 卫星快照 | `k.java:109-111` |
| 6 | `u16` | headerSize = 24 | 硬编码 |
| 8 | `u32` | payloadLen | — |
| 12 | `u32` | seq（1..4294967295 循环，`r0#q()` 维护，`r0.java:1230-1258`） | `seq != 0` |
| 16 | `int64` | `elapsedRealtimeNanos`（LOCATION 帧取 `Location.getElapsedRealtimeNanos()`，卫星帧取 `SystemClock.elapsedRealtimeNanos()`） | > 0 |
| 24 | `bytes` | payload | NMEA 3..256 字节且必须以 `\r\n` 结尾、只含可见 ASCII（`k.e`，`k.java:139-154`）；卫星快照见下 |

- kind 1 payload = 一条 NMEA 句子，实测由 `GPGGA` 模板拼出（`r0.d(Location)`，`r0.java:190-238`，含 XOR 校验 `*%02X`）。
- kind 2 payload = `u16 count` + `u16 24` + `count × 24 字节`（`k.f`，`k.java:156-180`），每颗卫星 24 字节 = `u8 constellation`（0..7）、`u8 flags`（低 5 位有效，bit3 = 有载波频率，bit4 = 有 baseband C/N0）、`u16 svid`（1..200）、`f32 cn0DbHz`（0..63）、`f32 elevationDegrees`（-90..90）、`f32 azimuthDegrees`（0..360）、`f32 carrierFrequencyHz`、`f32 basebandCn0DbHz`（字段语义见 `j.java:30-64` 的构造校验）。
- OPEN 成功且宿主有 12 小时内定位缓存时，宿主会**先**主动推一个 seq=1 的 seed 帧（`r0.java:443-486`，OPEN 分支；同一 lambda 被内联多次）。

### 3.4 lights（type 4）

`u0.a(LocalSocket)`（`u0.java:34-51`）：

| 命令 | 负载 | 应答 | 说明 |
|---|---|---|---|
| 唯一一条 | `int32 brightness`，合法 0..255（`<0` 或 `>255` 丢弃并记日志） | 无 | 值变化时 `b()`：`io.twoyi.z.b(brightness/255.0f)` 设置宿主背光，并广播 `io.twoyi.light.CHANGE`（`u0.java:53-115`） |

即「背光」没有命令编号，**一次连接 = 一个亮度值**，连接随即关闭（`u0.java:49`）。

### 3.5 vibrator（type 3）

`c1.a(LocalSocket)`（`c1.java:22-36`）：

| 命令 | 负载 | 应答 | 说明 |
|---|---|---|---|
| 唯一一条 | `int32 durationMs` | 无 | `0` → `Vibrator.cancel()`；`>0` → `VibrationEffect.createOneShot(durationMs, -1)`（默认振幅）；`<0` → 丢弃并 `"reject negative vibrator duration"`；宿主无振动器时静默忽略（`c1.java:38-55`） |

同样「一次连接 = 一个值」，无命令编号。

### 3.6 camera（type 5 / 6）：`twoyi_camera_transport` 的现状

**0.7.5 Java 层没有 `twoyi_camera_transport` 这个 socket**：全量源码 grep 无命中；`GuestEndpoints` 只导出 `twoyi_hal` + `TWOYI_SOCK`（`io/twoyi/o.java:41`）。camera 被并入 `@twoyi_hal`：

- **type 5 = 目录/工厂**（`g0.f(LocalSocket)`，`g0.java:199-288`）：循环读请求，唯一合法 opcode = **1**（`if (r0 != 1) throw new h("Invalid opcode on camera factory connection")`），且 payload 必须为 0 字节（`k.m(pkt, 0)`）；应答 payload = 宿主相机的「目录」字节串（`a8.d2.J()` 生成，`a8/d2.java:355-383`）；失败时回 status 9 + `"Camera catalog unavailable"`。目录编码器 `a8.d2.C(ArrayList)` 在 jadx 里是 `Method dump skipped, instructions count: 808`，**payload 布局无法从 Java 侧还原**（见 §8）。
- **type 6 = 会话/取帧**（`g0.e(LocalSocket)`，`g0.java:149-190`）：读请求 → `g(f0, pkt, out)` 分发；`g` 是 `Method dump skipped, instructions count: 1203`（`g0.java:299-305`），**具体 opcode 号无法从 Java 侧还原**。

能确定的通用帧结构（`k.l` 读、`k.p/q` 写、`k.o` 写错误）：

**请求头 32 字节（`k.l`，`k.java:369-415`）**

| 偏移 | 类型 | 字段 | 校验 |
|---|---|---|---|
| 0 | `int32` | 魔数 `843274068` = `0x32435754`，字节序反转即 ASCII `"TWC2"` | 必须相等 |
| 4 | `u16` | version = **2** | 必须 = 2 |
| 6 | `u16` | headerSize = **32** | 必须 = 32 |
| 8 | `u16` | opcode | 必须无 bit15（bit15 表示「应答」） |
| 10 | `u16` | flags | 请求必须 = 0 |
| 12 | `u32` | sequence | 必须非 0 且**严格递增**（`seq > 上一包 seq`） |
| 16 | `int32` | status/reserved | 请求必须 = 0 |
| 20 | `u32` | payloadLen | 必须 ≤ 4096 |
| 24 | `int32` | reserved | 必须 = 0 |
| 28 | `int32` | reserved | 必须 = 0 |

**应答头 32 字节（`k.p`，`k.java:457-474`）**

| 偏移 | 类型 | 字段 |
|---|---|---|
| 0 | `int32` | `843274068`（`"TWC2"`） |
| 4 | `u16` | 2 |
| 6 | `u16` | 32 |
| 8 | `u16` | `opcode | 0x8000`（`k.q`/`k.o` 会强制置 bit15） |
| 10 | `u16` | 0 |
| 12 | `u32` | 回显请求 seq |
| 16 | `int32` | status（0 = 成功） |
| 20 | `int32` | payloadLen（应答无 4096 上限，只有 ≥0 约束） |
| 24 | `int32` | 0 |
| 28 | `int32` | 0 |

**错误负载**（`k.o`）：`int32 msgLen` + UTF-8 消息（≤1024 字节，按码点截断），status 必须非 0。

**帧（预览图像）负载 —— 24 字节帧头 + NV21 像素**（`g0.k(OutputStream, i, v)`，`g0.java:78-88`）：

| 偏移 | 类型 | 字段 | 依据 |
|---|---|---|---|
| 0 | `int32` | 预览宽 W | `v.f5932d.f5806a`（`g` = 预览尺寸） |
| 4 | `int32` | 预览高 H | `v.f5932d.f5807b` |
| 8 | `int32` | 格式魔数 `825382478` = `0x3132564E`，即 ASCII `"NV21"` | 硬编码常量 |
| 12 | `int32` | fps 下限 | `v.f5933e.f5772a`（`e` = `Range(lower,upper)`） |
| 16 | `int32` | fps 上限 | `v.f5933e.f5773b` |
| 20 | `int32` | 像素缓冲字节数 = `W*H*3/2` | `v.f5936h = k.j(W,H)`（`k.java:291-297`），并据此预分配 3 个缓冲（`new ba.o(k.j(W,H))`，`v.java:150-151`；`ba.o.B(byte[])` 回收池上限 3，`ba/o.java:264-268`） |

- **没有 stride 字段**：宿主把 `YUV_420_888`（format 35）在内存里按 `Image.Plane.rowStride/pixelStride` 采样、压成紧密排列的 NV21（`k.k`，`k.java:299-367`；`d(ByteBuffer,rowStride,pixelStride)` + `d.a(...)` 逐行/逐像素拷贝，`d.java:229-238`、`d.java:40-79`），拷贝进连续的裸 `byte[]`，所以 wire 上只传 W/H/NV21 与长度。
- **没有时间戳字段**：24 字节帧头里没有任何 timestamp；`v.H`（帧缓冲大小）、`v.I`/`v.N`（序号/状态计数，`v.java:34,109-110`）都不进 wire。
- **没有 fd 传递**：全程 `OutputStream.write`（Java 堆 byte[]），没有 `SCM_RIGHTS`/`ParcelFileDescriptor`（对比 type 16 才有 fd dup，见 §6）。
- **推测（帧头与像素如何分成两包）**：`g0.k` 只写 32 字节应答头 + 24 字节帧头（`k.q(out, opcode, seq, 0, 24B)`），像素数组本身由 `v.h(long,q)→s`（capture 结果结构）另行发出。依据：`g0.k` 的 payload 恰为 24 字节、不含 `byte[] bArr`；而 `s`/`t` 持有 `byte[]` + `ba.o` 回收句柄（`s.java:40-62`），说明像素走单独一包。**具体两包的 opcode 对应关系无法确定**（`g0.g` 未反编译）。

**帧头之外还确认了请求侧语义字段**（供将来对 opcode 时对照）：`q` 有 9 个 `int` 字段，`v.f()` 把它们当作 `CONTROL_MODE`/`CONTROL_AF_MODE`/`CONTROL_AF_TRIGGER`/`CONTROL_AE_MODE`/`CONTROL_AE_EXPOSURE_COMPENSATION`/`CONTROL_AE_PRECAPTURE_TRIGGER`/`CONTROL_AWB_MODE`/`FLASH_MODE`（`v.java:410-428`）；`a`（`q.f5861a`）在 `v.f` 中未使用，**推测**是请求编号/帧率上限。

### 3.7 其它通道（简述，不在本次重点）

| type | 模块 | 形态 |
|---|---|---|
| 9 | `k.h(Context,LocalSocket)`（`k.java:212-270`） | 首字节 `0` → 再读 8 字节 → `sendBroadcast(TIME_SET)`；首字节 `1` → `int32 LE len` + len 字节字符串 → `TIME_SET`。**推测** 8 字节为时间值（未在 Java 侧解释） |
| 11 | `b0`（`b0.java:23-37`，转发在 `z.java:100-122`） | 纯推送：首字节 `2` + `int32 LE len` + `Parcel.marshall(Intent)`；监听 `BATTERY_CHANGED`/`TIME_SET`/`TIMEZONE_CHANGED`/`DATE_CHANGED`/`TIME_TICK` |
| 12 | `e1.x(LocalSocket)`（`e1.java:954-1118`） | 先读 `int32 LE`：`<0`（且 ≠ `Integer.MIN_VALUE`）= 事件流会话（会话号 = `-值`），`>0` = 命令会话，其余抛 `IOException("Invalid WiFi forward session id")`；命令会话再读 **单字节 cmd 0..15**（0 先读一个 `int32` 再回 0/1、1/3/4 回 JSON 串（先 `int32 len`）、2 回 `int32`、5/6/7 回 `Parcel`（先 `int32 len`）、10/11 回 0/1、13/14 读一个 `int32` 后回 0、15 回 wifi 快照（`int32 len` + ≤64 KiB））；写回一律 `int32 LE` 长度或返回值（`e1.H/I/J`，`e1.java:220-250`） |
| 13 | `x0`（`io/twoyi/hal/x0.java`）+ `ril.TwoyiModemNative` | 首包后把 socket 交给 native：`init(LocalSocket, String)`（`ril/TwoyiModemNative.java:25`），线格式在 libtwoyi.so |
| 14 | `b1.a(LocalSocket)`（`b1.java:56-77`） | 无请求帧：注册 `NetworkCallback` 与 `SIM_STATE_CHANGED`，向 socket 推送 |
| 17 | `j0.a(LocalSocket)`（`j0.java:143-175`） | 首包 `int32` 版本必须 = **1**（回 `int32 1`）；之后帧头 **9 字节**：`int32 payloadLen`（≤1048576）、`int32 requestId`、`u8 kind`；kind `3` = stdout 片段、`4` = stderr 片段、`5` = 退出（payload 为 `int32 status`）、`6` = 错误文本；同一 socket 上的 host→guest 方向用相同 9 字节头（`a8/d2.T`，`a8/d2.java:815-822`） |

## 5. 关键字节布局汇总

### 4.1 `@twoyi_hal` 连接级

```
guest → host : int32 LE type
host → guest : int32 LE type          // ACK（不可用类型也会回显，随后 close）
guest → host : <模块私有字节流>
```

### 4.2 camera `TWC2` 头（请求/应答同构，32 字节）

见 §3.6 两张表；魔数 `0x32435754`（线上 4 字节 = `T W C 2`），version 2，headerSize 32，seq 严格递增，请求 payload ≤ 4096，应答 opcode 置 bit15。

### 4.3 sensors 传感器列表（`4 + 156*N`）

```
u32 count
repeat count:
  int32 type
  int32 minDelay
  int32 maxDelay
  int32 fifoMaxEventCount
  f32   maximumRange
  f32   resolution
  f32   power
  u8[64] name  (UTF-8, NUL 填充)
  u8[64] vendor(UTF-8, NUL 填充)
```
`N` = 正常枚举到的传感器（`a1.f5722d` 白名单 `{1,2,4,5,6,8,19}`，最多 7 个）+ 可能的合成 step counter（type 19，`name="Step Counter"`、`vendor="twoyi"`）→ 最多 8 条、最大 **1252 字节**。

### 4.4 GPS `TGPS` 帧（24 + payload）

见 §3.3。

### 4.5 camera 帧头（24 字节 payload）

```
int32 W; int32 H; int32 0x3132564E("NV21"); int32 fpsMin; int32 fpsMax; int32 frameBytes(=W*H*3/2)
```

### 4.6 控制服务帧（9 字节 + payload）

```
int32 payloadLen; int32 requestId; u8 kind
```

### 4.7 `TwoyiResolverHal`（type 2）

```
guest → host : 360 字节，定长，无长度前缀
host → guest : 988 字节，定长
```

## 6. `TwoyiResolverHal`（type 2）

`TwoyiResolverHal.a(LocalSocket)`（`TwoyiResolverHal.java:12-31`），全流程：

1. `setSoTimeout(5000)`；
2. `t0.d(in, buf, 360)` —— **死读 360 字节**（不读长度、不看魔数）；
3. `nativeResolve(buf)` —— `private static native byte[] nativeResolve(byte[])`，实现在 `libtwoyi.so`（`v0.java:10-12` 的 `System.loadLibrary("twoyi")`）；
4. 返回值必须**恰为 988 字节**，否则 `throw new IOException("Invalid resolver response")`；
5. 写出 988 字节并 `flush`，随后关闭 socket（`finally` 里 `IOUtils.closeSilently`）。

**已确定（字段级）**

| 项 | 值 | 依据 |
|---|---|---|
| 请求长度 | 360 字节，定长 | `byte[360]` + `t0.d(...,360)` |
| 响应长度 | 988 字节，定长（严格相等判断） | `nativeResolve.length == 988` |
| 前缀/魔数/校验 | 无（Java 侧不做任何解析） | 同上，无 `ByteBuffer`、无 `getInt()` |
| 字节序 | **未知**（Java 不解包） | — |
| 会话模型 | 一问一答一连接（读完立刻回写并关闭） | `return` 在 `finally` 之前，无循环 |
| 超时 | 请求 5 s；响应写出无超时 | `setSoTimeout(5000)` |
| 触发条件 | 只在来宾主动连 `@twoyi_hal` 且首包 `int32 == 2` 时进入 | `s0.java:71-74` |

**推测（必须标注）**

- 「360 / 988 与 bionic `res_state` / `addrinfo` 数组无关的证据都没有」——本机资产无法证明任何一种。可能的模型，按可信度排序：
  1. **推测 A：请求 = 定长解析请求（hostname + family/socktype/protocol/flags…），响应 = 定长结果槽数组。** 依据：宿主侧只做「转发 + 长度校验」，真正的解析在 native；两方向长度不同且都定长，符合「查询小、结果大」。
  2. **推测 B：请求 = 来宾 DNS 配置快照，响应 = 宿主 DNS 配置回填。** 依据：`TwoyiResolverHal` 只被 `s0` 调用一次/连接，且 `v0` 单例；但 360≠988 说明不是同构结构，可信度低于 A。
  3. **推测 C：360 = 头部 + 变长字段的固定上界**（即结构体带固定上限的缓冲区），988 同理。无从验证。
- 直接否定项：**响应不是**「`int32 长度` + 变长内容」——若是，Java 不会要求恰好 988 字节。

**如何证伪/证实（下一步）**：`libtwoyi.so` 只导出 `JNI_OnLoad`（`native-symbols.txt`），所以 `nativeResolve` 是通过 `RegisterNatives` 注册的；需要在云端 Ghidra 里从 `JNI_OnLoad` 找 `RegisterNatives` 表拿到 `nativeResolve` 地址，读它的读/写偏移；或在运行的来宾里抓一次真实的 360 字节请求。


## 7. camera 的 opcode 已解（smali 实证）

上一版把 camera opcode 列为空缺，理由是两个方法被 jadx 跳过。改用 **apktool 解 smali** 后
（`.github/workflows/smali-focus.yml`，run 45914008675）在 `io/twoyi/hal/g0.smali` 的
`g(Lio/twoyi/hal/f0;Lio/twoyi/hal/i;Ljava/io/OutputStream;)V` 里直接读到了分发链：

```
iget  v0, v3, Lio/twoyi/hal/i;->a:I     # v0 = 请求 opcode（来自来宾，不是常量）
const/4 v10, 0x2
if-eq v0, v10, :cond_29               # opcode 2
const/4 v6, 0x3
if-eq v0, v6,  :cond_7                # opcode 3
const/4 v14, 0x4
if-eq v0, v14, :cond_2                # opcode 4
const/4 v13, 0x5
if-eq v0, v13, :cond_1                # opcode 5
const/4 v12, 0x6
if-ne v0, v12, :cond_0                # opcode 6；否则落到 fallthrough
```

结论：

| 项 | 值 | 证据 |
|---|---|---|
| 被服务的请求 opcode | **2、3、4、5、6**（各走不同 `:cond_*` 分支） | 上述 if 链 |
| 未知 opcode 的处理 | **不回错误，走 fallthrough** 仍会写出应答 | 链尾无 "unknown opcode" 抛出（`Unknown camera v2 device opcode` 只在另一处引用） |
| 应答 opcode | `请求 opcode \| 0x8000`（`const v2, 0x8000` + `or-int/2addr`） | 同一方法内三处 |
| opcode 是否宿主常量 | **否**，`v0` 来自请求对象 `i.a` | `iget` |

每个分支随后都调用同一组：
`k.m(i, 0)`（清标志）→ `g0.h(f0)`（取帧）→ `this.b(f0)`（送帧）→
`k.q(OutputStream, seq, ts, opcode, ...)` 或 `k.p(...)` 写回。区别在于 opcode 4 分支额外传了
`v0`（原 opcode）给 `k.m`，其余传 `v15 = 0`。

**因此 camera 的完整线格式现在是**：
32 字节 `TWC2` 信封（魔数 843274068、version 2、headerSize 32、seq 递增、payload ≤ 4096、应答 opcode 置 bit15）
+ 24 字节帧头（`W, H, 0x3132564E("NV21"), fpsMin, fpsMax, W*H*3/2`）+ 紧密 NV21 像素，
请求 opcode 取 2..6，宿主对未知值不报错直接回帧。

**仍未解**：opcode 2..6 各自的语义（预览/拍照/参数查询？）；`k.m` 的 `0` 与 `v0` 两种传参差异含义。
这些需要真机抓一次两侧字节，或看 `libtwoyi.so` 里对应实现。另外更正：`a8/d2` 与 camera 无关
（`d2.smali` 是混淆过的 UI 类，含 `EditText`/`Parcel`/`AttributeSet`），上一版把它列为 camera 缺口是误判。

## 8. `KernelSuHal`（type 16）

`KernelSuHal.handle(LocalSocket)`（`KernelSuHal.java:15-25`）：

1. `ParcelFileDescriptor.dup(localSocket.getFileDescriptor())` —— **在宿主进程内 dup 一份 fd**；
2. `nativeHandle(dup.getFd())` —— 把 **int fd** 交给 native（`private static native void nativeHandle(int)`）；
3. `dup.close()`（native 侧若需继续用，必须自己再 dup/接管）；随后 `s0` 关闭原 socket（`s0.java:119-120`）。

握手事实：

- **没有额外负载**：`s0` 只读了 4 字节 type 并回写同一 int32，然后直接 `handle`。所以「KernelSU 握手」= `int32 16` + 回显，之后**双方不再有 Java 侧字节交换**。
- **fd 传递方式 = 进程内 `dup()` + 传 `int`**，不是 `SCM_RIGHTS`（Java 侧没有任何 `sendmsg`/辅助数据代码），也不是 `ParcelFileDescriptor` 走 Binder。
- `isSupported()` 在本版恒为 `true`（`KernelSuHal.java:19-21`）；`false` 分支（`s0.java:46-48` `"KernelSU HAL unsupported in this backend"`）是给别的后端留的。
- native 侧拿到的是什么语义（KernelSU 的 `fd` 代理 / 提权通道 / 直接与 `ksud` 对话）：**未解**，需要 `nativeHandle` 的反汇编。


## 9. resolver 的 native 入口：按名字找不到（负结论）

上一版推测「`nativeResolve` 由 `RegisterNatives` 动态注册」，理由是 `.so` 只导出 `JNI_OnLoad`。
扩大字符串搜索后（`.github/workflows/strings-focus.yml`，run 45927015406，`libtwoyi.so` 共 43,881 条字符串），
这个推测**不成立**：

| 搜索 | 结果 |
|---|---|
| 任何含 `resolve` 的字符串 | 只有 Vulkan 的 `vkCmdResolveImage` 系列与 `STCompileAndResolve`，**没有任何 JNI 名字** |
| `^native[A-Za-z0-9_]+$` 形式的标识符 | **只有 `nativeHandle` 一个** |
| 形如 `%s/…` 或 `*.sock/.pipe/.json` 的路径串 | **0 条** |
| gfxstream 相关且含 `/`、`%`、`.` 的串 | 只有日志格式串，没有文件路径 |
| `libloader.so` 里的 `resolve/endpoint/broker/notify` | **0 条**（该库的字符串是 XOR 编码的，明文本来就不出现） |

也就是说：`nativeResolve` 既不在 `libtwoyi.so` 的明文名字表里，也没有 `Java_…_nativeResolve` 导出符号
（该库只导出 `JNI_OnLoad`）。三种可能：

1. 名字在运行时拼装（与 `libloader.so` 同一套混淆思路）；
2. 该 Java 声明是**死代码**——resolver 请求实际由 `@twoyi_hal` 的 type 2 分支在 native 侧直接处理，
   不经过这个 JNI 方法；
3. 实现落在别的库（但 APK 里 `lib/arm64-v8a` 只有 `libtwoyi/libloader/libadb/libOpenglRender`）。

**对 988 字节布局的含义**：靠名字定位这条路已经彻底走不通，只能对 `libtwoyi.so` 做**按调用点**的
反汇编（从 `@twoyi_hal` 的 accept 循环往下追，而不是从名字入手），或真机抓一次 360/988 的真实字节。

**顺带确认**：`gfxstream-vulkan` 目录里的文件名**没有任何明文串**——它同样只能是运行时构造或
由 `libtwoyi.so` 自己拼出来的，这解释了为什么按路径格式搜索一直是 0 条。

## 10. 证据索引

| 结论 | 类#方法 / 常量 |
|---|---|
| bind `@twoyi_hal`（ABSTRACT） | `d8.d#run()` case 3，`d8/d.java:64,72-80` |
| accept 循环、每连接一线程 | `d8/d.java:78-80`；线程池 `t0.f5906h`（`t0.java:35`） |
| 首包 `int32 LE` 读/写 | `io.twoyi.hal.t0#e(InputStream)`（238）、`t0#f(OutputStream,int)`（244） |
| 类型 ACK 回显 | `io.twoyi.hal.s0#run()` case 0（`s0.java:45-53`） |
| type→模块分发表 | `s0.java:54-129` |
| type→懒初始化槽位 | `t0#a(int)`（248-312）、`t0#c(Context,int)`（75-220） |
| HAL 启动入口 | `io.twoyi.e0#g(String)`（93-120）、`t0#b(Context)`（61-72） |
| 端点常量 | `io.twoyi.o`（21，41）；`io.twoyi.GuestEndpoints.DEFAULT_HAL_SOCKET` |
| audio 播放 PCM | `s0.java:137-191`；格式 `y#b()`（`y.java:46-57`） |
| audio 录音 PCM | `w#run()`（`w.java:57-206`）；格式 `w.java:74-87` |
| audio 控制 cmd 1 | `y#c(LocalSocket)`（`y.java:78-99`）、`y#e(int)`（101-130） |
| sensors 命令 1/2/3 | `a1#b(LocalSocket)`（`a1.java:75-162`）、`z0#e/a/b/d`（`z0.java:164-237`） |
| sensors 列表 156 字节/条 | `z0#e()`（`z0.java:205-235`）、`a1#a(ByteBuffer,String)`（`a1.java:29-64`） |
| sensors 事件 28 字节 | `z0#onSensorChanged`（`z0.java:248-292`） |
| GPS wire v2 握手 | `r0#j(LocalSocket)`（`r0.java:299,425`） |
| GPS OPEN=1 / CLOSE=0 | `r0.java:548,667` |
| GPS 帧编码 | `io.twoyi.hal.k#d/l/e/f`（`k.java:108-180,369-415`） |
| GPS seq 循环 | `r0#q(int,long,byte[],OutputStream,long)`（`r0.java:1230-1258`） |
| lights 亮度 | `u0#a(LocalSocket)`（`u0.java:34-51`）、`u0#b()`（53-115） |
| vibrator 时长 | `c1#a(LocalSocket)`（`c1.java:22-36`）、`c1#b(int)`（38-55） |
| camera 工厂（type 5） | `g0#f(LocalSocket)`（`g0.java:199-288`）、`a8.d2#J()`（`a8/d2.java:355-383`） |
| camera 目录项字段 | `io.twoyi.hal.a`（`a.java:8-65`）、`a8.d2#Q(String)`（`a8/d2.java:726-788`） |
| camera 会话（type 6） | `g0#e(LocalSocket)`（`g0.java:149-190`）、`g0#g(f0,i,OutputStream)`（**未反编译**，`g0.java:299-305`） |
| camera 请求/应答头 | `k#l`（369-415）、`k#p`（457-474）、`k#q`（476-482）、`k#o`（435-455）、`k#m`（417-422） |
| camera 帧头 24 字节 | `g0#k(OutputStream,i,v)`（`g0.java:78-88`） |
| camera NV21 转换/无 stride | `k#k(Image,int,int,byte[])`（299-367）、`d(ByteBuffer,rowStride,pixelStride)`（`d.java:229-238`）、`d#a`（40-79） |
| camera 帧缓冲池 | `v` 构造器（`v.java:150-151`）、`ba.o#B(byte[])`（`ba/o.java:264-268`） |
| camera peer-close 侦测 | `s0#run()` default（`s0.java:193-221`） |
| resolver 360/988 | `TwoyiResolverHal#a`（`TwoyiResolverHal.java:12-31`）、`v0`（`v0.java:10-12`） |
| KernelSu fd dup | `KernelSuHal#handle`（`KernelSuHal.java:15-25`）、`s0.java:118-121` |
| 控制服务 9 字节头 | `j0#b(InputStream,d2)`（`j0.java:41-138`）、`a8.d2#T`（`a8/d2.java:815-822`） |
| 时间通道 | `k#h(Context,LocalSocket)`（`k.java:212-270`） |
| 宿主广播通道 | `b0#a`（`b0.java:23-37`）、`z#onReceive` case 1（`z.java:100-122`） |

## 11. 未解问题

1. **`android.net.LocalSocket(int)` 的 `2` / `3` 到底映射哪个 `SOCKET_*`**。反编译只有字面值（`d8/d.java:64` = 2，`io/twoyi/m.java:101` = 3），且两者都用于 `bind`+`accept`。本机实测 AF_UNIX 上 `SOCK_DGRAM` 不能 `listen()`、`SOCK_SEQPACKET` 可以，所以二者都必须是面向连接的类别；具体取值需在真机上看 `socket(2)` 的 `type` 参数或 `/proc/net/unix` 的 flags。
2. **camera opcode 2..6 各自的语义**（编号已解，见 §7；语义未定）。
3. **camera type 5 的目录 payload 布局**（需要看 `g0.f` / 目录项序列化的具体偏移）。只能列出目录项的**语义字段**（`io.twoyi.hal.a`：id / lensFacing / orientation / `List<g>` 尺寸 / `List<e>` fps 区间 / ae、awb、af 模式数组 / timestampSource / AE 补偿 min,max,num,den / 最小焦距）。
4. **帧头 24 字节与像素包的 opcode 对应关系**（同一 opcode 的第二包？还是不同 opcode？）——同 2。
5. **`TwoyiResolverHal` 360/988 的字段布局**：完全在 `libtwoyi.so` 的 `nativeResolve` 内；Java 层只固定了长度、超时与「一问一答一连接」。
6. **`KernelSuHal` native 侧用 fd 做什么**：`nativeHandle(int fd)` 之后的行为无 Java 证据。
7. **帧头是否存在时间戳的隐藏约定**：24 字节帧头里没有 timestamp 字段，但 `v` 内部有序号/状态计数（`H`/`I`/`N`）；若来宾侧需要帧时间，只能靠「帧到达时刻」推断。
8. **`twoyi_camera_transport` 由谁 bind**：Java 层不存在该名字。要么 0.7.5 的来宾 `camera.goldfish.so` 已改为连 `@twoyi_hal` type 5/6，要么该名字由 `libtwoyi.so` 绑定后再转 Java（`libtwoyi.so` 无导出符号可查，需反汇编 `JNI_OnLoad`/字符串 xref）。
9. **type 9 的 8 字节负载语义**（时间值？时区？），以及 type 9 首字节 `0/1` 之外的取值行为（未处理 → 关闭）。

## 12. 对重写/验收的含义

- 只要实现 `@twoyi_hal` 的「`int32 LE type` + 回显」两段，再加上**任一**模块的后续字节，就能让该模块工作；其余模块不可混用格式。
- 想先做最小可用闭环，优先级建议：vibrator（1 个 int32，无应答）→ lights（1 个 int32）→ sensors（3 条命令 + 定长事件）→ audio 控制（1 条命令 + 状态码）→ audio 播放/录音（裸 PCM，最容易验证）→ gps（握手 + 单字节命令 + TGPS 帧）→ camera（自描述 32 字节头，但 opcode 未解，风险最高）。
- **不要**在 `twoyi_hal` 上先 bind 再「猜」应答：所有模块都是阻塞读（GPS 还会 `setSoTimeout(0)`），帧不合法会让来宾 HAL 长时间阻塞，比不连更差（与 `docs/HAL_BRIDGE.md` 的结论一致）。