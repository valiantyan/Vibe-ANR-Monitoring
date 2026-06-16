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
     * 该回调只表示 SDK 已生成并写入报告，不代表系统一定已确认 ANR；调用方应通过
     * [AnrReport.snapshot] 中的事件阶段或系统 ANR 信息判断确认状态。
     *
     * @param report 已归因的 ANR 报告。
     */
    fun onReportGenerated(report: AnrReport): Unit = Unit

    /**
     * 兼容旧版本宿主覆写的报告生成回调，也是运行时保持旧宿主二进制兼容的稳定派发入口。
     *
     * 该名称容易被误读为系统已确认 ANR；新代码应改用 [onReportGenerated]，并从
     * [AnrReport.snapshot] 判断报告阶段。
     *
     * @param report 已归因的 ANR 报告。
     */
    @Deprecated(
        message = "该回调表示报告已生成，不表示系统确认 ANR；请改用 onReportGenerated。",
        replaceWith = ReplaceWith(expression = "onReportGenerated(report)"),
    )
    fun onConfirmedAnr(report: AnrReport): Unit = onReportGenerated(report = report)

    /**
     * 当 SDK 内部采集或上报出现异常时回调，避免异常影响宿主进程。
     *
     * @param error 被 SDK 捕获的异常。
     */
    fun onMonitorError(error: Throwable): Unit = Unit
}
