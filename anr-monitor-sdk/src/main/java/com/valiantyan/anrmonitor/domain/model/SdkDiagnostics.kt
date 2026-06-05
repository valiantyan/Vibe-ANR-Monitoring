package com.valiantyan.anrmonitor.domain.model

/**
 * SDK 自身采集状态和成本诊断。
 *
 * @property pendingAvailable Pending 队列采集是否可用。
 * @property reportBuildCostMs 构建报告耗时。
 * @property collectorFailures 各采集器失败摘要，避免静默丢证据。
 */
data class SdkDiagnostics(
    val pendingAvailable: Boolean,
    val reportBuildCostMs: Long,
    val collectorFailures: List<String>,
)
