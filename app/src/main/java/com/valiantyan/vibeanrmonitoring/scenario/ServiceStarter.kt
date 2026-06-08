package com.valiantyan.vibeanrmonitoring.scenario

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.valiantyan.vibeanrmonitoring.ServiceTimeoutService

/**
 * 可注入 Service 启动器，测试中记录 action，真实 Demo 中启动显式应用内 Service。
 */
fun interface ServiceStarter {
    /**
     * 使用指定 action 启动 Demo Service。
     *
     * @param action Service 启动 action。
     */
    fun start(action: String): Unit
}

/**
 * 使用应用上下文启动显式 Service，避免持有 Activity 并确保场景只在当前应用内触发。
 *
 * @param context 用于获取应用上下文并启动 Service。
 */
class ContextServiceStarter(
    context: Context,
) : ServiceStarter {
    // 使用 applicationContext，避免场景类长期持有 Activity。
    private val appContext: Context = context.applicationContext

    /**
     * 构造指向 [ServiceTimeoutService] 的显式 Intent，保证测试场景稳定命中 Demo Service。
     *
     * @param action Demo 专用 Service action。
     */
    override fun start(action: String): Unit {
        val intent: Intent = Intent(action).apply {
            component = ComponentName(appContext, ServiceTimeoutService::class.java)
            setPackage(appContext.packageName)
        }
        appContext.startService(intent)
    }
}
