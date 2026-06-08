package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context

/**
 * 发送显式广播，让清单注册的 BroadcastReceiver 在主线程阻塞并触发组件超时。
 *
 * @param broadcastSender 广播发送器，测试中可替换为记录器。
 */
class BroadcastTimeoutScenario(
    private val broadcastSender: BroadcastSender,
) : AnrDemoScenario {
    constructor(context: Context) : this(
        broadcastSender = ContextBroadcastSender(context = context),
    )

    override val id: String = "broadcast_receiver_timeout"
    override val title: String = "BroadcastReceiver 超时"
    override val expectedAttribution: String = "CURRENT_MESSAGE_SLOW + BROADCAST component evidence"
    override val expectedJsonSignals: List<String> = listOf(
        "mainThread.stackFrames 包含 BroadcastTimeoutReceiver.onReceive",
        "systemAnr.anrType = BROADCAST_FOREGROUND 或 BROADCAST_BACKGROUND",
        "systemAnr.componentTimeoutMs = 10000 或 60000",
        "barrierEvidence.stuckTokens 不是主因",
    )

    /**
     * 发送 Demo 专用显式广播。真正阻塞入口在 BroadcastTimeoutReceiver.onReceive。
     */
    override fun run(): Unit {
        broadcastSender.send(action = ACTION_BROADCAST_TIMEOUT)
    }

    companion object {
        /**
         * Demo 专用 action，只用于应用内显式广播。
         */
        const val ACTION_BROADCAST_TIMEOUT: String =
            "com.valiantyan.vibeanrmonitoring.action.BROADCAST_TIMEOUT"
    }
}
