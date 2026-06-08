package com.valiantyan.vibeanrmonitoring

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
import android.util.Log
import android.widget.Toast
import com.valiantyan.anrmonitor.api.AnrBarrierDebug
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Debug 专用 Sync Barrier 泄漏场景，用于复现主线程停在 [MessageQueue.nativePollOnce] 的真实 ANR。
 *
 * @param context 用于启动测试 Service，保持系统组件消息排入主线程队列。
 */
class SyncBarrierLeakScenario(
    private val context: Context,
) {
    // 主线程 Handler，用于在 Barrier 后投递会被阻塞的同步消息。
    private val mainHandler: Handler = Handler(Looper.getMainLooper())

    /**
     * 插入一个故意不移除的 Sync Barrier，并记录 token 供 SDK 报告对齐。
     */
    fun run(): Unit {
        val token: Int = postSyncBarrier() ?: return
        AnrBarrierDebug.recordPostSyncBarrier(
            token = token,
            stackFrames = captureStackFrames(),
        )
        postBlockedSynchronousMessages()
        startBarrierLeakService()
        Log.w(TAG, "Sync Barrier 泄漏场景已触发，token=$token")
    }

    // 反射调用隐藏 API，只在 Demo Debug 场景使用，失败时明确提示而不是继续伪造证据。
    private fun postSyncBarrier(): Int? {
        return try {
            Log.d(TAG, "开始反射调用 postSyncBarrier")
            val queue: MessageQueue = Looper.getMainLooper().queue
            val method: Method = MessageQueue::class.java.getDeclaredMethod("postSyncBarrier")
            method.isAccessible = true
            method.invoke(queue) as Int
        } catch (error: NoSuchMethodException) {
            showFailure(error = error)
            null
        } catch (error: IllegalAccessException) {
            showFailure(error = error)
            null
        } catch (error: InvocationTargetException) {
            showFailure(error = error)
            null
        } catch (error: SecurityException) {
            showFailure(error = error)
            null
        } catch (error: ClassCastException) {
            showFailure(error = error)
            null
        }
    }

    // 投递同步消息，让 Pending 队列在 Barrier 后方留下可归因的 blockedMs 证据。
    private fun postBlockedSynchronousMessages(): Unit {
        repeat(times = BLOCKED_MESSAGE_COUNT) { index: Int ->
            mainHandler.post {
                Log.w(TAG, "Sync Barrier 泄漏验证失败，同步消息被执行: index=$index")
            }
        }
    }

    // 启动测试 Service，让 ActivityThread 组件消息也排到 Barrier 后方。
    private fun startBarrierLeakService(): Unit {
        val intent = Intent(
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

    // 捕获 Demo 调用栈，帮助报告定位是谁插入了未移除的 Barrier。
    private fun captureStackFrames(): List<String> {
        return Thread.currentThread().stackTrace
            .drop(n = STACK_FRAMES_TO_DROP)
            .take(n = MAX_STACK_FRAMES)
            .map { element: StackTraceElement -> element.toString() }
    }

    // 反射失败时给出人可见提示，避免用户误以为已经制造了 ANR 场景。
    private fun showFailure(error: Throwable): Unit {
        Log.e(TAG, "Sync Barrier 泄漏场景触发失败: ${error.message}", error)
        Toast.makeText(
            context,
            "Sync Barrier 触发失败：${error.javaClass.simpleName}",
            Toast.LENGTH_LONG,
        ).show()
    }

    private companion object {
        /**
         * 场景日志标签。
         */
        private const val TAG: String = "SyncBarrierLeak"

        /**
         * Barrier 后方投递的同步消息数量。
         */
        private const val BLOCKED_MESSAGE_COUNT: Int = 8

        /**
         * 调试栈需要跳过的 SDK 辅助帧数量。
         */
        private const val STACK_FRAMES_TO_DROP: Int = 3

        /**
         * 报告中最多保留的插入栈帧数量。
         */
        private const val MAX_STACK_FRAMES: Int = 16
    }
}
