package com.dashcam.assistant.bridge

import android.content.Context
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 解析车机在 Wi-Fi 网络中的"手机可达"局域网 IP，用于生成手机扫码的连接地址。
 *
 * 三种拓扑（手机永远连车机热点，故二维码必须显示车机 AP 侧地址）：
 * - 模式1 纯（车机作 station 连 DVR 热点，手机也连 DVR 热点）：
 *     车机 station 地址 192.168.42.x，手机同子网，用 connectionInfo 地址即可。
 * - 模式2（车机作 AP，DVR/手机连车机热点）：
 *     connectionInfo.ipAddress == 0（无 station），需枚举接口取热点网关（如 192.168.43.1）。
 * - 模式3（车机兼作 station 连 DVR 热点 + AP 供手机）：车机同时有两个 IP
 *     station 侧 192.168.42.x（连 DVR）与 AP 侧 192.168.43.1（供手机）。
 *     手机连的是车机热点，故必须显示 AP 侧 192.168.43.1，而非 station 侧地址。
 *
 * 规则：优先返回"车机热点(AP)接口地址"；无热点时才退回 station 地址；最后兜底枚举私有 IPv4。
 */
fun resolveBridgeIp(context: Context): String {
    // 1) 车机热点(AP)接口地址：手机连车机热点，必须经此地址访问桥（模式2 / 模式3）
    findHotspotIp()?.let { return it }
    // 2) 车机作 station 连 DVR 热点（模式1 纯）：用 connectionInfo 地址
    try {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        if (ip != 0) {
            val s = String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
            if (s != "0.0.0.0" && !s.startsWith("127.")) return s
        }
    } catch (_: Exception) {
    }
    // 3) 兜底：枚举任意非回环私有 IPv4
    return enumerateLanIp() ?: "127.0.0.1"
}

/**
 * 查找车机热点(SoftAP)接口地址：手机连车机热点时只能经此地址到达。
 * 判定：接口名含 softap/ap0/ap*（典型 AP 接口），或地址落在 Android 热点常见段 192.168.4[3-7].x。
 * 命中 AP 接口名则直接采用；否则返回热点段候选。
 */
private fun findHotspotIp(): String? {
    return try {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        var candidate: String? = null
        for (ni in ifaces) {
            if (ni.isLoopback || !ni.isUp) continue
            val name = ni.name.lowercase()
            val isApIface = name == "ap0" || name.contains("softap")
            for (addr in ni.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                val ip = host.substringBefore('%') // 去掉可能的 % 作用域后缀
                if (ip.startsWith("127.") || ip == "0.0.0.0") continue
                if (isApIface) return ip // AP 接口名命中，直接采用
                // Android 热点常见段 192.168.4x.x（含现代随机段），作为候选
                if (Regex("^192\\.168\\.4\\d+\\.").matches(ip)) candidate = ip
            }
        }
        candidate
    } catch (_: Exception) {
        null
    }
}

/** 枚举本地非回环 IPv4 地址，按"热点段 > 私有段"打分取最优，作通用兜底。 */
private fun enumerateLanIp(): String? {
    return try {
        val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<Pair<Int, String>>()
        for (ni in ifaces) {
            if (ni.isLoopback || !ni.isUp) continue
            val name = ni.name.lowercase()
            // 跳过移动数据、虚拟/回环、Wi-Fi Direct 等不会承载热点桥接的接口
            if (name.startsWith("rmnet") || name.startsWith("lo") || name.startsWith("dummy") ||
                name.startsWith("v4-") || name.startsWith("v6-") || name.startsWith("p2p") ||
                name.startsWith("bt-pan")
            ) continue
            for (addr in ni.inetAddresses) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val host = addr.hostAddress ?: continue
                val ip = host.substringBefore('%') // 去掉可能的 % 作用域后缀
                if (ip.startsWith("127.") || ip == "0.0.0.0") continue
                candidates.add(score(ip) to ip)
            }
        }
        if (candidates.isEmpty()) return null
        candidates.minByOrNull { it.first }?.second
    } catch (_: Exception) {
        null
    }
}

/**
 * 给候选地址打分（越小越优先）：
 *   0 = Android 热点常见段 192.168.4x.x（含 192.168.43.1 等网关）
 *   1 = 其它 192.168.x.x
 *   2 = 172.16~31.x.x
 *   3 = 10.x.x.x
 *   4 = 其它非公网/兜底
 */
private fun score(ip: String): Int = when {
    Regex("^192\\.168\\.4\\d+\\.").matches(ip) -> 0
    ip.startsWith("192.168.") -> 1
    ip.startsWith("172.") -> {
        val seg = ip.substringAfter("172.").substringBefore('.').toIntOrNull() ?: 0
        if (seg in 16..31) 2 else 4
    }
    ip.startsWith("10.") -> 3
    else -> 4
}
