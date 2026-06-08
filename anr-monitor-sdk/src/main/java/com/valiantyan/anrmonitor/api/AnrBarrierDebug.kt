package com.valiantyan.anrmonitor.api

import android.os.SystemClock
import com.valiantyan.anrmonitor.collector.barrier.BarrierTokenTracker

/**
 * Sync Barrier 调试证据入口，只用于 Demo、Debug 或灰度 hook 把 token 写入 SDK 报告。
 *
 * 这个对象不会主动调用系统 [android.os.MessageQueue.postSyncBarrier] 或
 * [android.os.MessageQueue.removeSyncBarrier]，避免 SDK 改变宿主消息队列行为。
 */
object AnrBarrierDebug {
    /**
     * 记录一次已发生的 Sync Barrier 插入事件，供报告和 Pending 队头 token 对齐。
     *
     * @param token [android.os.MessageQueue.postSyncBarrier] 返回的 token。
     * @param uptimeMs 插入 Barrier 时的 uptime，默认使用当前 [SystemClock.uptimeMillis]。
     * @param stackFrames 插入调用栈；调用方不传时由 SDK 捕获当前线程栈。
     */
    fun recordPostSyncBarrier(
        token: Int,
        uptimeMs: Long = SystemClock.uptimeMillis(),
        stackFrames: List<String> = captureCurrentStackFrames(),
    ): Unit {
        BarrierTokenTracker.global.onPostBarrier(
            token = token,
            uptimeMs = uptimeMs,
            stack = stackFrames.take(n = MAX_STACK_FRAMES),
        )
    }

    /**
     * 记录一次 Sync Barrier 移除事件，正常配对后该 token 不再作为卡住证据输出。
     *
     * @param token 需要移除的 Barrier token。
     * @param uptimeMs 移除时的 uptime，默认使用当前 [SystemClock.uptimeMillis]。
     */
    fun recordRemoveSyncBarrier(
        token: Int,
        uptimeMs: Long = SystemClock.uptimeMillis(),
    ): Unit {
        BarrierTokenTracker.global.onRemoveBarrier(
            token = token,
            uptimeMs = uptimeMs,
        )
    }

    // 捕获调用方栈帧，用于在调试报告中定位是谁插入了 Barrier。
    private fun captureCurrentStackFrames(): List<String> {
        return Thread.currentThread().stackTrace
            .drop(n = STACK_FRAMES_TO_DROP)
            .take(n = MAX_STACK_FRAMES)
            .map { element: StackTraceElement -> element.toString() }
    }

    private const val STACK_FRAMES_TO_DROP: Int = 3

    private const val MAX_STACK_FRAMES: Int = 32
}
