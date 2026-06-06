package com.valiantyan.anrmonitor.collector.threadcpu

import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证线程 CPU 排名采集器，确保进程内资源证据按消耗排序输出。
 */
class ThreadCpuSnapshotterTest {
    /**
     * CPU 排名必须按 user + system 总消耗降序输出，便于 ANR 报告快速定位进程内热点线程。
     */
    @Test
    fun rankThreadsSortsByCpuDeltaDescending(): Unit {
        val snapshotter = ThreadCpuSnapshotter(
            statReader = {
                listOf(
                    ThreadCpuStat(
                        tid = 1,
                        threadName = "main",
                        userMs = 10L,
                        systemMs = 5L,
                    ),
                    ThreadCpuStat(
                        tid = 2,
                        threadName = "io-worker",
                        userMs = 10L,
                        systemMs = 90L,
                    ),
                )
            },
        )

        val result: List<ThreadCpuRecord> = snapshotter.captureTopThreads(maxCount = 1)

        assertEquals("io-worker", result.first().threadName)
        assertEquals(100L, result.first().totalCpuMs)
    }
}
