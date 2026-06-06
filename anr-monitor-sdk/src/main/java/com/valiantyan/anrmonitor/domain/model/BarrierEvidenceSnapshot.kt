package com.valiantyan.anrmonitor.domain.model

/**
 * Barrier token 与 [nativePollOnce] 增强证据快照。
 *
 * @property available 增强采集是否可用。
 * @property stuckTokens 超过阈值仍未移除的 Barrier token。
 * @property recentNativePollOnceRecords 最近 [nativePollOnce] 进入/退出记录。
 * @property repeatedInfinitePollCount 最近窗口内 [timeoutMillis=-1] 的次数。
 * @property alignedWithPendingBarrier 增强证据是否能和 Pending 队头 Barrier 对齐。
 * @property failureReason 不可用时的失败或降级原因。
 */
data class BarrierEvidenceSnapshot(
    val available: Boolean,
    val stuckTokens: List<BarrierTokenRecord>,
    val recentNativePollOnceRecords: List<NativePollOnceRecord>,
    val repeatedInfinitePollCount: Int,
    val alignedWithPendingBarrier: Boolean,
    val failureReason: String?,
) {
    /**
     * Barrier 证据辅助构造器，统一表达空证据和不可用状态。
     */
    companion object {
        /**
         * 创建可用但暂无增强证据的快照。
         *
         * @return 可用于报告协议占位的空快照。
         */
        fun empty(): BarrierEvidenceSnapshot {
            return BarrierEvidenceSnapshot(
                available = true,
                stuckTokens = emptyList(),
                recentNativePollOnceRecords = emptyList(),
                repeatedInfinitePollCount = 0,
                alignedWithPendingBarrier = false,
                failureReason = null,
            )
        }

        /**
         * 创建不可用快照，保留降级原因以便评审确认风险能力未静默缺席。
         *
         * @param reason 增强采集关闭或失败的原因。
         * @return 不包含增强证据的不可用快照。
         */
        fun unavailable(reason: String): BarrierEvidenceSnapshot {
            return BarrierEvidenceSnapshot(
                available = false,
                stuckTokens = emptyList(),
                recentNativePollOnceRecords = emptyList(),
                repeatedInfinitePollCount = 0,
                alignedWithPendingBarrier = false,
                failureReason = reason,
            )
        }
    }
}

/**
 * Barrier token 生命周期记录。
 *
 * @property token [postSyncBarrier] 返回的 token。
 * @property postUptimeMs 插入 Barrier 的 uptime。
 * @property removeUptimeMs 移除 Barrier 的 uptime，未移除时为空。
 * @property aliveMs Barrier 已存活或曾存活的时长。
 * @property postStack 插入 Barrier 时的调用栈，需在采集前完成脱敏。
 */
data class BarrierTokenRecord(
    val token: Int,
    val postUptimeMs: Long,
    val removeUptimeMs: Long?,
    val aliveMs: Long,
    val postStack: List<String>,
)

/**
 * 单次 [nativePollOnce] 调用窗口。
 *
 * @property timeoutMillis 传入 [nativePollOnce] 的超时时间，-1 表示无限等待。
 * @property enterUptimeMs 进入调用时的 uptime。
 * @property exitUptimeMs 退出调用时的 uptime，仍在调用中时为空。
 * @property durationMs 已完成调用的耗时，仍在调用中时为空。
 */
data class NativePollOnceRecord(
    val timeoutMillis: Int,
    val enterUptimeMs: Long,
    val exitUptimeMs: Long?,
    val durationMs: Long?,
) {
    /**
     * 是否为无限等待轮询，不能简单视为空闲。
     */
    val isInfiniteWait: Boolean = timeoutMillis < 0

    /**
     * 是否仍在 [nativePollOnce] 调用中。
     */
    val isInFlight: Boolean = exitUptimeMs == null
}
