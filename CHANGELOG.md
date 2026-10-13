# 更新日志 (Changelog)

本项目所有重要变更均记录于此。格式参考 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.0.3] - 2026-10-13（测试构建：构建号降级至 3，用于联调应用中心「检查更新 / 升级」）

> 本构建在功能上较 1.0.4 仅新增「应用中心检查更新」能力；版本号与构建号刻意降级，便于在真机安装 1.0.3 后，由应用中心服务器以 1.0.4（构建号 4）作为「最新版」触发升级流程验证。

### 新增
- **应用中心检查更新 / 升级（新增 `UpdateCenter.kt`，零新增依赖：HttpURLConnection + org.json + 协程，规避构建机拉包）**：
  - 设置页新增「应用更新」分区：展示当前版本、新增「检查更新」按钮。
  - 调用 `GET /api/public/check-update?package=<包名>&versionCode=<当前>`（服务器 `https://app.songmy.top`），按统一信封 `{"code":0,"msg":"ok","data":{has_update, latest{...}}}` 解析；`has_update` 且 `latest.version_code > 当前` 才提示升级。
  - 升级弹窗展示版本号、更新内容（changelog）、下载进度；支持**强制更新**（不可取消、隐藏「以后再说」）。
  - 下载走 **HTTP Range 断点续传**（206 续传、进度 0–100%）；完成后**校验 SHA-256**，不一致则删除重试一次，仍不符则报错并中止。
  - 校验通过经 **FileProvider**（`${applicationId}.fileprovider`，暴露 `cacheDir/updates`）调起系统安装器安装；已申请 `REQUEST_INSTALL_PACKAGES` 权限，`usesCleartextTraffic=true` 满足 HTTP 明文。
  - **启动时自动检查更新（设置页「应用更新」新增开关，持久化）**：开启后 APP 启动即在后台静默调用检查接口；发现新版本自动弹出升级提示。
  - **会话级抑制**：后台自动提示后若用户点击「以后再说」，则**本次 APP 运行期间**不再自动检查与提示（`UpdateManager.suppressedThisSession`，进程级、重启失效，不持久化）；返回键关闭不触发抑制。
  - **提示圆点常驻**：设置图标角标与版本区红点由独立持久状态 `updateAvailable` 驱动；关闭升级弹窗仅清 `pendingUpdate`（弹窗态），不清 `updateAvailable`，故**圆点在未安装完成 / 未重新检查到「已是最新」前持续显示**。手动「检查更新」返回「已是最新版本」时清除。
  - **设置页升级入口增强**：检测到新版本时，在「当前版本」下方显示「最新版本：vX.X.X（构建号 N）」（高亮），并将「检查更新」按钮切换为「立即更新」。
  - **「立即更新」直达下载**：点击「立即更新」**直接触发**下载 + SHA-256 校验 + 安装流程（按钮实时显示「下载中… N%」），**不再二次弹出「发现新版本」确认弹窗**（该确认弹窗仅用于后台自动检查发现的场景）。无新版本时仍显示「检查更新」。
  - **安装兜底（无系统默认安装器时）**：优先 `ACTION_VIEW` 走系统安装器；当该 Intent 无活动可解析时，改用 **`PackageInstaller` 会话 API** 直接提交安装升级，避免依赖外部「包安装器」。
  - **下载增强（断点续传 / 后台下载 / 缓存复用）**：
    - **断点续传**：`UpdateCenter.downloadWithResume` 基于已存在的部分文件发起 `Range: bytes=start-` 续传（服务端返回 206 则追加写入），网络中断后再次触发同版本下载自动从断点继续。
    - **关闭设置窗口后台下载**：下载改由 `UpdateManager` 的进程级 `CoroutineScope`（SupervisorJob）驱动，与设置/升级弹窗的 Composable 生命周期解耦；关闭设置窗口或升级弹窗后下载仍在后台继续，弹窗可关闭而下载不中断（按钮/弹窗只读共享的 `isDownloading` / `downloadProgress` 状态）。
    - **磁盘缓存复用**：`downloadAndInstall` 新增快速路径——若 `cacheDir/updates/update_<versionCode>.apk` 已存在、大小与 `apk_size` 一致且 SHA-256 校验通过，则**直接安装、不再重复下载**；下载完成后同样以 SHA-256 校验（不一致删文件重试一次）。
- `AndroidManifest.xml` 注册 `FileProvider` 并新增 `res/xml/file_paths.xml`；`build.gradle.kts` 增加 `outputFileName` 中版本号占位（沿用既有规则）。

## [1.0.4] - 2026-10-13

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
- **网页端（手机扫码）逻辑与原生端同步**：`MobileThumbnailService` 接入反馈①的 DVR 端缩略图直链（命中即跳过客户端取帧/降采样，失败回退）；`HttpServer /api/list` 接入列表磁盘缓存（`getCachedListPage` 先读盘秒回，无缓存再 `listMediaPaged`），与车机两段式加载对齐。此前网页端漏接这两处优化，现已补齐。
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
- 版本号升 **1.0.4**，构建号（versionCode）同步为 **4**。
- 本机模拟 DVR 调试工具 `tools/mock_dvr.py` 增强：`--count` 合成大目录（验证 >100 文件场景）、DVR 缩略图直链端点（`/SD0/<CAT>/Thumb/<stem>T.JPG`，纯 Python 生成 PNG，按分类配色）、请求日志与 `--slow-list/--slow-thumb/--latency/--thumb-fail-rate/--no-thumbs` 延迟与回退开关，便于验证 A+B+C 与反馈①。

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

[1.0.4]: https://github.com/smysong/DashcamAssistant/releases/tag/v1.0.4
[1.0.2]: https://github.com/smysong/DashcamAssistant/releases/tag/v1.0.2
[1.0.1]: https://github.com/smysong/DashcamAssistant/releases/tag/v1.0.1
