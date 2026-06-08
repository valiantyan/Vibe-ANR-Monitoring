package com.valiantyan.vibeanrmonitoring.scenario

import com.valiantyan.anrmonitor.api.AnrBarrierDebug

/**
 * 可注入 Barrier token 记录器，真实 Demo 中把 token 写入 SDK Barrier 增强证据。
 */
fun interface BarrierDebugRecorder {
    /**
     * 记录一次 Sync Barrier 插入。
     *
     * @param token 系统返回的 Barrier token。
     * @param stackFrames 插入 Barrier 时的调用栈。
     */
    fun recordPostSyncBarrier(
        token: Int,
        stackFrames: List<String>,
    ): Unit
}

/**
 * 调用 SDK Debug API 的真实记录器。
 */
object SdkBarrierDebugRecorder : BarrierDebugRecorder {
    /**
     * 将 Demo 捕获到的 token 和插入栈转交给 SDK，供 JSON 中 Barrier 证据对齐。
     */
    override fun recordPostSyncBarrier(
        token: Int,
        stackFrames: List<String>,
    ): Unit {
        AnrBarrierDebug.recordPostSyncBarrier(
            token = token,
            stackFrames = stackFrames,
        )
    }
}
