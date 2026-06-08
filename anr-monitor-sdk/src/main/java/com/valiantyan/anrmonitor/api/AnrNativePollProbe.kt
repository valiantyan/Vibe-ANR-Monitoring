package com.valiantyan.anrmonitor.api

import android.os.SystemClock
import com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitor

/**
 * nativePollOnce 探针入口，供宿主自有 hook、JVMTI 或灰度调试代码写入真实轮询证据。
 *
 * 这个对象不主动安装 hook，也不会改变系统 Looper 行为。
 */
object AnrNativePollProbe {
    /**
     * 记录进入 nativePollOnce。
     *
     * @param timeoutMillis nativePollOnce timeout 参数，-1 表示无限等待。
     * @param uptimeMs 进入时的 uptime，默认使用当前 [SystemClock.uptimeMillis]。
     */
    fun recordEnter(
        timeoutMillis: Int,
        uptimeMs: Long = SystemClock.uptimeMillis(),
    ): Unit {
        NativePollOnceMonitor.global.onEnter(
            timeoutMillis = timeoutMillis,
            uptimeMs = uptimeMs,
        )
    }

    /**
     * 记录退出 nativePollOnce。
     *
     * @param uptimeMs 退出时的 uptime，默认使用当前 [SystemClock.uptimeMillis]。
     */
    fun recordExit(uptimeMs: Long = SystemClock.uptimeMillis()): Unit {
        NativePollOnceMonitor.global.onExit(uptimeMs = uptimeMs)
    }

    /**
     * 记录一次已完成的 nativePollOnce 调用，适合只能在调用后拿到 timeout 的低风险入口。
     *
     * @param timeoutMillis nativePollOnce timeout 参数。
     * @param uptimeMs 记录发生时的 uptime，默认使用当前 [SystemClock.uptimeMillis]。
     */
    fun recordCompleted(
        timeoutMillis: Int,
        uptimeMs: Long = SystemClock.uptimeMillis(),
    ): Unit {
        NativePollOnceMonitor.global.record(
            timeoutMillis = timeoutMillis,
            uptimeMs = uptimeMs,
        )
    }
}
