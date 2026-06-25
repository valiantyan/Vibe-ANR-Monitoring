package com.valiantyan.anrmonitor.domain.model

/**
 * 主线程证据保留层的一次不可变快照。
 *
 * @property historyMessages 最近完成的主线程消息窗口。
 * @property slowHistoryMessages 不被普通短消息淘汰的历史慢消息和关键组件消息。
 * @property aggregatedBursts 连续短消息风暴的聚合记录。
 * @property stackSamples 当前消息或慢历史消息引用的栈采样记录。
 * @property retention 本次快照的保留上限、裁剪计数和聚合统计。
 */
data class MainThreadEvidenceSnapshot(
    val historyMessages: List<MessageRecord> = emptyList(),
    val slowHistoryMessages: List<MessageRecord> = emptyList(),
    val aggregatedBursts: List<MessageRecord> = emptyList(),
    val stackSamples: List<StackSampleRecord> = emptyList(),
    val retention: MainThreadRetentionStats = MainThreadRetentionStats.empty(),
)

/**
 * 主线程证据保留状态，帮助报告读者判断证据是否完整。
 *
 * @property historyLimit 最近历史消息窗口容量。
 * @property slowHistoryLimit 慢历史消息窗口容量。
 * @property aggregatedBurstLimit 聚合短消息窗口容量。
 * @property stackSampleLimit 栈采样保留容量。
 * @property historyDroppedCount 最近历史窗口因容量淘汰的记录数。
 * @property slowHistoryDroppedCount 慢历史窗口因容量淘汰的记录数。
 * @property aggregatedMessageCount 被折叠进聚合记录的原始短消息数量。
 * @property aggregationEnabled 是否启用连续短消息聚合。
 * @property truncated 任一有界窗口因容量限制丢弃证据时为 true。
 */
data class MainThreadRetentionStats(
    val historyLimit: Int,
    val slowHistoryLimit: Int,
    val aggregatedBurstLimit: Int,
    val stackSampleLimit: Int,
    val historyDroppedCount: Long,
    val slowHistoryDroppedCount: Long,
    val aggregatedMessageCount: Long,
    val aggregationEnabled: Boolean,
    val truncated: Boolean,
) {
    companion object {
        /**
         * 空报告或旧测试构造路径使用的明确默认值。
         */
        fun empty(): MainThreadRetentionStats {
            return MainThreadRetentionStats(
                historyLimit = 0,
                slowHistoryLimit = 0,
                aggregatedBurstLimit = 0,
                stackSampleLimit = 0,
                historyDroppedCount = 0L,
                slowHistoryDroppedCount = 0L,
                aggregatedMessageCount = 0L,
                aggregationEnabled = false,
                truncated = false,
            )
        }
    }
}
