package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 nativePollOnce 探针公开入口，供灰度 hook 写入真实 timeout 证据。
 */
class AnrNativePollProbeTest {
    /**
     * enter/exit 入口应写入全局 nativePollOnce 记录器。
     */
    @Test
    fun recordEnterAndExitWritesNativePollOnceEvidence(): Unit {
        AnrNativePollProbe.recordEnter(
            timeoutMillis = -1,
            uptimeMs = 1_000L,
        )
        AnrNativePollProbe.recordExit(uptimeMs = 1_600L)

        val record = NativePollOnceMonitor.global.recentRecords(maxRecords = 1).first()

        assertEquals(-1, record.timeoutMillis)
        assertEquals(1_000L, record.enterUptimeMs)
        assertEquals(1_600L, record.exitUptimeMs)
        assertEquals(600L, record.durationMs)
        assertTrue(record.isInfiniteWait)
        assertFalse(record.isInFlight)
    }
}
