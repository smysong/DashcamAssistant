# 行车记录仪助手

车机端 Android 应用，用于行车记录仪（DVR）发现、车内媒体浏览，以及通过手机浏览器访问媒体。

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

## 构建与验证

需要 Android SDK 35、JDK 17 与 Gradle 8.9。仓库**未**内置 Gradle wrapper（构建使用本地/缓存的 Gradle 8.9 发行版）。

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Release 签名是可选的，读取由 `local.properties` 配置的本地、未跟踪 keystore（`DASHCAM_KEYSTORE` 及相关的 store/key 凭据）；keystore 文件与 `local.properties` 均被 Git 忽略。请妥善备份 keystore 及其凭据：后续更新必须使用同一把密钥，重新生成的新密钥将无法作为用旧密钥签名的 APK 的更新被安装。keystore 与凭据均不得进入源码管理或发布产物。

JVM 测试覆盖桥接访问、伪造 DVR 的媒体列表/代理行为、媒体传输、缩略图缓存上限，以及文件名显示格式化。单元/伪造 DVR 测试不能证明与真实 DVR 的兼容性；车机 RTSP 与目标设备编解码支持需要硬件实测验证。
