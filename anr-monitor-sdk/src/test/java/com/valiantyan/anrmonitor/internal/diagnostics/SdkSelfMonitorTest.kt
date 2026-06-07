package com.valiantyan.anrmonitor.internal.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 SDK 自监控指标聚合，避免采集链路故障只能从异常日志推断。
 */
class SdkSelfMonitorTest {
    /**
     * 计数和耗时都应被聚合到稳定快照中，供报告诊断字段输出。
     */
    @Test
    fun snapshotAggregatesCounterAndDurationMetrics(): Unit {
        val monitor = SdkSelfMonitor()

        monitor.increment(name = "report_queue_enqueued")
        monitor.recordCost(
            name = "report_write_cost_ms",
            costMs = 12L,
        )
        monitor.recordCost(
            name = "report_write_cost_ms",
            costMs = 8L,
        )

        val counters: Map<String, Long> = monitor.snapshotCounters()
        val metrics: List<SdkMetric> = monitor.snapshot()
        val costMetric: SdkMetric = metrics.first { metric: SdkMetric ->
            metric.name == "report_write_cost_ms"
        }

        assertEquals(1L, counters["report_queue_enqueued"])
        assertEquals(2L, costMetric.count)
        assertEquals(20L, costMetric.totalValue)
        assertEquals(8L, costMetric.lastValue)
    }
}
