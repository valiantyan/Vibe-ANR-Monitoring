package com.valiantyan.vibeanrmonitoring

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Demo 专用远端进程 Service，用于复现主进程同步 Binder 调用被远端阻塞。
 */
class RemoteBlockingService : Service() {
    // AIDL Stub 会在远端 Binder 线程执行，用于稳定制造同步 transact 等待。
    private val binder: IRemoteBlockingService.Stub = object : IRemoteBlockingService.Stub() {
        /**
         * 在远端 Binder 线程阻塞指定时长，让主进程调用方停在 BinderProxy.transact。
         *
         * @param durationMs 远端阻塞时长，单位毫秒。
         */
        override fun blockFor(durationMs: Long): Unit {
            Log.w(TAG, "remote binder block start: durationMs=$durationMs")
            Thread.sleep(durationMs)
            Log.w(TAG, "remote binder block end")
        }
    }

    /**
     * 返回 AIDL Binder stub，调用方会拿到跨进程代理。
     *
     * @param intent 绑定 Service 的 Intent，本场景不依赖其中参数。
     * @return 远端阻塞调用的 Binder stub。
     */
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    private companion object {
        /**
         * 远端 Binder 场景日志标签。
         */
        private const val TAG: String = "RemoteBlockingService"
    }
}
