package com.valiantyan.anrmonitor.collector.pending

import com.valiantyan.anrmonitor.domain.model.PendingMessage

/**
 * Pending 队列分析器，将原始队列消息提炼成 Barrier 和阻塞相关摘要。
 */
object PendingQueueAnalyzer {
    /**
     * 分析 Pending 消息列表，优先保留队头屏障、首个同步消息阻塞时长和重复 target 证据。
     *
     * @param messages 采集到的 Pending 队列消息，按队列顺序排列。
     * @return 可供归因规则消费的队列摘要。
     */
    fun analyze(messages: List<PendingMessage>): PendingQueueSummary {
        val head: PendingMessage? = messages.firstOrNull()
        val firstSynchronous: PendingMessage? = messages.firstOrNull { message ->
            !message.isBarrierLike && message.isAsynchronous != true
        }
        val repeatedTargetCount: Int = messages
            .mapNotNull { message -> message.targetClass ?: message.callbackClass }
            .groupingBy { key -> key }
            .eachCount()
            .values
            .maxOrNull() ?: 0
        return PendingQueueSummary(
            totalCount = messages.size,
            hasBarrierHead = head?.isBarrierLike == true,
            barrierToken = head?.takeIf { message -> message.isBarrierLike }?.arg1,
            firstSynchronousBlockedMs = firstSynchronous?.blockedMs,
            repeatedTargetCount = repeatedTargetCount,
        )
    }
}
