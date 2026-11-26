# 旧 ROM HAL 桥接证据

依据：最后开源版 0.5.4 配套 `rootfs.7z` 内的 HAL 模块字符串（来自 `ylh440104/twoyi-apk-reverse` 的 `report/rootfs/stub-strings/`）。
这些是**静态字符串**，只能说明模块会尝试连接什么，不能证明请求负载的二进制布局。

## 已确认的连接目标

| 模块 | 关键字符串 | 结论 |
|---|---|---|
| `audio.primary.goldfish.so` | `twoyi_hal`、`socket_local_client`、`Failed to connect to twoyi Audio server`、`Failed to connect twoyi Audio Recording server` | 连接抽象 socket `twoyi_hal`，音频与录音各一条连接 |
| `lights.goldfish.so` | `twoyi_hal`、`Successfully connected hal server: %d` | 连接 `twoyi_hal` |
| `vibrator.goldfish.so` | `twoyi_hal`、`Successfully connected hal server: %d` | 连接 `twoyi_hal` |
| `sensors.goldfish.so` | `twoyi_hal`、`:sensors`、`host sensors server unreachable, exposing zero sensors`、`host reports %u sensors, clamping to %d` | 连接 `twoyi_hal`；无主机时优雅退化为 0 传感器 |
| `gps.goldfish.so` | `twoyi_hal`、`gps_twoyi`、`GPS wire v%u handshake failed`、`twoyi_gps_parse_frame_header/nmea/sv_snapshot` | 连接 `twoyi_hal`，握手带 wire 版本号，NMEA 与卫星快照各有解析函数 |
| `camera.goldfish.so` | `QemuClient`、`twoyi_camera_transport`、`getHostMapper` | 走 `twoyi_camera_transport` 自有协议，与 `twoyi_hal` 不同 |
| `hwcomposer.twoyi.so` | `HostConnection::get`、`Failed to get host connection` | 走渲染器共享内存，不是 HAL socket |

`GuestEndpoints.DEFAULT_HAL_SOCKET = "twoyi_hal"` 与这些字符串一致，端点定义正确。

## 为什么现在还不能实现完整桥接

1. 只有字符串，没有结构体布局、字段顺序、帧长和版本协商细节；`GPS wire v%u` 表明存在版本字段，但取值与语义未知。
2. `gps/goldfish.so` 的 `twoyi_gps_parse_frame_header` 等符号说明帧有头部 + 校验，但边界值未公开。
3. `camera.goldfish.so` 使用独立传输层，无法从 `twoyi_hal` 推断。
4. 若宿主绑定 `twoyi_hal` 却不按协议应答，来宾 HAL 会阻塞或失败，比当前“未连接、退化为空设备”更差。

因此当前策略是**不绑定** `twoyi_hal`，让旧 ROM 走自带的降级路径（例如传感器为 0 个），并把连接证据记录在 `HalConnectionRegistry` 中，供将来实现时对照。

## 本次落地

- `HalConnectionRegistry`：按模块名记录一次连接尝试与最终结果（待处理 / 不支持 / 对端关闭 / 失败），上限 16 条，重复连接不重复计数。纯 Java，可在 Actions 上直接跑。
- 未绑定 `twoyi_hal`，未伪造应答，来宾行为与之前一致。

## 下一步需要什么证据

- 用一次受控启动在来宾内 `strace`/logcat 抓 `twoyi_hal` 的首包字节，确认帧头长度与字段。
- 从旧 ROM 取 `libhardware.so` 侧调用签名，比对 `audio_hw_if` 与 lights/vibrator 的参数。
- 上述任一未取得前，HAL 桥接保持"未验收"，不宣称可用。
