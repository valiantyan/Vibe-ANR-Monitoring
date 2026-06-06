package com.valiantyan.anrmonitor.reporter.encoder

import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrInfoSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrReport
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrType
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.ChecktimeSummary
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.EnvironmentEvidenceAvailability
import com.valiantyan.anrmonitor.domain.model.MemorySnapshot
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.ProcessIoSnapshot
import com.valiantyan.anrmonitor.domain.model.SdkDiagnostics
import com.valiantyan.anrmonitor.domain.model.QueuedWorkBypassState
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesFileStat
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationRecord
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesOperationType
import com.valiantyan.anrmonitor.domain.model.SharedPreferencesSnapshot
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
     * SharedPreferences 专项证据需要进入 JSON，供评审查看文件健康、写入成本和治理状态。
     */
    @Test
    fun encodeIncludesSharedPreferencesEvidence(): Unit {
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
                    frames = listOf("android.app.QueuedWork.waitToFinish(QueuedWork.java:180)"),
                ),
                sharedPreferencesSnapshot = SharedPreferencesSnapshot(
                    available = true,
                    topFiles = listOf(
                        SharedPreferencesFileStat(
                            fileName = "settings.xml",
                            sizeBytes = 2_048L,
                            keyCount = 80,
                            firstLoadCostMs = 450L,
                            applyCount = 2,
                            commitCount = 1,
                            lastWriteCostMs = 90L,
                        ),
                    ),
                    recentOperations = listOf(
                        SharedPreferencesOperationRecord(
                            fileName = "settings.xml",
                            operationType = SharedPreferencesOperationType.APPLY,
                            costMs = 32L,
                            timestampUptimeMs = 1_200L,
                            threadName = "main",
                            stackFrames = listOf("com.example.MainActivity.onStop(MainActivity.kt:30)"),
                            success = true,
                            pendingFinisherCount = 3,
                        ),
                    ),
                    pendingFinisherCount = 3,
                    queuedWorkBypass = QueuedWorkBypassState(
                        enabled = false,
                        allowedFiles = emptySet(),
                        blockedFiles = setOf("auth.xml"),
                        rollbackEnabled = false,
                    ),
                    failureReason = null,
                ),
            ),
            attribution = AttributionResult(
                primaryCode = AnrAttributionCode.SP_APPLY_WAIT,
                secondaryCodes = emptyList(),
                confidence = Confidence.HIGH,
                evidenceItems = listOf("main stack contains QueuedWork.waitToFinish"),
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

        assertTrue(json.contains("\"sharedPreferences\""))
        assertTrue(json.contains("\"fileName\":\"settings.xml\""))
        assertTrue(json.contains("\"sizeBytes\":2048"))
        assertTrue(json.contains("\"keyCount\":80"))
        assertTrue(json.contains("\"firstLoadCostMs\":450"))
        assertTrue(json.contains("\"applyCount\":2"))
        assertTrue(json.contains("\"commitCount\":1"))
        assertTrue(json.contains("\"operationType\":\"APPLY\""))
        assertTrue(json.contains("\"pendingFinisherCount\":3"))
        assertTrue(json.contains("\"queuedWorkBypass\""))
        assertTrue(json.contains("\"rollbackEnabled\":false"))
    }
}
