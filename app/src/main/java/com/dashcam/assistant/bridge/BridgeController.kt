package com.dashcam.assistant.bridge

/**
 * 嵌入式 HTTP 服务（车机桥）回调到业务逻辑的接口。
 * HTTP 处理线程调用这些方法，内部用 runBlocking 桥接 amba 的挂起调用。
 */
interface BridgeController {
    fun statusJson(): String
    fun listJson(type: String, offset: Int = 0, limit: Int = 10): String
    fun getThumb(path: String): ByteArray?
    fun getFile(path: String, size: Int): ByteArray?
    fun deleteFile(path: String): Boolean
    fun logJson(): String
    fun clearLog()
    fun reconnect()
}
