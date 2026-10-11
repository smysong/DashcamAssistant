# 行车记录仪助手

Android app for DVR discovery, in-car media viewing, and phone-browser media access.

## Product surfaces

| Capability | Car display (Android/Compose) | Phone browser |
| --- | --- | --- |
| DVR status and pairing | Connection status, rescan, QR pairing | Connection status |
| Normal/event recordings | Paged grid, thumbnails, Media3 playback, save to Android media library | Paged grid, video playback, download |
| Photos | Thumbnail grid, full-size preview, save to Android media library | Thumbnail grid, image preview, download |
| Live view | Media3 RTSP/TCP player | Not provided |
| Filename display | Friendly, original, or both; saved in app preferences | Friendly, original, or both; saved in browser local storage |
| Diagnostics | App logging | Tap the page title five times for logs |

The phone page intentionally has no live-view tab or streaming endpoint. Car live view remains an independent Media3 RTSP/TCP flow. The phone bridge does not transcode RTSP to HLS.

## Architecture

```text
MainActivity / MediaBrowser (Compose)
       ├── BridgeState ↔ BridgeService / DvrDiscovery
       │                  └── HttpBackend → DVR HTTP:80 media index/files
       ├── Media3 Progressive → DVR HTTP media playback
       ├── Media3 RTSP/TCP ───→ DVR live stream (car display only)
       └── HttpServer :8080 ─→ paired phone page/API → DVR media proxy
```

The phone bridge serves status, media listing, playback/download, thumbnails, and diagnostics. It uses a short-lived pairing credential and an HTTP-only session cookie. Traffic on the local network is unencrypted; use a trusted network.

The configured media directories are `/SD0/NOR/M_video/` (normal, `NOR_*`), `/SD0/EVT/M_video/` (event/emergency, `EVT_*`), and `/SD0/PHO/M_photo/` (photos, `PHO_*`); classification uses the filename prefix and the path. DVR discovery listens on UDP 10000/10001 and also probes the local subnet for HTTP 80 and RTSP 554. The observed live URL is `rtsp://<DVR-IP>/live`; no authentication or other device protocol is inferred. Confirm directory formats, HTTP Range behavior, RTSP authentication and codecs against each target model/firmware.

## Build and verification

Requires Android SDK 35, JDK 17, and Gradle 8.9. A Gradle wrapper is **not** checked in (builds use a local/cached Gradle 8.9 distribution).

```powershell
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Release signing is optional and reads a locally supplied, untracked keystore configured via `local.properties` (`DASHCAM_KEYSTORE` plus the related store/key credentials); the keystore file and `local.properties` are ignored by Git. Keep a secure backup of the keystore and its credentials: future updates must use this same key, and a newly generated replacement will not be accepted as an update to APKs signed with this key. Neither the keystore nor credentials belong in source control or release artifacts.

JVM tests cover bridge access, fake DVR media listing/proxy behavior, media transfer, thumbnail cache limits, and filename display formatting. Unit/fake-DVR tests do not prove compatibility with a physical DVR; car RTSP and target-device codec support require hardware validation.
