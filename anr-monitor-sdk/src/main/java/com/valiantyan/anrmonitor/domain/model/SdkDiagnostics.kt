package com.valiantyan.anrmonitor.domain.model

/**
 * SDK 自身采集状态和成本诊断。
 *
 * @property pendingAvailable Pending 队列采集是否可用。
 * @property reportBuildCostMs 构建报告耗时。
 * @property collectorFailures 各采集器失败摘要，避免静默丢证据。
 * @property privacyMode 当前报告使用的隐私模式。
 * @property missingEvidenceCount 当前归因结果缺失证据数量。
 * @property selfMetrics SDK 自身治理链路计数指标。
 */
data class SdkDiagnostics(
    val pendingAvailable: Boolean,
    val reportBuildCostMs: Long,
    val collectorFailures: List<String>,
    val privacyMode: String? = null,
    val missingEvidenceCount: Int = 0,
    val selfMetrics: Map<String, Long> = emptyMap(),
)
