package com.valiantyan.vibeanrmonitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutBlocker
import com.valiantyan.vibeanrmonitoring.scenario.BroadcastTimeoutScenario

/**
 * Demo 专用阻塞广播接收器，用于复现 BroadcastReceiver 执行超时。
 */
class BroadcastTimeoutReceiver : BroadcastReceiver() {
    /**
     * 收到 Demo action 后在主线程阻塞，形成 BroadcastReceiver 超时现场。
     *
     * @param context Receiver 运行上下文。
     * @param intent 广播 Intent，只有 Demo action 才触发阻塞。
     */
    override fun onReceive(context: Context, intent: Intent): Unit {
        if (intent.action != BroadcastTimeoutScenario.ACTION_BROADCAST_TIMEOUT) {
            return
        }
        BroadcastTimeoutBlocker().block()
    }
}
