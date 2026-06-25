package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MainThreadEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.MainThreadRetentionStats
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord

/**
 * 主线程证据保留层，按证据价值拆分最近历史、慢历史、短消息风暴和栈采样。
 *
 * @property historyLimit 最近历史窗口容量，小于 0 时按 0 处理。
 * @property slowHistoryLimit 慢历史窗口容量，小于 0 时按 0 处理。
 * @property aggregatedBurstLimit 聚合短消息窗口容量，小于 0 时按 0 处理。
 * @property stackSampleLimit 栈采样窗口容量，小于 0 时按 0 处理。
 * @property slowMessageMs 慢消息阈值，达到该值的消息进入慢历史。
 * @property shortMessageAggregateMs 短消息聚合阈值，也用于关键组件消息保留。
 * @property messageBurstCountThreshold 连续短消息数量聚合阈值。
 */
class MainThreadEvidenceStore(
    historyLimit: Int,
    slowHistoryLimit: Int,
    aggregatedBurstLimit: Int,
    stackSampleLimit: Int,
    private val slowMessageMs: Long,
    private val shortMessageAggregateMs: Long,
    private val messageBurstCountThreshold: Int = 20,
) {
    // 最近历史消息容量，构造时归一化为非负值。
    private val historyLimit: Int = historyLimit.coerceAtLeast(0)

    // 慢历史消息容量，构造时归一化为非负值。
    private val slowHistoryLimit: Int = slowHistoryLimit.coerceAtLeast(0)

    // 聚合短消息容量，构造时归一化为非负值。
    private val aggregatedBurstLimit: Int = aggregatedBurstLimit.coerceAtLeast(0)

    // 栈采样容量，构造时归一化为非负值。
    private val stackSampleLimit: Int = stackSampleLimit.coerceAtLeast(0)

    // 最近完成消息窗口，按时间顺序存放。
    private val historyMessages: ArrayDeque<MessageRecord> = ArrayDeque()

    // 慢消息和关键组件消息窗口，避免被短消息 churn 淘汰。
    private val slowHistoryMessages: ArrayDeque<MessageRecord> = ArrayDeque()

    // 已达到阈值的连续短消息聚合记录。
    private val aggregatedBursts: ArrayDeque<MessageRecord> = ArrayDeque()

    // 按 [StackSampleRecord.stackId] 去重的栈采样池。
    private val stackSamplesById: LinkedHashMap<String, StackSampleRecord> = LinkedHashMap()

    // 尚未达到聚合阈值的连续短消息。
    private var pendingBurst: ShortMessageBurst? = null

    // 最近历史窗口淘汰计数。
    private var historyDroppedCount: Long = 0L

    // 慢历史窗口淘汰计数。
    private var slowHistoryDroppedCount: Long = 0L

    // 聚合短消息窗口淘汰计数，用于标记证据被裁剪。
    private var aggregatedBurstDroppedCount: Long = 0L

    // 栈采样窗口淘汰计数，用于标记证据被裁剪。
    private var stackSampleDroppedCount: Long = 0L

    // 被折叠进聚合记录的原始短消息数量。
    private var aggregatedMessageCount: Long = 0L

    /**
     * 记录一条已完成的主线程消息，并同步保留其关联栈样本。
     *
     * @param record 已完成消息记录。
     * @param stackSamples 本次消息期间采集到的栈样本。
     */
    @Synchronized
    fun addFinishedMessage(
        record: MessageRecord,
        stackSamples: List<StackSampleRecord> = emptyList(),
    ): Unit {
        retainStackSamples(stackSamples = stackSamples)
        if (isSlowHistoryMessage(record = record)) {
            appendSlowHistory(record = record)
        }
        if (canAggregate(record = record)) {
            appendPendingBurst(record = record)
        } else {
            flushPendingBurstToHistory()
            appendHistory(record = record)
        }
        trimStackSamples(priorityStackIds = collectSlowHistoryStackIds())
    }

    /**
     * 生成不可变快照；未达到聚合阈值的短消息会以原始记录形式出现在最近历史里。
     *
     * @param currentMessage 触发快照时仍在执行的消息，用于提升栈样本保留优先级。
     * @param currentStackSamples 当前消息已采集但尚未写入 store 的栈样本。
     * @return 主线程证据保留层的独立快照。
     */
    @Synchronized
    fun snapshot(
        currentMessage: MessageRecord?,
        currentStackSamples: List<StackSampleRecord> = emptyList(),
    ): MainThreadEvidenceSnapshot {
        val visibleHistory: List<MessageRecord> = buildVisibleHistory()
        val snapshotHistoryDroppedCount: Long = calculateSnapshotHistoryDroppedCount(records = visibleHistory)
        val historySnapshot: List<MessageRecord> = trimRecords(records = visibleHistory, limit = historyLimit)
        val slowHistory: List<MessageRecord> = slowHistoryMessages.toList()
        val priorityStackIds: List<String> = collectPriorityStackIds(
            currentMessage = currentMessage,
            currentStackSamples = currentStackSamples,
            slowHistory = slowHistory,
        )
        val stackSampleSnapshot: StackSampleSnapshot = buildStackSampleSnapshot(
            currentStackSamples = currentStackSamples,
            priorityStackIds = priorityStackIds,
        )
        return MainThreadEvidenceSnapshot(
            historyMessages = historySnapshot,
            slowHistoryMessages = slowHistory,
            aggregatedBursts = aggregatedBursts.toList(),
            stackSamples = stackSampleSnapshot.records,
            retention = buildRetentionStats(
                snapshotHistoryDroppedCount = snapshotHistoryDroppedCount,
                snapshotStackSampleDroppedCount = stackSampleSnapshot.droppedCount,
            ),
        )
    }

    /**
     * 清空所有内部状态，供 SDK 停止或测试重置时释放证据。
     */
    @Synchronized
    fun clear(): Unit {
        historyMessages.clear()
        slowHistoryMessages.clear()
        aggregatedBursts.clear()
        stackSamplesById.clear()
        pendingBurst = null
        historyDroppedCount = 0L
        slowHistoryDroppedCount = 0L
        aggregatedBurstDroppedCount = 0L
        stackSampleDroppedCount = 0L
        aggregatedMessageCount = 0L
    }

    // 判断消息是否值得进入慢历史，避免关键证据被普通历史窗口淘汰。
    private fun isSlowHistoryMessage(record: MessageRecord): Boolean {
        return record.wallMs >= slowMessageMs ||
            record.sampleStackIds.isNotEmpty() ||
            (record.isCriticalComponent && record.wallMs >= shortMessageAggregateMs)
    }

    // 只聚合不含独立慢证据的短消息，避免折叠掉慢消息和栈样本引用。
    private fun canAggregate(record: MessageRecord): Boolean {
        return isAggregationEnabled() &&
            !isSlowHistoryMessage(record = record) &&
            record.wallMs < shortMessageAggregateMs
    }

    // 聚合需要同时具备有效数量阈值和耗时阈值。
    private fun isAggregationEnabled(): Boolean {
        return messageBurstCountThreshold > 1 && shortMessageAggregateMs > 0L
    }

    // 将短消息追加到当前连续分组，分组变化时先回写未聚合的原始记录。
    private fun appendPendingBurst(record: MessageRecord): Unit {
        val currentBurst: ShortMessageBurst? = pendingBurst
        if (currentBurst == null || !currentBurst.canAccept(record = record)) {
            flushPendingBurstToHistory()
            pendingBurst = ShortMessageBurst(firstRecord = record)
        } else {
            currentBurst.add(record = record)
        }
        flushPendingBurstIfThresholdReached()
    }

    // 达到数量或累计耗时阈值后折叠为一条聚合记录。
    private fun flushPendingBurstIfThresholdReached(): Unit {
        val currentBurst: ShortMessageBurst = pendingBurst ?: return
        if (currentBurst.count < messageBurstCountThreshold && currentBurst.wallMs < shortMessageAggregateMs) {
            return
        }
        val aggregatedRecord: MessageRecord = currentBurst.toAggregatedRecord()
        appendHistory(record = aggregatedRecord)
        appendAggregatedBurst(record = aggregatedRecord)
        aggregatedMessageCount += currentBurst.count.toLong()
        pendingBurst = null
    }

    // 将未达到聚合阈值的短消息作为原始历史消息保留，避免快照丢失尾部消息。
    private fun flushPendingBurstToHistory(): Unit {
        val currentBurst: ShortMessageBurst = pendingBurst ?: return
        currentBurst.records.forEach { record: MessageRecord ->
            appendHistory(record = record)
        }
        pendingBurst = null
    }

    // 追加最近历史记录并维护固定容量。
    private fun appendHistory(record: MessageRecord): Unit {
        appendBounded(
            records = historyMessages,
            record = record,
            limit = historyLimit,
            onDrop = { historyDroppedCount += 1L },
        )
    }

    // 追加慢历史记录并维护固定容量。
    private fun appendSlowHistory(record: MessageRecord): Unit {
        appendBounded(
            records = slowHistoryMessages,
            record = record,
            limit = slowHistoryLimit,
            onDrop = { slowHistoryDroppedCount += 1L },
        )
    }

    // 追加聚合短消息记录并维护固定容量。
    private fun appendAggregatedBurst(record: MessageRecord): Unit {
        appendBounded(
            records = aggregatedBursts,
            record = record,
            limit = aggregatedBurstLimit,
            onDrop = { aggregatedBurstDroppedCount += 1L },
        )
    }

    // 泛型容量维护逻辑，保证所有窗口都有一致的淘汰语义。
    private fun appendBounded(
        records: ArrayDeque<MessageRecord>,
        record: MessageRecord,
        limit: Int,
        onDrop: () -> Unit,
    ): Unit {
        if (limit <= 0) {
            onDrop()
            return
        }
        while (records.size >= limit) {
            records.removeFirst()
            onDrop()
        }
        records.addLast(record)
    }

    // 记录栈采样时按 ID 合并命中次数，避免同一栈重复占用窗口。
    private fun retainStackSamples(stackSamples: List<StackSampleRecord>): Unit {
        stackSamples.forEach { sample: StackSampleRecord ->
            mergeStackSample(samplesById = stackSamplesById, sample = sample)
        }
    }

    // 按优先 ID 裁剪内部栈采样池，优先保留慢历史仍引用的样本。
    private fun trimStackSamples(priorityStackIds: List<String>): Unit {
        val trimmedSamples: List<StackSampleRecord> = trimStackSamples(
            samplesById = stackSamplesById,
            priorityStackIds = priorityStackIds,
        )
        stackSampleDroppedCount += (stackSamplesById.size - trimmedSamples.size).coerceAtLeast(0).toLong()
        stackSamplesById.clear()
        trimmedSamples.forEach { sample: StackSampleRecord ->
            stackSamplesById[sample.stackId] = sample
        }
    }

    // 构造包含挂起短消息的历史视图，避免快照改变内部聚合状态。
    private fun buildVisibleHistory(): List<MessageRecord> {
        val records: MutableList<MessageRecord> = historyMessages.toMutableList()
        pendingBurst?.records?.let { pendingRecords: List<MessageRecord> ->
            records.addAll(pendingRecords)
        }
        return records
    }

    // 按容量返回最新记录；快照裁剪不改变内部淘汰计数。
    private fun trimRecords(
        records: List<MessageRecord>,
        limit: Int,
    ): List<MessageRecord> {
        if (limit <= 0) {
            return emptyList()
        }
        return records.takeLast(n = limit)
    }

    // 快照阶段的挂起短消息也可能被窗口裁剪，需要体现在本次 retention 中。
    private fun calculateSnapshotHistoryDroppedCount(records: List<MessageRecord>): Long {
        if (historyLimit <= 0) {
            return records.size.toLong()
        }
        return (records.size - historyLimit).coerceAtLeast(0).toLong()
    }

    // 生成栈样本快照，将当前消息样本临时并入并按优先级裁剪。
    private fun buildStackSampleSnapshot(
        currentStackSamples: List<StackSampleRecord>,
        priorityStackIds: List<String>,
    ): StackSampleSnapshot {
        val samplesById: LinkedHashMap<String, StackSampleRecord> = LinkedHashMap(stackSamplesById)
        currentStackSamples.forEach { sample: StackSampleRecord ->
            mergeStackSample(samplesById = samplesById, sample = sample)
        }
        val retainedSamples: List<StackSampleRecord> = trimStackSamples(
            samplesById = samplesById,
            priorityStackIds = priorityStackIds,
        )
        return StackSampleSnapshot(
            records = retainedSamples,
            droppedCount = (samplesById.size - retainedSamples.size).coerceAtLeast(0).toLong(),
        )
    }

    // 相同 [StackSampleRecord.stackId] 表示同一栈证据，合并命中次数避免覆盖采样强度。
    private fun mergeStackSample(
        samplesById: LinkedHashMap<String, StackSampleRecord>,
        sample: StackSampleRecord,
    ): Unit {
        val existingSample: StackSampleRecord? = samplesById[sample.stackId]
        samplesById[sample.stackId] = if (existingSample == null) {
            sample
        } else {
            sample.copy(hitCount = existingSample.hitCount + sample.hitCount)
        }
    }

    // 当前消息和慢历史引用的栈样本最有诊断价值，优先放入裁剪结果。
    private fun collectPriorityStackIds(
        currentMessage: MessageRecord?,
        currentStackSamples: List<StackSampleRecord>,
        slowHistory: List<MessageRecord>,
    ): List<String> {
        val stackIds: MutableList<String> = mutableListOf()
        currentStackSamples.forEach { sample: StackSampleRecord ->
            stackIds.add(sample.stackId)
        }
        currentMessage?.sampleStackIds?.let { currentStackIds: List<String> ->
            stackIds.addAll(currentStackIds)
        }
        slowHistory.asReversed().forEach { record: MessageRecord ->
            stackIds.addAll(record.sampleStackIds)
        }
        return stackIds.distinct()
    }

    // 内部裁剪只需要慢历史优先级，不依赖快照时的当前消息。
    private fun collectSlowHistoryStackIds(): List<String> {
        return slowHistoryMessages.toList().asReversed().flatMap { record: MessageRecord -> record.sampleStackIds }.distinct()
    }

    // 根据优先 ID 先取关键样本，再按插入顺序补足剩余容量。
    private fun trimStackSamples(
        samplesById: LinkedHashMap<String, StackSampleRecord>,
        priorityStackIds: List<String>,
    ): List<StackSampleRecord> {
        if (stackSampleLimit <= 0) {
            return emptyList()
        }
        val retainedSamples: MutableList<StackSampleRecord> = mutableListOf()
        priorityStackIds.forEach { stackId: String ->
            samplesById[stackId]?.let { sample: StackSampleRecord ->
                retainedSamples.add(sample)
            }
        }
        samplesById.values.forEach { sample: StackSampleRecord ->
            if (retainedSamples.size < stackSampleLimit && retainedSamples.none { item -> item.stackId == sample.stackId }) {
                retainedSamples.add(sample)
            }
        }
        return retainedSamples.take(n = stackSampleLimit)
    }

    // retention 汇总所有有界窗口的容量、淘汰和聚合状态。
    private fun buildRetentionStats(
        snapshotHistoryDroppedCount: Long,
        snapshotStackSampleDroppedCount: Long,
    ): MainThreadRetentionStats {
        val visibleHistoryDroppedCount: Long = historyDroppedCount + snapshotHistoryDroppedCount
        val visibleStackSampleDroppedCount: Long = stackSampleDroppedCount + snapshotStackSampleDroppedCount
        return MainThreadRetentionStats(
            historyLimit = historyLimit,
            slowHistoryLimit = slowHistoryLimit,
            aggregatedBurstLimit = aggregatedBurstLimit,
            stackSampleLimit = stackSampleLimit,
            historyDroppedCount = visibleHistoryDroppedCount,
            slowHistoryDroppedCount = slowHistoryDroppedCount,
            aggregatedMessageCount = aggregatedMessageCount,
            aggregationEnabled = isAggregationEnabled(),
            truncated = visibleHistoryDroppedCount > 0L ||
                slowHistoryDroppedCount > 0L ||
                aggregatedBurstDroppedCount > 0L ||
                visibleStackSampleDroppedCount > 0L,
        )
    }

    /**
     * 栈采样快照结果，同时携带本次快照裁剪数量用于 retention 说明。
     */
    private data class StackSampleSnapshot(
        val records: List<StackSampleRecord>,
        val droppedCount: Long,
    )

    /**
     * 连续短消息分组，只有目标、回调、what 和消息类型完全一致时才继续累计。
     */
    private class ShortMessageBurst(
        firstRecord: MessageRecord,
    ) {
        // 当前分组内的原始短消息，未达到阈值前需要原样返回。
        val records: MutableList<MessageRecord> = mutableListOf(firstRecord)

        // 当前分组消息数量。
        val count: Int
            get() = records.size

        // 当前分组累计 wall time。
        val wallMs: Long
            get() = records.sumOf { record: MessageRecord -> record.wallMs }

        // 当前分组累计 CPU time。
        private val cpuMs: Long
            get() = records.sumOf { record: MessageRecord -> record.cpuMs }

        // 只有连续且同型的短消息才可以折叠为一条聚合记录。
        fun canAccept(record: MessageRecord): Boolean {
            val firstRecord: MessageRecord = records.first()
            return firstRecord.targetClass == record.targetClass &&
                firstRecord.callbackClass == record.callbackClass &&
                firstRecord.what == record.what &&
                firstRecord.messageType == record.messageType
        }

        // 添加一条同型短消息，等待阈值判断决定是否聚合。
        fun add(record: MessageRecord): Unit {
            records.add(record)
        }

        // 生成聚合记录，用第一条消息承载稳定身份字段，用最后一条消息补齐结束时间。
        fun toAggregatedRecord(): MessageRecord {
            val firstRecord: MessageRecord = records.first()
            val lastRecord: MessageRecord = records.last()
            return firstRecord.copy(
                kind = MessageRecordKind.AGGREGATED,
                endUptimeMs = lastRecord.endUptimeMs,
                wallMs = wallMs,
                cpuMs = cpuMs,
                count = count,
                sampleStackIds = emptyList(),
            )
        }
    }
}
