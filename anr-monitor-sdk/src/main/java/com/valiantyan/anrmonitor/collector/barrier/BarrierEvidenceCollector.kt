package com.valiantyan.anrmonitor.collector.barrier

import com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitor
import com.valiantyan.anrmonitor.domain.model.BarrierEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.BarrierTokenRecord
import com.valiantyan.anrmonitor.domain.model.NativePollOnceRecord
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
     * @return 可写入 [AnrSnapshot] 的增强证据快照。
     */
    fun collect(
        enabled: Boolean,
        nowUptimeMs: Long,
        stuckThresholdMs: Long,
        maxRecords: Int,
        pendingQueue: PendingQueueSnapshot,
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
        )
        val infinitePollCount: Int = nativePollOnceMonitor.countRecentInfiniteWaits(
            maxRecords = safeMaxRecords,
        )
        return BarrierEvidenceSnapshot(
            available = true,
            stuckTokens = stuckTokens,
            recentNativePollOnceRecords = nativePollRecords,
            repeatedInfinitePollCount = infinitePollCount,
            alignedWithPendingBarrier = hasAlignedPendingBarrier(
                stuckTokens = stuckTokens,
                infinitePollCount = infinitePollCount,
                pendingQueue = pendingQueue,
            ),
            failureReason = null,
        )
    }

    // 判断增强证据是否能和 Pending 队头屏障互相印证。
    private fun hasAlignedPendingBarrier(
        stuckTokens: List<BarrierTokenRecord>,
        infinitePollCount: Int,
        pendingQueue: PendingQueueSnapshot,
    ): Boolean {
        val headMessage: PendingMessage = pendingQueue.messages.firstOrNull() ?: return false
        if (!pendingQueue.available || !headMessage.isBarrierLike || infinitePollCount <= 0) {
            return false
        }
        return stuckTokens.any { record: BarrierTokenRecord -> record.token == headMessage.arg1 }
    }
}
