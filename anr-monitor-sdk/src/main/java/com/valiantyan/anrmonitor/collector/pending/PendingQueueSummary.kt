package com.valiantyan.anrmonitor.collector.pending

/**
 * Pending 队列摘要，用于把队列快照压缩成可供归因规则直接消费的证据。
 *
 * @property totalCount 已采集的 Pending 消息数量。
 * @property hasBarrierHead 队头是否疑似同步屏障。
 * @property barrierToken 队头屏障 token，非屏障队头时为空。
 * @property firstSynchronousBlockedMs 首个同步消息已阻塞时长，未发现时为空。
 * @property repeatedTargetCount 同一 target 或 callback 出现的最大次数。
 */
data class PendingQueueSummary(
    val totalCount: Int,
    val hasBarrierHead: Boolean,
    val barrierToken: Int?,
    val firstSynchronousBlockedMs: Long?,
    val repeatedTargetCount: Int,
)
