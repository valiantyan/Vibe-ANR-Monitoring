package com.valiantyan.anrmonitor.collector.checktime

import com.valiantyan.anrmonitor.domain.model.ChecktimeSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 [ChecktimeMonitor] 的调度延迟统计，确保报告能表达 Watchdog 自身是否被系统调度拖慢。
 */
class ChecktimeMonitorTest {
    /**
     * Checktime 需要输出最大延迟和严重延迟次数，避免只知道主线程阻塞而不知道系统调度是否异常。
     */
    @Test
    fun summarizeRecordsMaxAndSevereDelay(): Unit {
        val monitor = ChecktimeMonitor(
            expectedIntervalMs = 300L,
            severeDelayMs = 800L,
        )
        monitor.recordDelay(actualIntervalMs = 320L)
        monitor.recordDelay(actualIntervalMs = 1_200L)

        val summary: ChecktimeSummary = monitor.summary()

        assertEquals(900L, summary.maxDelayMs)
        assertEquals(1, summary.severeDelayCount)
    }
}
