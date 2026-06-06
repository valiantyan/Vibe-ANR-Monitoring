package com.valiantyan.anrmonitor.collector.nativepoll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [nativePollOnce] 进入/退出窗口，确保增强证据能区分阻塞等待和普通短轮询。
 */
class NativePollOnceMonitorTest {
    /**
     * enter/exit 配对后应保留 timeout、持续时间和是否无限等待。
     */
    @Test
    fun recordEnterAndExitBuildsDurationEvidence(): Unit {
        val monitor = NativePollOnceMonitor()

        monitor.onEnter(
            timeoutMillis = -1,
            uptimeMs = 1_000L,
        )
        monitor.onExit(uptimeMs = 1_350L)

        val record = monitor.recentRecords(maxRecords = 10).first()

        assertEquals(-1, record.timeoutMillis)
        assertEquals(1_000L, record.enterUptimeMs)
        assertEquals(1_350L, record.exitUptimeMs)
        assertEquals(350L, record.durationMs)
        assertTrue(record.isInfiniteWait)
    }

    /**
     * 只统计最近窗口中的 [timeoutMillis=-1]，避免历史样本污染当次 ANR 证据。
     */
    @Test
    fun countRecentInfiniteWaitsUsesBoundedWindow(): Unit {
        val monitor = NativePollOnceMonitor()

        monitor.record(
            timeoutMillis = -1,
            uptimeMs = 1_000L,
        )
        monitor.record(
            timeoutMillis = 0,
            uptimeMs = 1_100L,
        )
        monitor.record(
            timeoutMillis = -1,
            uptimeMs = 1_200L,
        )

        assertEquals(1, monitor.countRecentInfiniteWaits(maxRecords = 2))
        assertEquals(2, monitor.countRecentInfiniteWaits(maxRecords = 3))
        assertFalse(monitor.recentRecords(maxRecords = 1).first().isInFlight)
    }
}
