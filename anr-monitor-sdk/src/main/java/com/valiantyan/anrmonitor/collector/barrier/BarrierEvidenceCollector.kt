package com.valiantyan.anrmonitor.collector.barrier

import com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitor
import com.valiantyan.anrmonitor.domain.model.BarrierEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.BarrierTokenRecord
import com.valiantyan.anrmonitor.domain.model.NativePollOnceRecord
import com.valiantyan.anrmonitor.domain.model.NativePollOnceRecordSource
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot

/**
 * 聚合 Barrier token、[nativePollOnce] 和 Pending 队列的增强证据。
 *
 * @param tokenTracker Barrier token 生命周期追踪器。
 * @param nativePollOnceMonitor [nativePollOnce] 调用窗口监控器。
 */
class BarrierEvidenceCollector(
    private val tokenTracker: BarrierTokenTracker,
    private val nativePollOnceMonitor: NativePollOnceMonitor,
) {
    /**
     * 采集 Barrier 增强证据；关闭时明确返回不可用，避免高风险能力静默启用。
     *
     * @param enabled 增强采集开关。
     * @param nowUptimeMs 当前采集 uptime。
     * @param stuckThresholdMs token 卡住阈值。
     * @param maxRecords 最近记录窗口大小。
     * @param pendingQueue Pending 队列快照，用于和队头 Barrier 对齐。
     * @param mainThreadFrames 疑似 ANR 时刻主线程栈，用于低风险推断 [nativePollOnce] 现场。
     * @return 可写入 [AnrSnapshot] 的增强证据快照。
     */
    fun collect(
        enabled: Boolean,
        nowUptimeMs: Long,
        stuckThresholdMs: Long,
        maxRecords: Int,
        pendingQueue: PendingQueueSnapshot,
        mainThreadFrames: List<String> = emptyList(),
    ): BarrierEvidenceSnapshot {
        if (!enabled) {
            return BarrierEvidenceSnapshot.unavailable(reason = "barrier evidence capture disabled")
        }
        val safeMaxRecords: Int = maxRecords.coerceAtLeast(minimumValue = 0)
        val stuckTokens: List<BarrierTokenRecord> = tokenTracker.findStuckTokens(
            nowUptimeMs = nowUptimeMs,
            thresholdMs = stuckThresholdMs,
        ).take(n = safeMaxRecords)
        val nativePollRecords: List<NativePollOnceRecord> = nativePollOnceMonitor.recentRecords(
            maxRecords = safeMaxRecords,
        ).withInferredNativePollOnceRecord(
            nowUptimeMs = nowUptimeMs,
            stuckTokens = stuckTokens,
            pendingQueue = pendingQueue,
            mainThreadFrames = mainThreadFrames,
        ).takeLast(n = safeMaxRecords)
        val infinitePollCount: Int = nativePollRecords.count { record: NativePollOnceRecord ->
            record.isInfiniteWait
        }
        return BarrierEvidenceSnapshot(
            available = true,
            stuckTokens = stuckTokens,
            recentNativePollOnceRecords = nativePollRecords,
            repeatedInfinitePollCount = infinitePollCount,
            alignedWithPendingBarrier = hasAlignedPendingBarrier(
                stuckTokens = stuckTokens,
                pendingQueue = pendingQueue,
            ),
            failureReason = null,
        )
    }

    // 没有 hook in-flight 记录时，用主线程栈和队头 Barrier 生成低风险 nativePollOnce 现场证据。
    private fun List<NativePollOnceRecord>.withInferredNativePollOnceRecord(
        nowUptimeMs: Long,
        stuckTokens: List<BarrierTokenRecord>,
        pendingQueue: PendingQueueSnapshot,
        mainThreadFrames: List<String>,
    ): List<NativePollOnceRecord> {
        if (any { record: NativePollOnceRecord -> record.isInFlight }) {
            return this
        }
        val inferredRecord: NativePollOnceRecord = inferNativePollOnceRecord(
            nowUptimeMs = nowUptimeMs,
            stuckTokens = stuckTokens,
            pendingQueue = pendingQueue,
            mainThreadFrames = mainThreadFrames,
        ) ?: return this
        return this + inferredRecord
    }

    // 仅在 nativePollOnce 栈和队头 Sync Barrier 同时成立时推断无限等待，避免普通空闲被误读。
    private fun inferNativePollOnceRecord(
        nowUptimeMs: Long,
        stuckTokens: List<BarrierTokenRecord>,
        pendingQueue: PendingQueueSnapshot,
        mainThreadFrames: List<String>,
    ): NativePollOnceRecord? {
        if (!mainThreadFrames.any { frame: String -> frame.contains(other = NATIVE_POLL_ONCE_FRAME) }) {
            return null
        }
        val headMessage: PendingMessage = pendingQueue.messages.firstOrNull() ?: return null
        if (!pendingQueue.available || !headMessage.isBarrierLike) {
            return null
        }
        return NativePollOnceRecord(
            timeoutMillis = INFINITE_WAIT_TIMEOUT_MS,
            enterUptimeMs = estimateNativePollEnterUptimeMs(
                nowUptimeMs = nowUptimeMs,
                stuckTokens = stuckTokens,
                headMessage = headMessage,
            ),
            exitUptimeMs = null,
            durationMs = null,
            source = NativePollOnceRecordSource.STACK_INFERENCE,
        )
    }

    // 推断记录只表达“采样时仍在等待”，进入时间用 token 插入时间或队头时间做保守估计。
    private fun estimateNativePollEnterUptimeMs(
        nowUptimeMs: Long,
        stuckTokens: List<BarrierTokenRecord>,
        headMessage: PendingMessage,
    ): Long {
        val matchingToken: BarrierTokenRecord? = stuckTokens.firstOrNull { record: BarrierTokenRecord ->
            record.token == headMessage.arg1
        }
        val candidateUptimeMs: Long = matchingToken?.postUptimeMs ?: headMessage.whenUptimeMs
        return candidateUptimeMs.coerceAtMost(maximumValue = nowUptimeMs)
    }

    // 判断 token 证据是否能和 Pending 队头屏障互相印证，nativePollOnce 只作为额外增强证据。
    private fun hasAlignedPendingBarrier(
        stuckTokens: List<BarrierTokenRecord>,
        pendingQueue: PendingQueueSnapshot,
    ): Boolean {
        val headMessage: PendingMessage = pendingQueue.messages.firstOrNull() ?: return false
        if (!pendingQueue.available || !headMessage.isBarrierLike) {
            return false
        }
        return stuckTokens.any { record: BarrierTokenRecord -> record.token == headMessage.arg1 }
    }

    private companion object {
        /**
         * 主线程栈里用于识别 nativePollOnce 现场的稳定片段。
         */
        private const val NATIVE_POLL_ONCE_FRAME: String = "MessageQueue.nativePollOnce"

        /**
         * Android nativePollOnce 中 -1 表示无限等待。
         */
        private const val INFINITE_WAIT_TIMEOUT_MS: Int = -1
    }
}
