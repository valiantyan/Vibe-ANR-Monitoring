package com.valiantyan.anrmonitor.domain.model

/**
 * SDK 输出给本地文件或宿主上传链路的完整报告。
 *
 * @property schemaVersion 报告协议版本。
 * @property snapshot ANR 现场证据。
 * @property attribution 归因结果。
 * @property diagnostics SDK 自诊断信息。
 */
data class AnrReport(
    val schemaVersion: Int,
    val snapshot: AnrSnapshot,
    val attribution: AttributionResult,
    val diagnostics: SdkDiagnostics,
) {
    /**
     * 报告辅助构造器。
     */
    companion object {
        /**
         * 创建一份空报告，用于上传链路、编码器或 demo 接线的占位验证。
         *
         * @param appId 宿主应用标识。
         * @param environment 运行环境标识。
         * @return 证据为空且置信度未知的报告。
         */
        fun empty(
            appId: String,
            environment: String,
        ): AnrReport {
            val snapshot = AnrSnapshot(
                eventId = "empty",
                eventType = AnrEventType.SUSPECT_ANR,
                appId = appId,
                environment = environment,
                timeUptimeMs = 0L,
                currentMessage = null,
                historyMessages = emptyList(),
                pendingQueue = PendingQueueSnapshot.unavailable(
                    maxDepth = 0,
                    failureReason = "empty report",
                ),
                mainThreadStack = StackTraceSnapshot(
                    stackId = "empty-main",
                    threadName = "main",
                    frames = emptyList(),
                ),
                threadCpuRecords = emptyList(),
            )
            return AnrReport(
                schemaVersion = 1,
                snapshot = snapshot,
                attribution = AttributionResult(
                    primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
                    secondaryCodes = emptyList(),
                    confidence = Confidence.UNKNOWN,
                    evidenceItems = emptyList(),
                    missingEvidence = listOf("empty report"),
                    actionSuggestions = emptyList(),
                ),
                diagnostics = SdkDiagnostics(
                    pendingAvailable = false,
                    reportBuildCostMs = 0L,
                    collectorFailures = emptyList(),
                ),
            )
        }
    }
}
