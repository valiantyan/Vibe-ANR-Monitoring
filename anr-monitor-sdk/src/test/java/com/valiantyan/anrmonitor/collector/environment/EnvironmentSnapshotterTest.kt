package com.valiantyan.anrmonitor.collector.environment

import com.valiantyan.anrmonitor.domain.model.EnvironmentEvidenceAvailability
import com.valiantyan.anrmonitor.domain.model.MemorySnapshot
import com.valiantyan.anrmonitor.domain.model.ProcessIoSnapshot
import com.valiantyan.anrmonitor.domain.model.SystemEnvironmentSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [EnvironmentSnapshotter] 的系统环境采集和失败降级，避免报告缺失外部负载证据。
 */
class EnvironmentSnapshotterTest {
    /**
     * 环境采集成功时必须同时表达 CPU Load、内存、存储和进程 I/O 可得性。
     */
    @Test
    fun captureIncludesDeviceEnvironmentAndAvailability(): Unit {
        val snapshotter = EnvironmentSnapshotter(
            buildProvider = {
                DeviceBuildInfo(
                    androidVersion = "14",
                    manufacturer = "Google",
                    model = "Pixel",
                )
            },
            loadAverageReader = { EnvironmentReadResult.available(value = 2.5) },
            memoryReader = {
                EnvironmentReadResult.available(
                    value = MemorySnapshot(
                        availableBytes = 512L,
                        totalBytes = 1_024L,
                        isLowMemory = false,
                    ),
                )
            },
            storageReader = { EnvironmentReadResult.available(value = 4_096L) },
            processIoReader = {
                EnvironmentReadResult.available(
                    value = ProcessIoSnapshot(
                        readBytes = 10L,
                        writeBytes = 20L,
                        cancelledWriteBytes = 0L,
                    ),
                )
            },
        )

        val snapshot: SystemEnvironmentSnapshot = snapshotter.capture()

        assertEquals(2.5, snapshot.loadAverage1m ?: 0.0, 0.0)
        assertEquals(512L, snapshot.memory?.availableBytes)
        assertEquals(4_096L, snapshot.availableStorageBytes)
        assertEquals(20L, snapshot.processIo?.writeBytes)
        assertEquals("Pixel", snapshot.model)
        assertEquals(
            EnvironmentEvidenceAvailability(
                cpuLoadAvailable = true,
                memoryAvailable = true,
                storageAvailable = true,
                processIoAvailable = true,
            ),
            snapshot.availability,
        )
    }

    /**
     * 采集失败时必须保留失败原因和可得性标记，供评审判断是无证据还是证据不可采。
     */
    @Test
    fun captureKeepsFailureReasonsWhenReadersAreUnavailable(): Unit {
        val snapshotter = EnvironmentSnapshotter(
            buildProvider = {
                DeviceBuildInfo(
                    androidVersion = "14",
                    manufacturer = "Google",
                    model = "Pixel",
                )
            },
            loadAverageReader = { EnvironmentReadResult.unavailable(reason = "loadavg denied") },
            memoryReader = {
                EnvironmentReadResult.available(
                    value = MemorySnapshot(
                        availableBytes = 512L,
                        totalBytes = 1_024L,
                        isLowMemory = false,
                    ),
                )
            },
            storageReader = { EnvironmentReadResult.unavailable(reason = "storage denied") },
            processIoReader = { EnvironmentReadResult.unavailable(reason = "proc io denied") },
        )

        val snapshot: SystemEnvironmentSnapshot = snapshotter.capture()

        assertFalse(snapshot.availability.cpuLoadAvailable)
        assertTrue(snapshot.availability.memoryAvailable)
        assertFalse(snapshot.availability.storageAvailable)
        assertFalse(snapshot.availability.processIoAvailable)
        assertTrue(snapshot.failureReasons.contains("loadavg denied"))
        assertTrue(snapshot.failureReasons.contains("storage denied"))
        assertTrue(snapshot.failureReasons.contains("proc io denied"))
    }
}
