package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.domain.model.AnrReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 验证 [AnrEventListener] 的报告回调语义，避免把报告生成误读为系统确认 ANR。
 */
class AnrEventListenerTest {
    /**
     * 新回调应表达“报告已生成”，由调用方继续通过 [AnrReport.snapshot] 判断系统确认态。
     */
    @Test
    fun onReportGeneratedReceivesCompletedReport(): Unit {
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )
        var receivedReport: AnrReport? = null
        val listener = object : AnrEventListener {
            /**
             * 记录生成后的报告，验证新语义回调不再暗示系统已确认 ANR。
             */
            override fun onReportGenerated(report: AnrReport): Unit {
                receivedReport = report
            }
        }

        listener.onReportGenerated(report = report)

        assertSame(report, receivedReport)
    }

    /**
     * 已有宿主若仍覆写旧回调，新回调默认实现必须继续转发，避免 SDK 升级后丢事件。
     */
    @Test
    fun deprecatedConfirmedCallbackDelegatesToOnReportGenerated(): Unit {
        val report: AnrReport = AnrReport.empty(
            appId = "demo",
            environment = "debug",
        )
        var reportGeneratedCount: Int = 0
        val listener = object : AnrEventListener {
            /**
             * 新回调表达准确语义，旧派发入口会转发到这里。
             */
            override fun onReportGenerated(report: AnrReport): Unit {
                reportGeneratedCount += 1
            }
        }

        @Suppress("DEPRECATION")
        listener.onConfirmedAnr(report = report)

        assertEquals(1, reportGeneratedCount)
    }
}
