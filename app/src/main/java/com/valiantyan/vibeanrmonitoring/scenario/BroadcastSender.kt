package com.valiantyan.vibeanrmonitoring.scenario

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.valiantyan.vibeanrmonitoring.BroadcastTimeoutReceiver

/**
 * 可注入广播发送器，测试中记录 action，真实 Demo 中发送显式应用内广播。
 */
fun interface BroadcastSender {
    /**
     * 发送指定 action 的广播。
     *
     * @param action 广播 action。
     */
    fun send(action: String): Unit
}

/**
 * 使用应用上下文发送显式广播，避免隐式广播限制影响 Demo 可复现性。
 *
 * @param context 用于获取应用上下文并发送广播。
 */
class ContextBroadcastSender(
    context: Context,
) : BroadcastSender {
    // 使用 applicationContext，避免场景类长期持有 Activity。
    private val appContext: Context = context.applicationContext

    /**
     * 构造指向 [BroadcastTimeoutReceiver] 的显式广播，保证测试场景只在当前应用内触发。
     *
     * @param action Demo 专用广播 action。
     */
    override fun send(action: String): Unit {
        val intent: Intent = Intent(action).apply {
            component = ComponentName(appContext, BroadcastTimeoutReceiver::class.java)
            setPackage(appContext.packageName)
        }
        appContext.sendBroadcast(intent)
    }
}
