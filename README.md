# 奇瑞原厂行车记录仪助手

车机/手机端 Android 应用，用于奇瑞原厂行车记录仪（DVR）发现、车内媒体浏览，以及通过手机浏览器访问媒体。

## 产品形态

| 能力 | 车机端（Android/Compose） | 手机浏览器 |
| --- | --- | --- |
| DVR 状态与配对 | 连接状态、重新扫描、二维码配对 | 连接状态 |
| 普通/紧急录像 | 分页网格、缩略图、Media3 播放、保存到 Android 媒体库 | 分页网格、视频播放、下载 |
| 照片 | 缩略图网格、原图预览、保存到 Android 媒体库 | 缩略图网格、图片预览、下载 |
| 实时预览 | Media3 RTSP/TCP 播放器 | 不提供 |
| 文件名显示 | 友好名 / 原始名 / 两者；保存在应用偏好中 | 友好名 / 原始名 / 两者；保存在浏览器本地存储 |
| 诊断 | 应用日志 | 连点页面标题五次查看日志 |

手机页面刻意不提供实时预览页或直播端点。车机实时预览仍是独立的 Media3 RTSP/TCP 流程，手机桥接不把 RTSP 转码为 HLS。

## 架构

```text
MainActivity / MediaBrowser (Compose)
       ├── BridgeState ↔ BridgeService / DvrDiscovery
       │                  └── HttpBackend → DVR HTTP:80 媒体索引/文件
       ├── Media3 Progressive → DVR HTTP 媒体播放
       ├── Media3 RTSP/TCP ───→ DVR 直播流（仅车机端）
       └── HttpServer :8080 ─→ 已配对手机页面/API → DVR 媒体代理
```

手机桥接提供状态、媒体列表、播放/下载、缩略图与诊断能力。它使用一个短时效的配对凭据和一个仅限 HTTP 的会话 Cookie。本地网络流量未加密，请在使用可信网络。

已配置的媒体目录为 `/SD0/NOR/M_video/`（普通，`NOR_*`）、`/SD0/EVT/M_video/`（紧急，`EVT_*`）与 `/SD0/PHO/M_photo/`（照片，`PHO_*`）；分类依据文件名前缀与路径。DVR 发现监听 UDP 10000/10001，并探测本地子网中的 HTTP 80 与 RTSP 554。观测到的直播地址为 `rtsp://<DVR-IP>/live`；未推断存在任何鉴权或其他设备协议。目录格式、HTTP Range 行为、RTSP 鉴权与编解码需针对各目标机型/固件逐一确认。

