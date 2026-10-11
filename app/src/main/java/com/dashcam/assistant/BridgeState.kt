package com.dashcam.assistant

import com.dashcam.assistant.bridge.DvrBackend
import com.dashcam.assistant.bridge.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 车机屏（MainActivity）与后台桥接服务（BridgeService）之间的共享状态。
 * 服务写状态，Compose UI 通过 collectAsState 观察；UI 的重连请求经由 onReconnect 回调到服务。
 */
data class DvrStatusUi(
    val connected: Boolean = false,
    val dvrHost: String = "192.168.42.1",
    val discovered: Boolean = false,
    val bridgeUrl: String = "",
    val devInfo: String = "",
    val error: String = "",
    val scanInfo: String = ""
)

object BridgeState {
    private val _status = MutableStateFlow(DvrStatusUi())
    val status: StateFlow<DvrStatusUi> = _status

    /** 当前激活的 DVR 后端（仅 HTTP），由 BridgeService 在 rebuildBackend 时注入；车机 UI 用它直接管理媒体。 */
    val backendState = MutableStateFlow<DvrBackend?>(null)

    /** UI 的"重新扫描"请求回调到 BridgeService（HTTP 模式即强制重新发现 DVR）。 */
    var onReconnect: (() -> Unit)? = null

    /** 嵌入式 HTTP 桥实例（由 BridgeService 注入），供 Compose UI 查询桥接服务状态。 */
    var httpServer: HttpServer? = null

    fun set(s: DvrStatusUi) { _status.value = s }
}
