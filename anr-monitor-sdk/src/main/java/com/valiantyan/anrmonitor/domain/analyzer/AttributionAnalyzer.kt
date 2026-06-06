package com.valiantyan.anrmonitor.domain.analyzer

import com.valiantyan.anrmonitor.collector.pending.PendingQueueAnalyzer
import com.valiantyan.anrmonitor.collector.pending.PendingQueueSummary
import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.AttributionResult
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.MessageRecord

/**
 * 基础 ANR 归因分析器，按证据确定性从高到低选择主因。
 *
 * @param thresholds 归因规则阈值，默认覆盖 P0 阶段的核心场景。
 */
class AttributionAnalyzer(
    private val thresholds: AttributionThresholds = AttributionThresholds(),
) {
    /**
     * 对单次 [AnrSnapshot] 做基础归因，优先识别资料中确定性更高的 SP 和 Barrier 模式。
     *
     * @param snapshot 疑似 ANR 现场快照。
     * @return 可直接进入报告编码和上传链路的归因结果。
     */
    fun analyze(snapshot: AnrSnapshot): AttributionResult {
        val frames: List<String> = snapshot.mainThreadStack.frames
        val spResult: AttributionResult? = analyzeSharedPreferences(frames = frames)
        if (spResult != null) {
            return spResult
        }
        val pendingSummary: PendingQueueSummary = PendingQueueAnalyzer.analyze(
            messages = snapshot.pendingQueue.messages,
        )
        val barrierResult: AttributionResult? = analyzeBarrier(summary = pendingSummary)
        if (barrierResult != null) {
            return barrierResult
        }
        val currentResult: AttributionResult? = analyzeCurrentMessage(current = snapshot.currentMessage)
        if (currentResult != null) {
            return currentResult
        }
        val historyResult: AttributionResult? = analyzeHistory(history = snapshot.historyMessages)
        if (historyResult != null) {
            return historyResult
        }
        val stormResult: AttributionResult? = analyzeMessageStorm(summary = pendingSummary)
        if (stormResult != null) {
            return stormResult
        }
        return unknownResult(snapshot = snapshot)
    }

    // 识别 SharedPreferences 加载等待和 apply 落盘等待，二者在主线程栈中证据最直接。
    private fun analyzeSharedPreferences(frames: List<String>): AttributionResult? {
        val joinedFrames: String = frames.joinToString(separator = "\n")
        if (joinedFrames.contains(other = "SharedPreferencesImpl.awaitLoadedLocked")) {
            return result(
                code = AnrAttributionCode.SP_LOAD_WAIT,
                confidence = Confidence.HIGH,
                evidence = listOf("main stack contains SharedPreferencesImpl.awaitLoadedLocked"),
                suggestion = "将首次读取前置到后台线程，拆分过大的 shared_prefs 文件。",
            )
        }
        if (joinedFrames.contains(other = "QueuedWork.waitToFinish") || joinedFrames.contains(other = "writtenToDiskLatch.await")) {
            return result(
                code = AnrAttributionCode.SP_APPLY_WAIT,
                confidence = Confidence.HIGH,
                evidence = listOf("main stack contains QueuedWork.waitToFinish or writtenToDiskLatch.await"),
                suggestion = "治理生命周期边界前的高频 apply，强一致数据不要跳过等待。",
            )
        }
        return null
    }

    // 识别队头同步屏障卡住同步消息的模式，避免把 nativePollOnce 误判成主线程空闲。
    private fun analyzeBarrier(summary: PendingQueueSummary): AttributionResult? {
        val firstBlockedMs: Long = summary.firstSynchronousBlockedMs ?: return null
        if (!summary.hasBarrierHead || firstBlockedMs < thresholds.suspectAnrMs) {
            return null
        }
        return result(
            code = AnrAttributionCode.SYNC_BARRIER_STUCK,
            confidence = Confidence.HIGH,
            evidence = listOf(
                "pending queue head is Sync Barrier",
                "first synchronous message blocked ${firstBlockedMs}ms",
            ),
            suggestion = "检查 postSyncBarrier/removeSyncBarrier 配对和 UI 调度清理逻辑。",
        )
    }

    // 识别当前消息本身消耗了 ANR 窗口且 CPU 占比较高的模式。
    private fun analyzeCurrentMessage(current: MessageRecord?): AttributionResult? {
        if (current == null || current.wallMs < thresholds.suspectAnrMs) {
            return null
        }
        val ratio: Double = current.cpuMs.toDouble() / current.wallMs.coerceAtLeast(minimumValue = 1L).toDouble()
        if (ratio < thresholds.highCpuRatio) {
            return null
        }
        return result(
            code = AnrAttributionCode.CURRENT_MESSAGE_SLOW,
            confidence = Confidence.MEDIUM,
            evidence = listOf("current message wall=${current.wallMs}ms cpu=${current.cpuMs}ms"),
            suggestion = "优化当前消息对应业务逻辑，拆分主线程重计算或同步等待。",
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

    // 在关键证据不足时返回明确的 unknown，帮助后续评审定位采集短板。
    private fun unknownResult(snapshot: AnrSnapshot): AttributionResult {
        val missingEvidence: MutableList<String> = mutableListOf()
        if (!snapshot.pendingQueue.available) {
            missingEvidence += "pending queue unavailable: ${snapshot.pendingQueue.failureReason}"
        }
        if (snapshot.historyMessages.isEmpty()) {
            missingEvidence += "history messages empty"
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
}
