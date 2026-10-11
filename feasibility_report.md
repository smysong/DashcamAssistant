# 行车记录仪助手 —— 可行性研究与协议还原报告

> **现状校正（2026-09-30）**：本文主体记录早期逆向分析与 Node/amba 参考原型的设计，不应视作当前 Android 产品架构说明。Android 车机应用已位于本仓库根目录（`app/` 模块），当前产品路径是 HTTP:80 目录/媒体读取、车机本地桥接 HTTP 和 RTSP；它没有接入本文参考原型中的 AMBA 客户端，也不提供本文原型的删除功能。当前页面/功能清单、API 路径及未确认事项以 [`./README.md`](./README.md) 为准。文中关于 UDP 发现数据格式和端口字节序的说明仍需目标 DVR 真机抓包确认。

> 目标：开发一款安装在**车机**上的「行车记录仪助手」App。手机连接车机热点、扫描助手提供的二维码后，即可在手机浏览器里**直播预览 / 下载 / 删除**车机自带行车记录仪（DVR）的录像。
> 自带录像软件 `行车记录仪_5.0.2025091318.apk` 不可下载、无法改造，但**无需改造它**——助手只需与 DVR 暴露的私有 amba 服务通信即可。

## 结论：可以实现（高置信度）

通过 apktool 3.0.3 反编译，确认该 APK 是 **`com.skyworthauto.dvrchery`**（创维/Skyworth 车机行车记录仪），内置一套完整、可还原的私有控制协议 **amba**。DVR 通过一个 WiFi 接口对外提供：
- **TCP 7878** 上的 amba JSON 命令通道（列表/下载/删除/设备信息…）
- **RTSP `rtsp://192.168.42.1/live`** 的直播流

助手 App 只要「**说 amba 话**」去连 DVR，再把结果通过 HTTP 透传给手机，整条链路即可跑通。本仓库早期附带一个**可运行参考原型**（`dashcam-assistant/`，已在本地用模拟 DVR 端到端验证通过）；该原型目前已不再是产品实现路径，仅作协议研究存档。

---

## 一、逆向还原的协议（amba over TCP）

### 1. 网络与握手
| 项 | 值 | 出处 |
|---|---|---|
| DVR 地址 | `192.168.42.1`（热点网关） | `CameraLoader.smali` |
| 命令端口 | `7878` (TCP) | `DvrInfo.smali` |
| 直播流 | `rtsp://192.168.42.1/live` | `CameraLoader.smali: setLivePath` |
| 报文格式 | 每行一个 JSON，多对象以 `}{` 粘连 | `CmdChannel.splitJsonResponse` |
| 响应标记 | 含 `rval`(0=成功, -30=无卡, -4=token错) 为响应；否则为通知 | `CmdChannel.consume` |
| 握手 | `START_SESSION(token=0)` → 回包 `param` 里是数字 sessionId；后续命令均带 `"token":<id>` | `CmdChannel.handleResponse` (0x101) |

### 2. 命令速查（取自 `Constants.smali`）
| 功能 | msg_id(十六/十) | 请求示例 | 说明 |
|---|---|---|---|
| 建立会话 | `0x101` / 257 | `{"token":0,"msg_id":257}` | 回包 `param`=sessionId |
| 结束会话 | `0x102` / 258 | `{"token":t,"msg_id":258}` | |
| 设备信息 | `0x0b` / 11 | `{"token":t,"msg_id":11}` | 回包含 `SD`(0=有卡)、`normal_folder`/`event_folder`/`photo_folder` |
| 媒体列表 | `0x10000002` / 268435458 | `{"msg_id":268435458,"token":t,"type":"normal\|event\|photo"}` | 回包 `param`=媒体项数组 |
| 取文件 | `0x505` / 1285 | `{"token":t,"msg_id":1285,"param":"<远程路径>"}` | **二进制**：先回 JSON ack，随后紧跟原始字节 |
| 取缩略图 | `0x401` / 1025 | `{"token":t,"msg_id":1025,"param":"<远程路径>"}` | 同上，二进制 |
| 删除文件 | `0x501` / 1281 | `{"token":t,"msg_id":1281,"param":"<远程路径>"}` | |
| 剩余空间 | `0x05` / 5 | — | 可选 |
| 格式化卡 | `0x04` / 4 | — | 可选（危险） |
| 心跳 | `0x10c` / 268 | — | 保活 |

### 3. 存储与媒体项字段
- 目录（来自 `CameraLoader`）：普通/紧急录像 `M_video/`，照片 `M_photo/`，缩略图 `Thumb/`
- 媒体项（`MediaItem` 模型）字段：`path`(远程路径，用于下载/删除)、`thumb`(缩略图路径)、`date`、`duration`(秒)、`size`(字节)、`type`(normal/event/photo)、`locked`(是否加锁保护)

> 注意：`GET_FILE`/`GET_THUMB` 的字节流长度，真实设备一般在 JSON ack 的 `size` 字段里给出（原型按此实现）；个别固件版本可能以「读到连接关闭」为界。对接真机时需用 Wireshark/真机抓包确认一次收尾方式（原型已兼容两种）。

---

## 二、系统架构（两种拓扑，任选）

```
       手机(浏览器)                车机(Android)                       DVR 记录仪
   ┌──────────────┐         ┌──────────────────────┐          ┌──────────────────┐
   │ 扫码 → 打开URL│  HTTP   │  行车记录仪助手        │  amba    │  TCP:7878        │
   │ 列表/预览/下载│ ───────▶ │  - Bridge HTTP 服务   │ ───────▶ │  RTSP /live      │
   │ /删除         │ ◀─────── │  - amba 客户端        │ ◀─────── │  文件/缩略图(二进制)│
   └──────────────┘         │  - UDP 发现(10000/10001)│         └──────────────────┘
                            │  - 二维码展示          │
                            └──────────────────────┘
```

> ⚠️ **关于 DVR 的 WiFi 模式切换**：逆向确认 `SET_WIFI_SETTING`/`WIFI_START` 等命令仅在 `Constants.smali` 中**定义、但全程未被调用**（见「六、DVR 两种 WiFi 模式」）。即 DVR 处于「AP 模式」还是「站点模式」是**固件/OEM 预置**的，APK 并不下发切换指令。因此助手**不能**靠发命令把 DVR 切到站点模式，而必须：在「模式2（站点）」下用 **UDP 广播发现**学到 DVR 的动态 IP；在「模式1（AP）」下回退到硬编码的 `192.168.42.1`。

### 拓扑 A（推荐，最贴合「模式2」）—— 车机作单一热点
1. 车机开启自身 WiFi 热点（如 `CarAssistant_XXXX`），手机加入。
2. DVR 以**站点模式**接入该车机热点（出厂/OEM 预置，无需助手干预）。助手启动后监听 UDP `10000`/`10001` 广播，自动学到 DVR 在车机子网里的动态 IP。
3. 车机助手经 amba 连上 DVR（同一网段），手机经 HTTP 连车机助手。
4. 助手把 `rtsp://<DVR动态IP>/live` 透传给手机；手机用 VLC 打开，或车机侧做 RTSP→HLS 转码后网页直接播。

### 拓扑 B（最快出 Demo）—— 手机直连 DVR 热点（「模式1」）
- DVR 自身是 AP（`192.168.42.1`），车机与手机都加入该热点。
- 助手在车机监听其 `192.168.42.x` 地址；二维码编码该地址。
- 缺点：手机此时无外网；但行车记录仪类场景本就如此，可接受作为 v1。

---

## 三、已验证（早期参考原型 `dashcam-assistant/`）

`MOCK=1 node src/bridge-server.js` 进程内拉起模拟 DVR，已用脚本端到端验证：
- `/api/status` 连接正常；`/api/list` 返回 2 条媒体；`/api/thumb` 返回 256B；`/api/file` 精确返回 2048B；`/api/delete` 返回 `msg_id=1281`；`/api/live` 返回 RTSP 地址；`/qr` 返回二维码 PNG。
- 手机网页 `public/index.html`：普通/紧急/照片分页、缩略图、预览/下载/删除按钮。
- 车机管理页 `public/admin.html`：展示二维码 + 连接状态 + 重连按钮。

---

## 四、风险与下一步

| 风险 | 说明 | 缓解 |
|---|---|---|
| 二进制收尾方式 | 真机 `GET_FILE` 字节边界可能与模拟不同 | 真机抓包一次确认；原型已兼容 `size` 与「读到关闭」 |
| 媒体列表 JSON 形态 | 真机 `param` 字段名/结构可能微调 | 以 `MediaItem` 字段名为基准做容错映射（已做 `normalizeMedia`） |
| RTSP 网页播放 | 浏览器原生不支持 RTSP | 车机侧加 RTSP→HLS/WebRTC 转码，或手机用 VLC 打开 |
| DVR WiFi 模式 | 已逆向确认：模式2(站点,动态IP)需 UDP 发现(10000/10001)；模式1(AP)回退 192.168.42.1；切换命令未调用，为 OEM 预置 | 助手主路径=UDP 发现+回退 192.168.42.1，已落地 `src/discovery.js` |
| UDP 发现报文 | 真实设备广播字节序/CRC 需一次真机抓包坐实 | 已按 `ConnectChannel` 还原解析+CRC32(小端)，单测 `src/test_discovery.js` 通过；真机抓包后微调即可 |
| Android 化 | 原型是 Node，最终要落地为车机 App | 逻辑平移到 Kotlin：`Socket`+`JSONObject` 实现 amba；`ServerSocket`/`NanoHTTPD` 做桥；`WebView` 承载手机 UI；`ZXing` 生成二维码 |

**最小可行路径建议**：先用拓扑 B + 本原型（Node 在车机跑，或先验证协议）→ 抓真机包校准 `GET_FILE` 边界与列表结构 → 移植为 Android App（含 RTSP→HLS 转码）→ 上线。

---

## 五、DVR 两种 WiFi 模式（已逆向确认）

你之前的判断成立——DVR 确实有「AP 模式 / 站点模式」两种工作形态，且「连上行车记录仪 APP 后默认进入站点模式（模式2）」与反编译代码一致。证据如下：

### 模式1：DVR 作为 AP（热点），IP 固定 `192.168.42.1`
- `CameraLoader.smali:39` 把 `http://192.168.42.1` 写死为默认地址，`setLivePath("rtsp://192.168.42.1/live")`。
- 这是「DVR 自己开热点、车机/手机直连」时的回退地址。助手在模式1下直接用此 IP 连 amba。

### 模式2：DVR 作为站点接入车机热点，IP 动态分配（助手需主动发现）
- 车机 APP 在 `CameraBinder.smali:799-824` 创建**两个 UDP 只收监听**：端口 `0x2710`(10000)→`ConnectChannel`、`0x2711`(10001)→`Connect1Channel`，随后 `mIHUAdapter.connect(true)`。
- `UDPSocketChannel.smali` 用 `DatagramSocket.bind(port)` + `setSoTimeout(2000ms)` 监听，`read()` 仅 `receive()`（写是空操作）——即**只收广播、不主动发包**，典型的被动发现。
- `ConnectChannel.phaseInfo` 解析广播报文（头 `1f ff`、module `00`、cmd `0a`），提取 `dvrId / apMac / dvrMac / dvrIP / port`，并把 `dvrIP` 写入 `DvrInfo.ip10000`；随后 `CameraLoader.updateAmbaCamera(camera, ip10000)` 把 HTTP 地址改为 `http://<dvrIP>`、RTSP 改为 `rtsp://<dvrIP>/live`。
- 因此**模式2 下 DVR 的 IP 是车机子网动态分配的，车机靠 UDP 广播学到它**。助手必须复刻同一机制（`src/discovery.js` 已还原报文解析，含 CRC32 校验）。

### 车机与 DVR 是「预绑定」的一对一关系
- `ConnectChannel.checkAPMacSame()` / `checkDVRID()` 读取 `IHUAdapterSettings`（`ap_mac`、`dvr_id`）SharedPreferences，校验发现到的 DVR 是否与本车预配对。意味着每台车机只认一台 DVR。

### WiFi 模式切换命令「已定义但未使用」
- 全 `smali/` 目录 grep：`AMBA_SET_WIFI_SETTING(0x602)`、`GET_WIFI_SETTING(0x603)`、`WIFI_START(0x605)`、`WIFI_STOP(0x604)`、`WIFI_RESTART(0x601)`、`GET_WIFI_STATUS(0x606)`、`QUERY_SESSION_HOLDER(0x701)` 仅出现在 `Constants.smali` 的常量定义里，**没有任何调用点**。
- 结论：DVR 当前处于哪种 WiFi 模式是**固件/OEM 出厂预置**的，APK 不参与切换。你观察到的「初始化并连上 APP 后默认切到模式2」，是 DVR 上电后自动以站点身份加入车机热点所致（或对应车型的出厂配置），并非 APP 下发指令。

### 对助手设计的直接影响
1. **主路径 = 模式2**：助手在车机侧监听 UDP `10000`/`10001`，解析广播学到 DVR 动态 IP，再建 amba 会话。原型已用 `src/discovery.js` + `bridge-server.js` 的 `discovery` 实现（CRC 校验见单测 `src/test_discovery.js`）。
2. **回退 = 模式1**：若 DVR 处于 AP 模式（或初次配对），直接用 `192.168.42.1`（可由 `DVR_HOST` 环境变量覆盖）。
3. **无需实现 WiFi 切换命令**：不必发 `SET_WIFI_SETTING` 等；依赖 DVR 出厂配置即可。
4. **配对校验（可选）**：若需像原 APP 那样只认本车 DVR，可读取/写入 `ap_mac`/`dvr_id` 做白名单。

## 六、文件索引（早期参考原型，未纳入本仓库）
- `dashcam-assistant/src/amba-client.js` —— 还原的 amba 协议客户端（核心，可平移 Kotlin）
- `dashcam-assistant/src/mock-dvr.js` —— 模拟 DVR，免硬件自测
- `dashcam-assistant/src/bridge-server.js` —— 车机桥接服务（HTTP API + 二维码 + UDP 发现接入）
- `dashcam-assistant/src/discovery.js` —— DVR UDP 发现（还原 `UDPSocketChannel`/`ConnectChannel`，监听 10000/10001，解析广播报文+CRC32）
- `dashcam-assistant/src/test_discovery.js` —— 发现报文解析单测（自洽通过）
- `dashcam-assistant/public/index.html` —— 手机端管理网页
- `dashcam-assistant/public/admin.html` —— 车机端二维码展示页
- `dashcam-assistant/README.md` —— 运行与部署说明
- `dashcam_work/out/` —— apktool 反编译产物（smali 源码）
