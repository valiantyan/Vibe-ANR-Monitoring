package com.valiantyan.anrmonitor.internal

import com.valiantyan.anrmonitor.api.AnrMonitorConfig
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.domain.analyzer.AttributionAnalyzer
import com.valiantyan.anrmonitor.domain.analyzer.AttributionThresholds
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.SdkDiagnostics
import com.valiantyan.anrmonitor.internal.diagnostics.SdkSelfMonitor

/**
 * 运行时报告拼装器，负责把现场快照转换为完整可落盘报告。
 *
 * @param config SDK 安装配置，提供归因阈值。
 * @param clock uptime 时间源，用于计算报告构建耗时。
 */
internal class AnrReportAssembler(
    config: AnrMonitorConfig,
    private val clock: Clock,
    private val selfMonitor: SdkSelfMonitor? = null,
) {
    // 基础归因分析器，阈值来自宿主配置。
    private val analyzer: AttributionAnalyzer = AttributionAnalyzer(
        thresholds = AttributionThresholds(
            slowMessageMs = config.slowMessageMs,
            suspectAnrMs = config.suspectAnrMs,
        ),
    )

    // 隐私模式记录到诊断字段，便于报告评审确认当前脱敏策略。
    private val privacyModeName: String = config.privacyMode.name

    /**
     * 拼装完整报告并记录本次报告构建成本。
     *
     * @param snapshot 疑似 ANR 现场快照。
     * @param buildStartMs 报告构建起点 uptime。
     * @return 可编码、落盘和上报的完整报告。
     */
    fun build(
        snapshot: AnrSnapshot,
        buildStartMs: Long,
    ): AnrReport {
        val attribution: AttributionResult = analyzer.analyze(snapshot = snapshot)
        val reportBuildCostMs: Long = clock.uptimeMillis() - buildStartMs
        selfMonitor?.recordCost(
            name = "report_build_cost_ms",
            costMs = reportBuildCostMs,
        )
        return AnrReport(
            schemaVersion = 1,
            snapshot = snapshot,
            attribution = attribution,
            diagnostics = SdkDiagnostics(
                pendingAvailable = snapshot.pendingQueue.available,
                reportBuildCostMs = reportBuildCostMs,
                collectorFailures = collectorFailures(snapshot = snapshot),
                privacyMode = privacyModeName,
                missingEvidenceCount = attribution.missingEvidence.size,
                selfMetrics = selfMonitor?.snapshotCounters().orEmpty(),
            ),
        )
    }

    // 汇总所有 collector 的失败原因，方便 SDK 采集质量监控统一读取。
    private fun collectorFailures(snapshot: AnrSnapshot): List<String> {
        return listOfNotNull(
            snapshot.anrInfo.failureReason,
            snapshot.pendingQueue.failureReason,
            snapshot.checktimeSummary.failureReason,
            snapshot.sharedPreferencesSnapshot.failureReason,
            snapshot.barrierEvidenceSnapshot.failureReason,
            snapshot.binderBlockSnapshot.failureReason,
        ) + snapshot.environmentSnapshot.failureReasons
    }
}
