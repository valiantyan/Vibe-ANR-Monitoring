package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot

/**
 * 供调试、自动化验收和宿主观测 SDK 内部事件的监听器。
 */
interface AnrEventListener {
    /**
     * 当 Watchdog 发现疑似 ANR 时回调，便于宿主查看未确认前的原始证据。
     *
     * @param snapshot 疑似 ANR 现场快照。
     */
    fun onSuspectAnr(snapshot: AnrSnapshot): Unit = Unit

    /**
     * 当 SDK 生成完整报告时回调，便于测试和宿主侧旁路消费。
     *
     * @param report 已归因的 ANR 报告。
     */
    fun onConfirmedAnr(report: AnrReport): Unit = Unit

    /**
     * 当 SDK 内部采集或上报出现异常时回调，避免异常影响宿主进程。
     *
     * @param error 被 SDK 捕获的异常。
     */
    fun onMonitorError(error: Throwable): Unit = Unit
}
