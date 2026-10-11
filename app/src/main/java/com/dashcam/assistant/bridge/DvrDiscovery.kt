package com.dashcam.assistant.bridge

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.zip.CRC32

/**
 * 还原 com.skyworthauto.sdk 的 DVR UDP 发现机制（模式2）。
 *
 * 对应 smali:
 *   - UDPSocketChannel      : 在端口 10000 / 10001 上开启「只收」UDP 监听 (SO_TIMEOUT=2000ms)
 *   - CameraBinder          : 创建两个 UDPSocketChannel -> ConnectChannel(10000) / Connect1Channel(10001)
 *   - ConnectChannel.phaseInfo : 解析二进制广播报文，提取 dvrIP / port，写入 DvrInfo
 *
 * 适用场景：DVR 作为站点接入车机热点后，会在车机子网广播该报文；
 *   车机助手监听即可学到 DVR 的动态 IP，无需硬编码（模式1 AP 回退见 FALLBACK_HOST）。
 *
 * 报文布局（已按 phaseInfo + checkPackageCrc32 还原）:
 *   [0]    0x1f  (HEADER_0)
 *   [1]    0xff  (HEADER_1)
 *   [2]    0x00  (MODULE_ID)
 *   [3]    0x0a  (COMMAND_ID)
 *   [4]    dataLen  = 6 + len(apMac) + len(dvrMac) + len(dvrIP)
 *   [5]    dvrId      (1 字节)
 *   [6]    len(apMac) ; [7 .. 7+len0) apMac
 *   [7+len0]    len(dvrMac) ; [..+1] dvrMac
 *   [8+len0+len1]    len(dvrIP) ; [..+1] dvrIP   <-- 助手需要的 DVR 地址
 *   [9+len0+len1+len2]   port (2 字节, 大端)      <-- 通常 7878
 *   [11+Σlen]    CRC32 (java.util.zip, 4 字节, 小端存储)，覆盖其前的全部数据字节
 */
object DvrDiscovery {
    private val PORTS = intArrayOf(10000, 10001)
    const val FALLBACK_HOST = "192.168.42.1"
    private const val HEADER_0 = 0x1f
    private const val HEADER_1 = 0xff
    private const val MODULE_ID = 0x00
    private const val COMMAND_ID = 0x0a

    @Volatile private var running = false
    @Volatile private var generation = 0
    private val lifecycle = Any()
    private val sockets = mutableMapOf<Int, DatagramSocket>()
    private val threads = mutableListOf<Thread>()

    data class DvrInfo(
        val dvrId: Int,
        val apMac: String,
        val dvrMac: String,
        val dvrIP: String,
        val port: Int,
        val crcOk: Boolean,
        val from: String
    )

    fun start(onFound: (DvrInfo) -> Unit, onError: (Throwable) -> Unit = {}) {
        synchronized(lifecycle) {
            if (running) return
            running = true
            val runGeneration = ++generation
            for (port in PORTS) {
                val socket = try {
                    DatagramSocket(null).apply {
                        reuseAddress = false
                        bind(InetSocketAddress(port))
                        soTimeout = 2000
                    }
                } catch (e: Exception) {
                    onError(e)
                    continue
                }
                sockets[port] = socket
                val thread = Thread(
                    { listen(port, socket, runGeneration, onFound, onError) },
                    "DvrDiscovery-$port"
                ).apply { isDaemon = true }
                threads.add(thread)
                thread.start()
            }
            if (sockets.isEmpty()) running = false
        }
    }

    fun stop() {
        synchronized(lifecycle) {
            running = false
            generation++
            sockets.values.forEach { it.close() }
            sockets.clear()
            threads.forEach { it.interrupt() }
            threads.clear()
        }
    }

    private fun listen(
        port: Int,
        socket: DatagramSocket,
        runGeneration: Int,
        onFound: (DvrInfo) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            val buffer = ByteArray(512)
            while (running && generation == runGeneration && !Thread.currentThread().isInterrupted) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val info = parseDiscoveryPacket(packet.data, packet.length, packet.address.hostAddress)
                    if (info != null && info.crcOk && running && generation == runGeneration) {
                        onFound(info)
                    }
                } catch (_: SocketTimeoutException) {
                    // A timeout allows the worker to observe stop and generation changes.
                } catch (e: SocketException) {
                    if (running && generation == runGeneration) onError(e)
                    break
                } catch (_: Exception) {
                    // Ignore malformed datagrams.
                }
            }
        } finally {
            socket.close()
            synchronized(lifecycle) {
                if (sockets[port] === socket) sockets.remove(port)
                threads.remove(Thread.currentThread())
                if (generation == runGeneration && sockets.isEmpty()) running = false
            }
        }
    }

    fun parseDiscoveryPacket(raw: ByteArray, length: Int, from: String?): DvrInfo? {
        val buf = if (length == raw.size) raw else raw.copyOf(length)
        if (buf.size < 9) return null
        if (buf[0].toInt() and 0xff != HEADER_0) return null
        if (buf[1].toInt() and 0xff != HEADER_1) return null
        if (buf[2].toInt() and 0xff != MODULE_ID) return null
        if (buf[3].toInt() and 0xff != COMMAND_ID) return null

        val len0 = buf[6].toInt() and 0xff          // apMac 长度
        val len1 = buf[7 + len0].toInt() and 0xff   // dvrMac 长度
        val len2 = buf[8 + len0 + len1].toInt() and 0xff // dvrIP 长度
        val dataLen = 11 + len0 + len1 + len2
        if (buf.size < dataLen + 4) return null

        // CRC32 覆盖 [0 .. dataLen)，与 java.util.zip.CRC32 一致（poly 0xEDB88320）
        val crc = CRC32()
        crc.update(buf, 0, dataLen)
        val computed = crc.value and 0xffffffffL
        // 真实 DVR 报文：CRC 以「小端」存储
        val stored = (buf[dataLen].toLong() and 0xff) or
                ((buf[dataLen + 1].toLong() and 0xff) shl 8) or
                ((buf[dataLen + 2].toLong() and 0xff) shl 16) or
                ((buf[dataLen + 3].toLong() and 0xff) shl 24)
        val crcOk = computed == (stored and 0xffffffffL)

        val dvrId = buf[5].toInt() and 0xff
        val apMac = String(buf, 7, len0, Charsets.UTF_8)
        val dvrMac = String(buf, 7 + len0 + 1, len1, Charsets.UTF_8)
        val dvrIP = String(buf, 8 + len0 + len1 + 1, len2, Charsets.UTF_8)
        val port = (buf[9 + len0 + len1 + len2].toInt() and 0xff) or
                ((buf[10 + len0 + len1 + len2].toInt() and 0xff) shl 8)

        return DvrInfo(dvrId, apMac, dvrMac, dvrIP, if (port == 0) 7878 else port, crcOk, from ?: "")
    }
}
