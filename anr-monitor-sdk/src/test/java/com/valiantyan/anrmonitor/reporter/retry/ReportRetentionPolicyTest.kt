package com.valiantyan.anrmonitor.reporter.retry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证本地报告治理策略，避免疑似 ANR 高频触发后无限占用私有目录。
 */
class ReportRetentionPolicyTest {
    /**
     * 超过数量上限时保留最新报告，删除最旧报告。
     */
    @Test
    fun selectExpiredReportsKeepsNewestWithinLimit(): Unit {
        val policy = ReportRetentionPolicy(
            maxFileCount = 2,
        )

        val expired: List<LocalReportMeta> = policy.selectExpiredReports(
            reports = listOf(
                LocalReportMeta(fileName = "1.json", createUptimeMs = 1L),
                LocalReportMeta(fileName = "2.json", createUptimeMs = 2L),
                LocalReportMeta(fileName = "3.json", createUptimeMs = 3L),
            ),
        )

        assertEquals(listOf("1.json"), expired.map { report: LocalReportMeta -> report.fileName })
    }

    /**
     * 数量未超限但总大小超限时，仍需要从旧到新淘汰。
     */
    @Test
    fun selectExpiredReportsDropsOldestWhenTotalBytesExceedsLimit(): Unit {
        val policy = ReportRetentionPolicy(
            maxFileCount = 10,
            maxTotalBytes = 100L,
        )

        val expired: List<LocalReportMeta> = policy.selectExpiredReports(
            reports = listOf(
                LocalReportMeta(
                    fileName = "old.json",
                    createUptimeMs = 1L,
                    sizeBytes = 60L,
                ),
                LocalReportMeta(
                    fileName = "new.json",
                    createUptimeMs = 2L,
                    sizeBytes = 60L,
                ),
            ),
        )

        assertEquals(listOf("old.json"), expired.map { report: LocalReportMeta -> report.fileName })
    }

    /**
     * 超过保留时长的报告必须被清理，即使数量和大小仍在预算内。
     */
    @Test
    fun selectExpiredReportsDropsReportsOlderThanMaxAge(): Unit {
        val policy = ReportRetentionPolicy(
            maxFileCount = 10,
            maxTotalBytes = 1_000L,
            maxAgeMs = 100L,
        )

        val expired: List<LocalReportMeta> = policy.selectExpiredReports(
            reports = listOf(
                LocalReportMeta(fileName = "expired.json", createUptimeMs = 10L),
                LocalReportMeta(fileName = "fresh.json", createUptimeMs = 120L),
            ),
            nowUptimeMs = 130L,
        )

        assertEquals(listOf("expired.json"), expired.map { report: LocalReportMeta -> report.fileName })
    }
}
