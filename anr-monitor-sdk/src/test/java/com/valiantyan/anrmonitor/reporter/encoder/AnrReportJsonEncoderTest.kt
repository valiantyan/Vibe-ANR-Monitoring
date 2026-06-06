package com.valiantyan.anrmonitor.reporter.encoder

import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.SdkDiagnostics
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [AnrReportJsonEncoder] 的稳定输出字段，避免新增证据时丢失报告出口。
 */
class AnrReportJsonEncoderTest {
    /**
     * 报告编码必须包含基础字段并继续避免暴露 [android.os.Message.obj] 内容。
     */
    @Test
    fun encodeIncludesSchemaAndDoesNotExposeMessageObjectContent(): Unit {
        val report: AnrReport = AnrReport(
            schemaVersion = 1,
            snapshot = AnrSnapshot(
                eventId = "event-1",
                eventType = AnrEventType.SUSPECT_ANR,
                appId = "demo",
                environment = "test",
                timeUptimeMs = 123L,
                currentMessage = null,
                historyMessages = emptyList(),
                pendingQueue = PendingQueueSnapshot.unavailable(
                    maxDepth = 200,
                    failureReason = "reflection failed",
                ),
                mainThreadStack = StackTraceSnapshot(
                    stackId = "main",
                    threadName = "main",
                    frames = listOf("com.example.Feature.render(Feature.kt:20)"),
                ),
                threadCpuRecords = emptyList(),
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
                secondaryCodes = emptyList(),
                confidence = Confidence.UNKNOWN,
                evidenceItems = emptyList(),
                missingEvidence = listOf("pending queue unavailable"),
                actionSuggestions = listOf("capture more evidence"),
            ),
            diagnostics = SdkDiagnostics(
                pendingAvailable = false,
                reportBuildCostMs = 12L,
                collectorFailures = listOf("PendingQueueSnapshotter"),
            ),
        )

        val json: String = AnrReportJsonEncoder().encode(report = report)

        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"primary\":\"UNKNOWN_INSUFFICIENT_EVIDENCE\""))
        assertFalse(json.contains("obj="))
    }

    /**
     * 线程 CPU TopN 是进程内资源证据，必须进入 JSON 方便后续平台聚类和人工复核。
     */
    @Test
    fun encodeIncludesThreadCpuRecords(): Unit {
        val report: AnrReport = AnrReport(
            schemaVersion = 1,
            snapshot = AnrSnapshot(
                eventId = "event-1",
                eventType = AnrEventType.SUSPECT_ANR,
                appId = "demo",
                environment = "test",
                timeUptimeMs = 123L,
                currentMessage = null,
                historyMessages = emptyList(),
                pendingQueue = PendingQueueSnapshot.unavailable(
                    maxDepth = 200,
                    failureReason = "reflection failed",
                ),
                mainThreadStack = StackTraceSnapshot(
                    stackId = "main",
                    threadName = "main",
                    frames = emptyList(),
                ),
                threadCpuRecords = listOf(
                    ThreadCpuRecord(
                        tid = 2,
                        threadName = "io-worker",
                        totalCpuMs = 100L,
                    ),
                ),
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
                secondaryCodes = emptyList(),
                confidence = Confidence.UNKNOWN,
                evidenceItems = emptyList(),
                missingEvidence = emptyList(),
                actionSuggestions = emptyList(),
            ),
            diagnostics = SdkDiagnostics(
                pendingAvailable = false,
                reportBuildCostMs = 12L,
                collectorFailures = emptyList(),
            ),
        )

        val json: String = AnrReportJsonEncoder().encode(report = report)

        assertTrue(json.contains("\"threadCpu\""))
        assertTrue(json.contains("\"threadName\":\"io-worker\""))
        assertTrue(json.contains("\"totalCpuMs\":100"))
    }
}
