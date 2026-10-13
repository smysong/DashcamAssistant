package com.dashcam.assistant.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.dashcam.assistant.AppLog
import com.dashcam.assistant.BridgeState
import com.dashcam.assistant.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 前台服务（dataSync 类型），承载 UDP 发现 + 嵌入式 HTTP 桥。
 * 向 [BridgeState] 写入连接状态，供车机 Compose UI 观察。
 *
 * 2026-09-21 重构：移除 amba 模式，统一 HTTP 模式（不占用 DVR 控制会话，与原厂 APP 永久共存）。
 * 后端恒为 HttpBackend；DVR 被发现即就绪，无按需会话/空闲释放逻辑。
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 扫描发现的 DVR 主机（车机作 AP 时为其 station；车机作 station 时为其网关）。 */
    private var discoveredHost: String? = null
    private var bridgeUrl: String = ""
    /** 当前激活的 DVR 后端（仅 HTTP）。 */
    private var backend: DvrBackend? = null
    private lateinit var http: HttpServer

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("初始化中…"))

        backend = HttpBackend(discoveredHost ?: "", listCacheDir = this.cacheDir)
        BridgeState.backendState.value = backend
        val reconnect = { scope.launch { forceRescan() }; Unit }
        http = HttpServer(this@BridgeService).also {
            it.backend = backend
            it.discoveredHost = discoveredHost
            it.onReconnect = reconnect
        }
        BridgeState.httpServer = http
        BridgeState.onReconnect = reconnect

        scope.launch { runBridge() }
        AppLog.i("Bridge", "桥接服务已启动：监听 UDP 发现(10000/10001) + HTTP :8080")
    }

    private suspend fun runBridge() {
        bridgeUrl = "http://${resolveBridgeIp(this)}:8080/"
        http.start()
        publishState(false)
        // 监听 UDP 广播，发现后切换到 DVR 动态 IP
        DvrDiscovery.start(
            onFound = { info ->
                if (discoveredHost == info.dvrIP) return@start
                AppLog.i("发现", "UDP 广播发现 DVR：${info.dvrIP}:${info.port} (crcOk=${info.crcOk})")
                markDiscovered(info.dvrIP, info.port)
            },
            onError = { e -> AppLog.w("发现", "UDP 监听异常：${e.message}") }
        )
        // 动态扫描：只扫车机自身网段，按 80/554 开放判定（HTTP 模式仅需这两端口）为有效 DVR。
        scope.launch { scanForDvr() }
        // 周期刷新桥接地址：热点可能晚于 App 启动就绪（车机兼作 AP 时尤其相关）
        scope.launch { refreshBridgeUrlLoop() }
    }

    /** 每 5s 重解析车机可达地址，变化则更新 bridgeUrl 并通知 UI（二维码跟随刷新）。 */
    private suspend fun refreshBridgeUrlLoop() {
        while (true) {
            delay(5000)
            val ip = resolveBridgeIp(this@BridgeService)
            val url = "http://$ip:8080/"
            if (url != bridgeUrl) {
                bridgeUrl = url
                AppLog.i("Bridge", "桥接地址更新：$url")
                publishState(BridgeState.status.value.connected, BridgeState.status.value.error)
            }
        }
    }

    /** 强制重新扫描（手机请求 / 用户刷新时调用）。 */
    private suspend fun forceRescan() {
        discoveredHost = null
        http.discoveredHost = null
        rebuildBackend(null)
        AppLog.i("Bridge", "用户触发重新扫描")
        publishState(false, "")
        publishScan("已触发重新扫描…")
    }

    /** 仅记录发现的 DVR 地址并重建后端；HTTP 模式无需占会话，发现即就绪。 */
    private fun markDiscovered(host: String, port: Int = 80) {
        discoveredHost = host
        http.discoveredHost = host
        rebuildBackend(host)
        publishState(connected = true)
        AppLog.i("Bridge", "已发现并连接 DVR：$host（端口 ${DVR_PROBE_PORTS.joinToString("/")} 开放）")
        publishScan("已发现 DVR：$host")
    }

    private fun isBackendReady(): Boolean = discoveredHost != null

    /** 按当前方案重建后端（仅 HTTP）。 */
    private fun rebuildBackend(host: String?) {
        backend = HttpBackend(host ?: "", listCacheDir = this.cacheDir)
        http.backend = backend
        // 暴露给车机 UI 直接管理媒体（原生 Compose 浏览器调用）
        BridgeState.backendState.value = backend
    }

    /**
     * APP 启动后的动态 DVR 发现：只扫描车机自身所在的网段（车机作 AP 时 DVR 是其 station；
     * 车机作 station 连 DVR 热点时 DVR 即其网关），不做 192.168.0~255 盲扫。
     * 对每个候选主机并发探测 80/554，端口开放即判定为有效 DVR（HTTP 模式仅依赖这两端口），
     * 之后自动保持最新地址。未连上则每 [SCAN_INTERVAL_MS] 重试。
     */
    private suspend fun scanForDvr() {
        var loggedNotFound = false
        while (scope.isActive) {
            val prefixes = localSubnetPrefixes()
            val candidates = prefixes.flatMap { p -> (1..254).map { i -> "$p.$i" } }
                .filter { it != "127.0.0.1" && !it.startsWith("0.") }
                .distinct()
            val ports = DVR_PROBE_PORTS.joinToString("/")
            val found = concurrentProbe(candidates, DVR_PROBE_PORTS, 400)
            if (found != null) {
                if (found != discoveredHost) {
                    AppLog.i("扫描", "扫描车机网段命中 DVR：$found（网段 ${prefixes.joinToString("、")}，端口 $ports 开放）")
                    markDiscovered(found)
                }
                loggedNotFound = false
            } else {
                // 仅在"未发现"连续段首次记录，避免每轮(8s)刷屏；扫描活动本身仍在进行
                if (!loggedNotFound) {
                    AppLog.i("扫描", "扫描车机网段未发现 DVR（网段 ${prefixes.joinToString("、")}，候选 ${candidates.size} 台，探测端口 $ports），继续重试")
                    loggedNotFound = true
                }
            }
            delay(SCAN_INTERVAL_MS)
        }
    }

    /**
     * 扫描进度写入内部状态（仅供开发者模式/调试参考），不再作为车机屏或通知的可见提示。
     * 2026-09-21：按需求移除"扫描车机网段"的屏幕文字提示。
     */
    private fun publishScan(text: String) {
        BridgeState.set(BridgeState.status.value.copy(scanInfo = text))
    }

    /** 枚举车机所有本地非回环 IPv4 网卡所在 /24 前缀（如 "192.168.43"、"192.168.42"）。 */
    private fun localSubnetPrefixes(): List<String> {
        val out = mutableListOf<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in ifaces) {
                if (ni.isLoopback || !ni.isUp) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("lo") || name.startsWith("dummy") ||
                    name.startsWith("v4-") || name.startsWith("v6-") || name.startsWith("p2p") ||
                    name.startsWith("bt-pan")
                ) continue
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress?.substringBefore('%') ?: continue
                    if (ip.startsWith("127.") || ip == "0.0.0.0") continue
                    val m = Regex("^(\\d+\\.\\d+\\.\\d+)\\.").find(ip)
                    if (m != null) out.add(m.groupValues[1])
                }
            }
        } catch (_: Exception) {
        }
        return out.distinct()
    }

    /**
     * 并发 TCP 探测候选主机列表的端口，统计开放端口数；开放数 >= [DVR_MIN_OPEN_PORTS]
     * 才判定为有效 DVR 并返回该主机（不建立会话）。避免单端口误判普通设备。
     */
    private fun concurrentProbe(hosts: List<String>, ports: List<Int>, timeoutMs: Int): String? {
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(minOf(64, maxOf(8, hosts.size / 4)))
        val latch = CountDownLatch(hosts.size)
        try {
            for (h in hosts) {
                pool.submit {
                    try {
                        var open = 0
                        for (port in ports) {
                            if (probePort(h, port, timeoutMs)) {
                                open++
                                if (open >= DVR_MIN_OPEN_PORTS) { found.compareAndSet(null, h); break }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
            while (!latch.await(200, TimeUnit.MILLISECONDS)) {
                if (found.get() != null) break
            }
        } finally {
            pool.shutdown()
        }
        return found.get()
    }

    /** 快速 TCP 连通性探测（带超时）。 */
    private fun probePort(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun publishState(connected: Boolean, error: String = "") {
        val prev = BridgeState.status.value
        val changed = prev.connected != connected
        BridgeState.set(
            BridgeState.status.value.copy(
                connected = connected,
                dvrHost = discoveredHost ?: "",
                discovered = discoveredHost != null,
                bridgeUrl = http.pairingUrl(bridgeUrl),
                error = error,
                scanInfo = if (connected) "" else prev.scanInfo
            )
        )
        if (changed) {
            if (connected) AppLog.i("连接", "连接状态：已连接 DVR (${discoveredHost ?: ""})")
            else AppLog.i("连接", "连接状态：已断开 DVR")
        } else if (error.isNotEmpty()) {
            AppLog.w("连接", "连接错误：$error")
        }
        val host = discoveredHost ?: ""
        updateNotification(if (connected) "已连接 DVR ($host)" else "未连接 DVR")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "行车记录仪桥接", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("行车记录仪助手")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notify)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    override fun onDestroy() {
        DvrDiscovery.stop()
        http.stop()
        scope.cancel()
        http.backend = null
        http.discoveredHost = null
        if (BridgeState.httpServer === http) BridgeState.httpServer = null
        BridgeState.onReconnect = null
        BridgeState.backendState.value = null
        BridgeState.set(BridgeState.status.value.copy(connected = false, discovered = false, dvrHost = ""))
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "dashcam_bridge"
        private const val NOTIF_ID = 1001
        /** 动态扫描两轮之间间隔：DVR 可能晚于 App 启动上线，周期性重试直到连上。 */
        private const val SCAN_INTERVAL_MS = 8_000L
        /** 判定有效 DVR 的探测端口：80(HTTP 管理)、554(RTSP 流)。HTTP 模式仅依赖这两端口。 */
        private val DVR_PROBE_PORTS = listOf(80, 554)
        /** 上述端口中至少开放的数量才判定为有效 DVR（过滤单端口误判）。 */
        private const val DVR_MIN_OPEN_PORTS = 2
    }
}
