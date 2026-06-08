package com.valiantyan.vibeanrmonitoring.scenario

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * 场景失败提示器，避免反射失败时用户误以为已经制造了 ANR。
 */
fun interface ScenarioFailureNotifier {
    /**
     * 提示场景触发失败。
     *
     * @param message 稳定的失败原因。
     * @param error 具体异常，可能为空。
     */
    fun notifyFailure(
        message: String,
        error: Throwable?,
    ): Unit
}

/**
 * Android Demo 使用的真实失败提示器。
 */
class ToastScenarioFailureNotifier(
    private val context: Context,
) : ScenarioFailureNotifier {
    /**
     * 同时输出 Logcat 和 Toast，方便测试者知道本次没有真正插入 Barrier。
     */
    override fun notifyFailure(
        message: String,
        error: Throwable?,
    ): Unit {
        Log.e(TAG, "Sync Barrier 泄漏场景触发失败: $message", error)
        val detail: String = error?.javaClass?.simpleName ?: message
        Toast.makeText(
            context,
            "Sync Barrier 触发失败：$detail",
            Toast.LENGTH_LONG,
        ).show()
    }

    private companion object {
        /**
         * Sync Barrier 场景日志标签。
         */
        private const val TAG: String = "SyncBarrierLeak"
    }
}
