package com.valiantyan.anrmonitor.reporter.encoder

import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrInfoSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrType
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.BarrierEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.BarrierTokenRecord
import com.valiantyan.anrmonitor.domain.model.BinderBlockSnapshot
import com.valiantyan.anrmonitor.domain.model.ChecktimeSummary
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.EnvironmentEvidenceAvailability
import com.valiantyan.anrmonitor.domain.model.MemorySnapshot
import com.valiantyan.anrmonitor.domain.model.NativePollOnceRecord
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.ProcessIoSnapshot
import com.valiantyan.anrmonitor.domain.model.SdkDiagnostics
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import com.valiantyan.anrmonitor.domain.model.SystemEnvironmentSnapshot
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
        assertFalse(json.contains("\"sharedPreferences\""))
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

    /**
     * Checktime 和系统环境是判断外部负载的重要辅助证据，必须进入 JSON 报告。
     */
    @Test
    fun encodeIncludesChecktimeAndSystemEnvironment(): Unit {
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
                threadCpuRecords = emptyList(),
                checktimeSummary = ChecktimeSummary(
                    maxDelayMs = 900L,
                    severeDelayCount = 1,
                    recentDelayMs = listOf(20L, 900L),
                ),
                environmentSnapshot = SystemEnvironmentSnapshot(
                    loadAverage1m = 2.5,
                    memory = MemorySnapshot(
                        availableBytes = 512L,
                        totalBytes = 1_024L,
                        isLowMemory = false,
                    ),
                    availableStorageBytes = 4_096L,
                    processIo = ProcessIoSnapshot(
                        readBytes = 10L,
                        writeBytes = 20L,
                        cancelledWriteBytes = 0L,
                    ),
                    androidVersion = "14",
                    manufacturer = "Google",
                    model = "Pixel",
                    availability = EnvironmentEvidenceAvailability(
                        cpuLoadAvailable = true,
                        memoryAvailable = true,
                        storageAvailable = true,
                        processIoAvailable = true,
                    ),
                    failureReasons = emptyList(),
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

        assertTrue(json.contains("\"checktime\""))
        assertTrue(json.contains("\"maxDelayMs\":900"))
        assertTrue(json.contains("\"environmentSnapshot\""))
        assertTrue(json.contains("\"loadAverage1m\":2.5"))
        assertTrue(json.contains("\"processIoAvailable\":true"))
        assertTrue(json.contains("\"writeBytes\":20"))
    }

    /**
     * 系统确认 ANR 和组件阈值必须进入报告，但不能替代真正的归因字段。
     */
    @Test
    fun encodeIncludesSystemAnrInfoAndComponentTimeout(): Unit {
        val report: AnrReport = AnrReport(
            schemaVersion = 1,
            snapshot = AnrSnapshot(
                eventId = "event-1",
                eventType = AnrEventType.CONFIRMED_ANR,
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
                anrInfo = AnrInfoSnapshot(
                    available = true,
                    isConfirmedAnr = true,
                    anrType = AnrType.INPUT,
                    shortMsg = "Input dispatching timed out",
                    longMsg = "Input dispatching timed out waiting for com.example/.MainActivity",
                    condition = 2,
                    failureReason = null,
                ),
                componentTimeoutMs = 5_000L,
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

        assertTrue(json.contains("\"systemAnr\""))
        assertTrue(json.contains("\"isConfirmedAnr\":true"))
        assertTrue(json.contains("\"anrType\":\"INPUT\""))
        assertTrue(json.contains("\"componentTimeoutMs\":5000"))
        assertTrue(json.contains("\"condition\":2"))
        assertTrue(json.contains("\"shortMsg\":\"Input dispatching timed out\""))
        assertTrue(json.contains("\"primary\":\"UNKNOWN_INSUFFICIENT_EVIDENCE\""))
    }

    /**
     * Barrier token 和 [nativePollOnce] 增强证据必须进入 JSON，作为第 4 篇专项评审入口。
     */
    @Test
    fun encodeIncludesBarrierEvidence(): Unit {
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
                    frames = listOf("android.os.MessageQueue.nativePollOnce(Native Method)"),
                ),
                barrierEvidenceSnapshot = BarrierEvidenceSnapshot(
                    available = true,
                    stuckTokens = listOf(
                        BarrierTokenRecord(
                            token = 41,
                            postUptimeMs = 1_000L,
                            removeUptimeMs = null,
                            aliveMs = 6_000L,
                            postStack = listOf("postSyncBarrier token=41"),
                        ),
                    ),
                    recentNativePollOnceRecords = listOf(
                        NativePollOnceRecord(
                            timeoutMillis = -1,
                            enterUptimeMs = 4_000L,
                            exitUptimeMs = 4_300L,
                            durationMs = 300L,
                        ),
                    ),
                    repeatedInfinitePollCount = 2,
                    alignedWithPendingBarrier = true,
                    failureReason = null,
                ),
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.SYNC_BARRIER_STUCK,
                secondaryCodes = emptyList(),
                confidence = Confidence.HIGH,
                evidenceItems = listOf("pending queue head is Sync Barrier"),
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

        assertTrue(json.contains("\"barrierEvidence\""))
        assertTrue(json.contains("\"token\":41"))
        assertTrue(json.contains("\"aliveMs\":6000"))
        assertTrue(json.contains("\"postStack\":[\"postSyncBarrier token=41\"]"))
        assertTrue(json.contains("\"timeoutMillis\":-1"))
        assertTrue(json.contains("\"durationMs\":300"))
        assertTrue(json.contains("\"repeatedInfinitePollCount\":2"))
        assertTrue(json.contains("\"alignedWithPendingBarrier\":true"))
    }

    /**
     * Binder 疑似证据必须进入 JSON，但字段名保持 suspected，避免误读为强确认。
     */
    @Test
    fun encodeIncludesBinderBlockSuspectedEvidence(): Unit {
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
                    frames = listOf("android.os.BinderProxy.transactNative(Native Method)"),
                ),
                binderBlockSnapshot = BinderBlockSnapshot(
                    available = true,
                    suspected = true,
                    mainThreadInBinder = true,
                    binderThreadWaitsMain = true,
                    mainThreadEvidence = listOf("android.os.BinderProxy.transactNative(Native Method)"),
                    binderThreadEvidence = listOf("com.example.Service.waitMainThread(Service.kt:10)"),
                    failureReason = null,
                ),
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.BINDER_BLOCK_SUSPECTED,
                secondaryCodes = emptyList(),
                confidence = Confidence.MEDIUM,
                evidenceItems = listOf("main thread blocked in Binder transact"),
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

        assertTrue(json.contains("\"binderBlock\""))
        assertTrue(json.contains("\"suspected\":true"))
        assertTrue(json.contains("\"mainThreadInBinder\":true"))
        assertTrue(json.contains("\"binderThreadWaitsMain\":true"))
        assertTrue(json.contains("\"mainThreadEvidence\":[\"android.os.BinderProxy.transactNative(Native Method)\"]"))
        assertTrue(json.contains("\"binderThreadEvidence\":[\"com.example.Service.waitMainThread(Service.kt:10)\"]"))
        assertFalse(json.contains("\"confirmedDeadlock\":true"))
    }

    /**
     * SDK 自诊断需要输出隐私模式、缺失证据数量和自监指标，方便评审报告质量。
     */
    @Test
    fun encodeIncludesSdkSelfMetricsAndPrivacyDiagnostics(): Unit {
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
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
                secondaryCodes = emptyList(),
                confidence = Confidence.UNKNOWN,
                evidenceItems = emptyList(),
                missingEvidence = listOf("pending queue unavailable", "system trace unavailable"),
                actionSuggestions = emptyList(),
            ),
            diagnostics = SdkDiagnostics(
                pendingAvailable = false,
                reportBuildCostMs = 12L,
                collectorFailures = listOf("reflection failed"),
                privacyMode = "STRICT",
                missingEvidenceCount = 2,
                selfMetrics = mapOf(
                    "report_queue_enqueued" to 1L,
                    "report_queue_failure" to 2L,
                ),
            ),
        )

        val json: String = AnrReportJsonEncoder().encode(report = report)

        assertTrue(json.contains("\"sdkDiagnostics\""))
        assertTrue(json.contains("\"privacyMode\":\"STRICT\""))
        assertTrue(json.contains("\"missingEvidenceCount\":2"))
        assertTrue(json.contains("\"selfMetrics\""))
        assertTrue(json.contains("\"name\":\"report_queue_enqueued\""))
        assertTrue(json.contains("\"count\":1"))
        assertFalse(json.contains("system trace raw content"))
    }
}
