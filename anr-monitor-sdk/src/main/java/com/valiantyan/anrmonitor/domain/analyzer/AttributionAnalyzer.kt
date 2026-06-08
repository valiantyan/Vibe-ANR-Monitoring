package com.valiantyan.anrmonitor.domain.analyzer

import com.valiantyan.anrmonitor.collector.pending.PendingQueueAnalyzer
import com.valiantyan.anrmonitor.collector.pending.PendingQueueSummary
import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.BarrierEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.BarrierTokenRecord
import com.valiantyan.anrmonitor.domain.model.BinderBlockSnapshot
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord

/**
 * 基础 ANR 归因分析器，按证据确定性从高到低选择主因。
 *
 * @param thresholds 归因规则阈值，默认覆盖 P0 阶段的核心场景。
 */
class AttributionAnalyzer(
    private val thresholds: AttributionThresholds = AttributionThresholds(),
) {
    /**
     * 对单次 [AnrSnapshot] 做基础归因，优先识别确定性更高的 Barrier、消息堆积和跨进程疑似模式。
     *
     * @param snapshot 疑似 ANR 现场快照。
     * @return 可直接进入报告编码和上传链路的归因结果。
     */
    fun analyze(snapshot: AnrSnapshot): AttributionResult {
        val pendingSummary: PendingQueueSummary = PendingQueueAnalyzer.analyze(
            messages = snapshot.pendingQueue.messages,
        )
        val barrierResult: AttributionResult? = analyzeBarrier(
            summary = pendingSummary,
            barrierEvidenceSnapshot = snapshot.barrierEvidenceSnapshot,
            mainThreadFrames = snapshot.mainThreadStack.frames,
        )
        if (barrierResult != null) {
            return withThreadCpuEvidence(
                result = barrierResult,
                snapshot = snapshot,
            )
        }
        val stormResult: AttributionResult? = analyzeMessageStorm(summary = pendingSummary)
        if (stormResult != null) {
            return withThreadCpuEvidence(
                result = stormResult,
                snapshot = snapshot,
            )
        }
        val binderResult: AttributionResult? = analyzeBinderBlock(snapshot = snapshot.binderBlockSnapshot)
        if (binderResult != null) {
            return withThreadCpuEvidence(
                result = binderResult,
                snapshot = snapshot,
            )
        }
        val currentResult: AttributionResult? = analyzeCurrentMessage(current = snapshot.currentMessage)
        if (currentResult != null) {
            return withThreadCpuEvidence(
                result = currentResult,
                snapshot = snapshot,
            )
        }
        val historyResult: AttributionResult? = analyzeHistory(history = snapshot.historyMessages)
        if (historyResult != null) {
            return withThreadCpuEvidence(
                result = historyResult,
                snapshot = snapshot,
            )
        }
        return withThreadCpuEvidence(
            result = unknownResult(snapshot = snapshot),
            snapshot = snapshot,
        )
    }

    // 识别队头同步屏障卡住同步消息的模式，避免把 nativePollOnce 误判成主线程空闲。
    private fun analyzeBarrier(
        summary: PendingQueueSummary,
        barrierEvidenceSnapshot: BarrierEvidenceSnapshot,
        mainThreadFrames: List<String>,
    ): AttributionResult? {
        val firstBlockedMs: Long = summary.firstSynchronousBlockedMs ?: return null
        if (!summary.hasBarrierHead || firstBlockedMs < thresholds.suspectAnrMs) {
            return null
        }
        if (!hasStrongBarrierEvidence(
                barrierEvidenceSnapshot = barrierEvidenceSnapshot,
                mainThreadFrames = mainThreadFrames,
            )
        ) {
            return null
        }
        return result(
            code = AnrAttributionCode.SYNC_BARRIER_STUCK,
            confidence = Confidence.HIGH,
            evidence = listOf(
                "pending queue head is Sync Barrier",
                "first synchronous message blocked ${firstBlockedMs}ms",
            ) + barrierEvidence(snapshot = barrierEvidenceSnapshot),
            suggestion = "检查 postSyncBarrier/removeSyncBarrier 配对和 UI 调度清理逻辑。",
        )
    }

    // 队头临时 Barrier 常见于绘制调度，只有主线程在 nativePollOnce 或 token 对齐时才提升为主因。
    private fun hasStrongBarrierEvidence(
        barrierEvidenceSnapshot: BarrierEvidenceSnapshot,
        mainThreadFrames: List<String>,
    ): Boolean {
        return mainThreadFrames.any { frame: String -> frame.contains(other = NATIVE_POLL_ONCE_FRAME) } ||
            barrierEvidenceSnapshot.alignedWithPendingBarrier ||
            barrierEvidenceSnapshot.stuckTokens.isNotEmpty()
    }

    // 提取 Barrier 增强证据，只辅助审查，不替代 Pending 队列主因。
    private fun barrierEvidence(snapshot: BarrierEvidenceSnapshot): List<String> {
        if (!snapshot.available) {
            return listOf("barrier evidence unavailable: ${snapshot.failureReason}")
        }
        val stuckToken: BarrierTokenRecord? = snapshot.stuckTokens.firstOrNull()
        return listOfNotNull(
            stuckToken?.let { record: BarrierTokenRecord ->
                "barrier stuck token=${record.token} alive=${record.aliveMs}ms"
            },
            "nativePollOnce infiniteWaitCount=${snapshot.repeatedInfinitePollCount} " +
                "alignedWithPendingBarrier=${snapshot.alignedWithPendingBarrier}",
        )
    }

    // 识别当前消息本身消耗了 ANR 窗口的模式，CPU 占比只影响置信度。
    private fun analyzeCurrentMessage(current: MessageRecord?): AttributionResult? {
        if (current == null || current.wallMs < thresholds.suspectAnrMs) {
            return null
        }
        val ratio: Double = current.cpuMs.toDouble() / current.wallMs.coerceAtLeast(minimumValue = 1L).toDouble()
        val confidence: Confidence = if (ratio >= thresholds.highCpuRatio) Confidence.MEDIUM else Confidence.LOW
        return result(
            code = AnrAttributionCode.CURRENT_MESSAGE_SLOW,
            confidence = confidence,
            evidence = listOf("current message wall=${current.wallMs}ms cpu=${current.cpuMs}ms"),
            suggestion = "优化当前消息对应业务逻辑，拆分主线程重计算、同步等待或阻塞 I/O。",
        )
    }

    // 识别历史消息慢导致当前系统栈看不到真正根因的模式。
    private fun analyzeHistory(history: List<MessageRecord>): AttributionResult? {
        val slowMessage: MessageRecord = history.firstOrNull { record ->
            record.wallMs >= thresholds.suspectAnrMs
        } ?: return null
        return result(
            code = AnrAttributionCode.HISTORY_MESSAGE_SLOW,
            confidence = Confidence.MEDIUM,
            evidence = listOf("history message seq=${slowMessage.seq} wall=${slowMessage.wallMs}ms"),
            suggestion = "回看 ANR 前历史消息，而不是只按当前 Trace 派单。",
        )
    }

    // 识别大量同类 Pending 消息堆积导致主线程窗口被挤占的模式。
    private fun analyzeMessageStorm(summary: PendingQueueSummary): AttributionResult? {
        if (summary.repeatedTargetCount < thresholds.messageStormCount) {
            return null
        }
        return result(
            code = AnrAttributionCode.MESSAGE_STORM,
            confidence = Confidence.MEDIUM,
            evidence = listOf("pending repeated target count=${summary.repeatedTargetCount}"),
            suggestion = "合并重复 Handler 消息，增加去重、防抖或队列清理。",
        )
    }

    // 识别 Binder/跨进程阻塞疑似，只输出中等置信度并提示线下复核。
    private fun analyzeBinderBlock(snapshot: BinderBlockSnapshot): AttributionResult? {
        if (!snapshot.available || !snapshot.suspected) {
            return null
        }
        return result(
            code = AnrAttributionCode.BINDER_BLOCK_SUSPECTED,
            confidence = Confidence.MEDIUM,
            evidence = binderEvidence(snapshot = snapshot),
            suggestion = "结合 Perfetto、system trace 或远端进程栈复核 Binder 调用链，端侧只输出疑似。",
        )
    }

    // 提取 Binder 疑似证据，字段命名避免把 suspected 误读为 confirmed。
    private fun binderEvidence(snapshot: BinderBlockSnapshot): List<String> {
        return listOf(
            "main thread blocked in Binder transact",
            "binder thread waits main or lock",
        ) + snapshot.mainThreadEvidence.take(n = 1) + snapshot.binderThreadEvidence.take(n = 1)
    }

    // 在关键证据不足时返回明确的 unknown，帮助后续评审定位采集短板。
    private fun unknownResult(snapshot: AnrSnapshot): AttributionResult {
        val missingEvidence: MutableList<String> = mutableListOf()
        if (!snapshot.pendingQueue.available) {
            missingEvidence += "pending queue unavailable: ${snapshot.pendingQueue.failureReason}"
        }
        if (snapshot.historyMessages.isEmpty()) {
            missingEvidence += "history messages empty"
        }
        if (!snapshot.barrierEvidenceSnapshot.available) {
            missingEvidence += "barrier evidence unavailable: ${snapshot.barrierEvidenceSnapshot.failureReason}"
        }
        if (!snapshot.binderBlockSnapshot.available) {
            missingEvidence += "binder evidence unavailable: ${snapshot.binderBlockSnapshot.failureReason}"
        }
        return AttributionResult(
            primaryCode = AnrAttributionCode.UNKNOWN_INSUFFICIENT_EVIDENCE,
            secondaryCodes = emptyList(),
            confidence = Confidence.UNKNOWN,
            evidenceItems = emptyList(),
            missingEvidence = missingEvidence,
            actionSuggestions = listOf("补齐 Pending、历史消息或主线程栈后重新评估。"),
        )
    }

    // 将线程 CPU TopN 作为辅助证据追加到归因结果，不改变主因判断优先级。
    private fun withThreadCpuEvidence(
        result: AttributionResult,
        snapshot: AnrSnapshot,
    ): AttributionResult {
        val topThread: ThreadCpuRecord = snapshot.threadCpuRecords.firstOrNull() ?: return result
        return result.copy(
            evidenceItems = result.evidenceItems + "top thread ${topThread.threadName} cpu=${topThread.totalCpuMs}ms",
        )
    }

    // 统一构造归因结果，避免各规则遗漏建议或证据字段。
    private fun result(
        code: AnrAttributionCode,
        confidence: Confidence,
        evidence: List<String>,
        suggestion: String,
    ): AttributionResult {
        return AttributionResult(
            primaryCode = code,
            secondaryCodes = emptyList(),
            confidence = confidence,
            evidenceItems = evidence,
            missingEvidence = emptyList(),
            actionSuggestions = listOf(suggestion),
        )
    }

    private companion object {
        /**
         * 主线程栈里用于确认 Sync Barrier 真正挡住 Looper 取消息的稳定片段。
         */
        private const val NATIVE_POLL_ONCE_FRAME: String = "MessageQueue.nativePollOnce"
    }
}
