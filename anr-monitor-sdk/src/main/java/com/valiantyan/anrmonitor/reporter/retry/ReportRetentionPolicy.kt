package com.valiantyan.anrmonitor.reporter.retry

/**
 * 本地报告文件元信息，供保留策略在不读取报告内容的前提下做治理。
 *
 * @property fileName 报告文件名。
 * @property createUptimeMs 报告创建时间，调用方需保证和 [nowUptimeMs] 使用同一时间基准。
 * @property sizeBytes 报告文件大小，无法读取时可传 0。
 */
data class LocalReportMeta(
    val fileName: String,
    val createUptimeMs: Long,
    val sizeBytes: Long = 0L,
)

/**
 * 本地报告保留策略，按数量、总大小和保留时长共同控制磁盘占用。
 *
 * @param maxFileCount 最多保留的报告文件数。
 * @param maxTotalBytes 最多保留的报告总字节数。
 * @param maxAgeMs 最长保留时长。
 */
class ReportRetentionPolicy(
    maxFileCount: Int,
    maxTotalBytes: Long = Long.MAX_VALUE,
    maxAgeMs: Long = Long.MAX_VALUE,
) {
    // 数量上限小于 0 时按 0 处理，避免调用方误配导致全部保留。
    private val safeMaxFileCount: Int = maxFileCount.coerceAtLeast(minimumValue = 0)

    // 大小上限小于 0 时按 0 处理，保证策略输出可预测。
    private val safeMaxTotalBytes: Long = maxTotalBytes.coerceAtLeast(minimumValue = 0L)

    // 时长上限小于 0 时按 0 处理，表示立即过期。
    private val safeMaxAgeMs: Long = maxAgeMs.coerceAtLeast(minimumValue = 0L)

    /**
     * 选择应清理的报告，优先保留最新且仍在大小预算内的文件。
     *
     * @param reports 当前本地报告元信息。
     * @param nowUptimeMs 当前时间，需和 [LocalReportMeta.createUptimeMs] 使用同一基准。
     * @return 应删除的报告元信息，顺序保持为输入顺序。
     */
    fun selectExpiredReports(
        reports: List<LocalReportMeta>,
        nowUptimeMs: Long = Long.MAX_VALUE,
    ): List<LocalReportMeta> {
        if (reports.isEmpty()) {
            return emptyList()
        }
        val expiredByAge: Set<LocalReportMeta> = reports
            .filter { report: LocalReportMeta -> isExpiredByAge(report = report, nowUptimeMs = nowUptimeMs) }
            .toSet()
        val keptReports: Set<LocalReportMeta> = selectKeptReports(
            reports = reports.filterNot { report: LocalReportMeta -> expiredByAge.contains(element = report) },
        )
        return reports.filter { report: LocalReportMeta ->
            expiredByAge.contains(element = report) || !keptReports.contains(element = report)
        }
    }

    // 按时长判断过期，避免历史报告长期堆积影响磁盘和隐私风险。
    private fun isExpiredByAge(
        report: LocalReportMeta,
        nowUptimeMs: Long,
    ): Boolean {
        return nowUptimeMs - report.createUptimeMs > safeMaxAgeMs
    }

    // 选择最新且在数量、大小预算内的报告集合。
    private fun selectKeptReports(reports: List<LocalReportMeta>): Set<LocalReportMeta> {
        var keptBytes: Long = 0L
        val keptReports: MutableSet<LocalReportMeta> = linkedSetOf()
        reports
            .sortedByDescending { report: LocalReportMeta -> report.createUptimeMs }
            .forEach { report: LocalReportMeta ->
                if (canKeepReport(report = report, keptCount = keptReports.size, keptBytes = keptBytes)) {
                    keptReports.add(element = report)
                    keptBytes += report.sizeBytes.coerceAtLeast(minimumValue = 0L)
                }
            }
        return keptReports
    }

    // 同时满足数量和大小预算时才保留，避免某个维度失控。
    private fun canKeepReport(
        report: LocalReportMeta,
        keptCount: Int,
        keptBytes: Long,
    ): Boolean {
        if (keptCount >= safeMaxFileCount) {
            return false
        }
        val nextBytes: Long = keptBytes + report.sizeBytes.coerceAtLeast(minimumValue = 0L)
        return nextBytes <= safeMaxTotalBytes
    }
}
