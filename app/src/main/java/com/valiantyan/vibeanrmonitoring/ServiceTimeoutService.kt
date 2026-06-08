package com.valiantyan.vibeanrmonitoring

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutBlocker
import com.valiantyan.vibeanrmonitoring.scenario.ServiceTimeoutScenario

/**
 * Demo 专用阻塞 Service，用于复现 Service 生命周期执行超时。
 */
class ServiceTimeoutService : Service() {
    /**
     * 本场景不提供绑定能力，只验证 started Service 的 [onStartCommand] 超时。
     *
     * @param intent 绑定 Intent。
     * @return 始终返回 null。
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 收到 Demo action 后在主线程阻塞，形成 Service 执行超时现场。
     *
     * @param intent 启动 Intent，只有 Demo action 才触发阻塞。
     * @param flags 系统启动标记。
     * @param startId 本次启动 id，用于阻塞结束后停止当前启动请求。
     * @return [START_NOT_STICKY]，避免进程恢复后自动重启 Demo 阻塞场景。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ServiceTimeoutScenario.ACTION_SERVICE_TIMEOUT) {
            return START_NOT_STICKY
        }
        ServiceTimeoutBlocker().block()
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
