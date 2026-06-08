package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.content.Intent
import android.util.Log
import com.valiantyan.vibeanrmonitoring.BarrierLeakService

/**
 * 可注入组件消息触发器，用于让 ActivityThread 组件消息也排入 Barrier 后方。
 */
fun interface BarrierLeakComponentStarter {
    /**
     * 触发一个会进入主线程队列的组件动作。
     */
    fun start(): Unit
}

/**
 * 启动 Demo 测试 Service 的真实实现。
 */
class ServiceBarrierLeakComponentStarter(
    private val context: Context,
) : BarrierLeakComponentStarter {
    /**
     * 启动测试 Service，让组件调度消息进入主线程队列并暴露 Barrier 影响。
     */
    override fun start(): Unit {
        val intent: Intent = Intent(
            context,
            BarrierLeakService::class.java,
        )
        try {
            Log.d(TAG, "开始启动 Barrier 泄漏测试 Service")
            context.startService(intent)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "Barrier 泄漏测试 Service 启动失败: ${error.message}", error)
        } catch (error: SecurityException) {
            Log.e(TAG, "Barrier 泄漏测试 Service 启动被系统拒绝: ${error.message}", error)
        }
    }

    private companion object {
        /**
         * Sync Barrier 场景日志标签。
         */
        private const val TAG: String = "SyncBarrierLeak"
    }
}
