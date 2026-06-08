package com.valiantyan.vibeanrmonitoring

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Sync Barrier 泄漏验证用 Service，用于向主线程队列投递系统组件消息。
 */
class BarrierLeakService : Service() {
    /**
     * 当前场景不提供绑定能力，只通过 [onStartCommand] 验证组件消息是否被调度。
     *
     * @param intent 启动参数，本场景不读取。
     * @return 始终返回空绑定。
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * 如果该回调被执行，说明 Barrier 没有挡住 Service 组件消息。
     *
     * @param intent 启动参数，本场景不读取。
     * @param flags 系统启动标记。
     * @param startId 本次启动 ID。
     * @return 非粘性启动，避免测试 Service 被系统自动拉起。
     */
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.w(TAG, "Barrier 泄漏验证失败，Service 消息已经执行: startId=$startId")
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private companion object {
        /**
         * Service 日志标签。
         */
        private const val TAG: String = "BarrierLeakService"
    }
}
