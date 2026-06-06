package com.valiantyan.anrmonitor.collector.sharedprefs

import com.valiantyan.anrmonitor.domain.model.SharedPreferencesFileStat
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationRecord
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 SharedPreferences 文件健康度和运行期操作证据，避免第 5 篇专项只剩文件大小排序。
 */
class SharedPreferencesHealthScannerTest {
    /**
     * 扫描结果需要同时表达文件大小、key 数、首次加载、写入次数和 pending finisher。
     */
    @Test
    fun scanBuildsSharedPreferencesHealthSnapshot(): Unit {
        val scanner = SharedPreferencesHealthScanner(
            fileReader = {
                listOf(
                    SharedPreferencesFileStat(
                        fileName = "small.xml",
                        sizeBytes = 100L,
                        keyCount = 2,
                    ),
                    SharedPreferencesFileStat(
                        fileName = "large.xml",
                        sizeBytes = 2_000L,
                        keyCount = 80,
                    ),
                )
            },
            operationReader = {
                listOf(
                    operation(
                        operationType = SharedPreferencesOperationType.LOAD,
                        costMs = 450L,
                    ),
                    operation(
                        operationType = SharedPreferencesOperationType.APPLY,
                        costMs = 32L,
                    ),
                    operation(
                        operationType = SharedPreferencesOperationType.COMMIT,
                        costMs = 90L,
                    ),
                )
            },
            pendingFinisherReader = { 3 },
            bypassPolicyProvider = {
                QueuedWorkBypassPolicy(
                    enabled = false,
                    allowedFiles = setOf("large.xml"),
                    blockedFiles = emptySet(),
                )
            },
        )

        val snapshot = scanner.scan(
            maxFileCount = 1,
            maxOperationCount = 2,
        )

        assertTrue(snapshot.available)
        assertEquals("large.xml", snapshot.topFiles.first().fileName)
        assertEquals(2_000L, snapshot.topFiles.first().sizeBytes)
        assertEquals(80, snapshot.topFiles.first().keyCount)
        assertEquals(450L, snapshot.topFiles.first().firstLoadCostMs)
        assertEquals(1, snapshot.topFiles.first().applyCount)
        assertEquals(1, snapshot.topFiles.first().commitCount)
        assertEquals(90L, snapshot.topFiles.first().lastWriteCostMs)
        assertEquals(2, snapshot.recentOperations.size)
        assertEquals(3, snapshot.pendingFinisherCount)
        assertFalse(snapshot.queuedWorkBypass.enabled)
        assertEquals("large.xml", scanner.scanTopFiles(maxCount = 1).first().fileName)
    }

    /**
     * 读取 shared_prefs 目录失败时必须显式降级，不能让报告误以为没有 SP 风险。
     */
    @Test
    fun scanReturnsUnavailableSnapshotWhenFileReaderFails(): Unit {
        val scanner = SharedPreferencesHealthScanner(
            fileReader = { throw SecurityException("permission denied") },
        )

        val snapshot = scanner.scan(
            maxFileCount = 5,
            maxOperationCount = 5,
        )

        assertFalse(snapshot.available)
        assertTrue(snapshot.failureReason.orEmpty().contains("permission denied"))
        assertTrue(snapshot.topFiles.isEmpty())
        assertTrue(snapshot.recentOperations.isEmpty())
    }

    // 构造同一个 SP 文件的运行期操作，便于验证 scanner 聚合字段。
    private fun operation(
        operationType: SharedPreferencesOperationType,
        costMs: Long,
    ): SharedPreferencesOperationRecord {
        return SharedPreferencesOperationRecord(
            fileName = "large.xml",
            operationType = operationType,
            costMs = costMs,
            timestampUptimeMs = 1_000L + costMs,
            threadName = "main",
            stackFrames = listOf("com.example.MainActivity.onStop(MainActivity.kt:20)"),
            success = true,
            pendingFinisherCount = 3,
        )
    }
}
