package com.valiantyan.anrmonitor.domain.model

/**
 * 主线程 Pending 队列快照，包含反射可用性和截断信息。
 *
 * @property available 当前设备和系统版本是否允许读取队列。
 * @property truncated 队列是否因 [maxDepth] 限制被截断。
 * @property maxDepth 本次采集允许的最大深度。
 * @property messages 已采集到的队列消息。
 * @property failureReason 不可用时的失败原因。
 */
data class PendingQueueSnapshot(
    val available: Boolean,
    val truncated: Boolean,
    val maxDepth: Int,
    val messages: List<PendingMessage>,
    val failureReason: String?,
) {
    /**
     * 构造不可用快照，保留失败原因以便评估证据缺口。
     */
    companion object {
        /**
         * 创建不可用的 Pending 队列快照。
         *
         * @param maxDepth 本次计划采集的最大深度。
         * @param failureReason 反射失败或策略关闭的原因。
         * @return 不包含消息的不可用快照。
         */
        fun unavailable(
            maxDepth: Int,
            failureReason: String,
        ): PendingQueueSnapshot {
            return PendingQueueSnapshot(
                available = false,
                truncated = false,
                maxDepth = maxDepth,
                messages = emptyList(),
                failureReason = failureReason,
            )
        }
    }
}
