package com.valiantyan.vibeanrmonitoring.scenario

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import com.valiantyan.vibeanrmonitoring.IRemoteBlockingService
import com.valiantyan.vibeanrmonitoring.RemoteBlockingService

/**
 * 可注入远端 Binder 客户端，测试中记录调用，真实 Demo 中绑定跨进程 Service。
 */
interface RemoteBinderClient {
    /**
     * 确保远端 Service 已开始绑定。
     */
    fun ensureBound(): Unit

    /**
     * 同步调用远端阻塞方法。
     *
     * @param durationMs 远端阻塞时长，单位毫秒。
     */
    fun blockRemote(durationMs: Long): Unit

    /**
     * 释放 Service 绑定。
     */
    fun release(): Unit
}

/**
 * 真实跨进程 Binder 客户端，主线程调用 [blockRemote] 时会停在 BinderProxy.transact。
 *
 * @param context 用于绑定远端 Service。
 * @param failureNotifier 绑定未完成或远端异常时提示用户本次没有产生有效 Binder 样本。
 */
class RemoteBlockingServiceClient(
    context: Context,
    private val failureNotifier: ScenarioFailureNotifier,
) : RemoteBinderClient {
    // 使用应用上下文持有绑定，避免 Activity 短生命周期导致连接对象泄漏。
    private val appContext: Context = context.applicationContext

    // 已连接的远端 AIDL 代理，为空表示当前无法制造同步 transact 阻塞样本。
    private var remoteService: IRemoteBlockingService? = null

    // 绑定中的标记，避免重复调用 [Context.bindService]。
    private var isBinding: Boolean = false

    // 释放标记，避免 Activity 销毁后继续重绑远端进程。
    private var isReleased: Boolean = false

    // ServiceConnection 负责把系统返回的 Binder 转成 AIDL 代理。
    private val connection: ServiceConnection = object : ServiceConnection {
        /**
         * 远端 Service 连接成功后缓存 AIDL 代理。
         */
        override fun onServiceConnected(
            name: ComponentName?,
            service: IBinder?,
        ): Unit {
            remoteService = IRemoteBlockingService.Stub.asInterface(service)
            isBinding = false
            Log.w(TAG, "remote binder service connected")
        }

        /**
         * 远端 Service 异常断开后清理代理，下一次触发前需要重新绑定。
         */
        override fun onServiceDisconnected(name: ComponentName?): Unit {
            remoteService = null
            isBinding = false
            Log.w(TAG, "remote binder service disconnected")
        }
    }

    /**
     * 开始绑定远端 Service。重复调用不会重复 bind。
     */
    override fun ensureBound(): Unit {
        if (isReleased || remoteService != null || isBinding) {
            return
        }
        isBinding = true
        val intent: Intent = Intent(appContext, RemoteBlockingService::class.java)
        val bound: Boolean = appContext.bindService(
            intent,
            connection,
            Context.BIND_AUTO_CREATE,
        )
        if (!bound) {
            isBinding = false
            failureNotifier.notifyFailure(
                message = "Remote binder service bind failed",
                error = null,
            )
        }
    }

    /**
     * 同步调用远端阻塞方法。若 Service 还未连接，只提示用户重试，不制造伪样本。
     */
    override fun blockRemote(durationMs: Long): Unit {
        val service: IRemoteBlockingService = remoteService ?: run {
            ensureBound()
            failureNotifier.notifyFailure(
                message = "Remote binder service is not connected, please tap again",
                error = null,
            )
            return
        }
        try {
            service.blockFor(durationMs)
        } catch (error: RemoteException) {
            remoteService = null
            failureNotifier.notifyFailure(
                message = "Remote binder call failed",
                error = error,
            )
            ensureBound()
        }
    }

    /**
     * 释放绑定，避免 Activity 销毁后泄漏 ServiceConnection。
     */
    override fun release(): Unit {
        if (isReleased) {
            return
        }
        isReleased = true
        runCatching {
            appContext.unbindService(connection)
        }.onFailure { error: Throwable ->
            Log.w(TAG, "unbind remote binder service failed: ${error.message}")
        }
        remoteService = null
        isBinding = false
    }

    private companion object {
        /**
         * Binder 场景日志标签。
         */
        private const val TAG: String = "BinderCrossProcess"
    }
}

/**
 * Binder 场景失败提示器，避免 Service 未连接时用户误以为已经制造了跨进程阻塞。
 */
class BinderScenarioFailureNotifier(
    private val context: Context,
) : ScenarioFailureNotifier {
    /**
     * 同时输出 Logcat 和 Toast，方便测试者知道本次没有产生有效 Binder 样本。
     */
    override fun notifyFailure(
        message: String,
        error: Throwable?,
    ): Unit {
        Log.e(TAG, "Binder 跨进程阻塞场景触发失败: $message", error)
        Toast.makeText(
            context,
            "Binder 场景未就绪，请稍后再点一次",
            Toast.LENGTH_LONG,
        ).show()
    }

    private companion object {
        /**
         * Binder 场景日志标签。
         */
        private const val TAG: String = "BinderCrossProcess"
    }
}
