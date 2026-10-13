# 更新日志 (Changelog)

本项目所有重要变更均记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.3] - 2026-10-11

> 本版本聚焦「手机端首页与媒体库体验」「播放器控制能力」「下载与缓存健壮性」三大方向，并修复了若干影响真机使用的缺陷。

### 新增
- **设置页**：首页与媒体库右上角齿轮入口，集中「媒体库分页大小」「文件名显示」「清除缩略图缓存 / 清除回放缓存」开关。
- **媒体库列表项独立下载按钮**：与全屏页「保存」相互独立，未连接时显示禁用态。
- **播放器控制条**：新增 播放/暂停、快退 10s、快进 10s、倍速（0.75×–2.0× 循环切换）、截图（存入 `Pictures/DashcamAssistant`，JPEG 质量 90）；进度滑块改为圆点手柄。
- **分类胶囊标签**：普通录像 / 紧急录像 / 照片 / 实时预览，采用现代简约无 ripple 风格。
- **全屏贯穿式操作栏**：播放/照片顶部统一半透明栏，集中「返回」「文件名」「下载 / 进度 / 取消」。
- **下载取消**：系统下载器与 APP 内置下载双路径均支持取消（下载中点击即取消并清理半成品）。
- **本机模拟 DVR 调试工具** `tools/mock_dvr.py`（不入库）：Cherokee 风格索引 + HTTP Range + 554 探测端口，便于无真机时联调。

### 优化
- **首页布局**：手机端隐藏二维码大图改为图标入口弹窗、按屏幕档位缩放；车机端保留双栏 + 内联二维码原样不变。
- **缩略图 / 列表 / 缓冲性能**：磁盘缓存上限提升（回放缓存 512MB / 缩略图缓存 256MB）；视频缩略图改用 Range 封顶拉取（≤8MB）避免拉整段；DVR 索引扫描缓存 30s→120s。
- **缩略图优先使用 DVR 端直链**：新增 `DvrBackend.dvrThumbnailUrl/fetchThumbnail`，按命名规则（原文件 `/SD0/<CAT>/M_*/X.MP4` → 缩略图 `/SD0/<CAT>/Thumb/XT.JPG`）推导并下载 DVR 端缩略图；命中即跳过客户端取帧/降采样（更省流量、更快），下载或解码失败自动回退现有生成逻辑。
- **大目录（>100 文件）加载优化（反馈②，A+B+C 组合）**：① **列表磁盘缓存**——全量枚举结果 JSON 落盘（`cacheDir/dashcam_list_<host>_<type>.json`），冷启动直接切片即时渲染（秒出列表），后台再静默 `listMediaPaged` 刷新覆盖，仅当无缓存且后台异常才显示加载/错误态；② **缩略图优先级队列**——`PriorityBlockingQueue` + 4 个 worker 协程，最新（列表靠前）条目优先生成、较旧/离屏条目排队，避免一次性突发请求压垮 DVR 并发，任务取消经 `CompletableDeferred` 传播不泄漏；③ **分类根目录并行扫描**——`collectAll` 对 `/SD0/NOR`、`/SD0/EVT`、`/SD0/PHO` 等根目录 `async` 并发采集（`Semaphore(4)` 限并发）。三项结合后，百级以上媒体量从「整屏转圈」变为「列表秒开 + 缩略图按序填充」。
- **视频缓冲参数上调**，起播更快更稳；HTTP 读超时放宽至 60s。

### 修复
- **启动闪退**：首页等高对齐误用 `IntrinsicSize.Min`，触发 `BoxWithConstraints`（SubcomposeLayout）固有测量崩溃，已改为确定高度 + `fillMaxHeight`。
- **按钮配色割裂**：补齐主题 `secondaryContainer` 等颜色，全 App 按钮统一为青色系（消除淡紫基线色）。
- **下载文件时间**：系统下载器下载完成后将文件修改时间校正为「下载完成时刻」。
- **设置页背景色**：弹窗背景改为主区域 `background`，消除与主体色差。
- **重复设置入口**：首页/媒体库控制条两处设置图标合并为顶栏单个入口。
- **照片缩略图误报「可用空间不足」**：移除临时文件上过于保守的空间预检（原为长期磁盘缓存预留阈值），改用真实文件大小估算下载上限；仅在分区几乎写满（<2MB）时才拒绝，且真实 IO 错误转译为「图片读取失败」而非臆断「可用空间不足」。

### 构建 / 发布
- 本地服务器构建与 GitHub 云端构建统一使用 `dashcam-release.jks` 签名，证书一致。
- APK 输出文件名统一为 `DashcamAssistant-<version>-release.apk`。
- 仅 `v*` 标签触发云端签名构建与发版，`push main` 不再自动构建。

## [1.0.2] - 2026-10 月初

### 构建 / 发布
- APK 输出文件名从默认 `app-release.apk` 改为 `DashcamAssistant-<version>-release.apk`。
- CI 触发条件改为仅 `v*` 标签触发，避免每次 push main 都构建。
- 手动打 tag 流程闭环：先在 `app/build.gradle.kts` 提升版本号并 commit → `git push origin main`（不触发构建）→ GitHub 建 `vX.Y.Z` 标签触发云端签名构建。

## [1.0.1] - 2026-10 月初

### 构建 / 发布
- 首次打通 GitHub Actions 云端构建：ubuntu runner + JDK17 + Android SDK + Gradle 8.9 直接 `:app:assembleRelease`。
- 通过 GitHub Secrets（SIGNING_KEY 等）注入签名，产出已签名 APK 并自动发 Release。
- 修复若干 CI 配置问题（setup-android 失效、SDK 路径冲突、secrets 上下文误用）。

[1.0.3]: https://github.com/smysong/DashcamAssistant/releases/tag/v1.0.3
[1.0.2]: https://github.com/smysong/DashcamAssistant/releases/tag/v1.0.2
[1.0.1]: https://github.com/smysong/DashcamAssistant/releases/tag/v1.0.1
