package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MessageRecord

/**
 * 有界主线程消息环形缓冲区，用于保留疑似 ANR 前的历史消息证据。
 *
 * @property capacity 最大保留记录数，小于等于 0 时表示不记录历史消息。
 */
class MessageRingBuffer(
    private val capacity: Int,
) {
    // 历史消息队列，所有访问通过同步方法保护。
    private val records: ArrayDeque<MessageRecord> = ArrayDeque()

    /**
     * 添加一条消息记录；当容量已满时先淘汰最旧记录，保证内存使用有界。
     *
     * @param record 要追加到历史窗口中的消息记录。
     */
    @Synchronized
    fun add(record: MessageRecord): Unit {
        if (capacity <= 0) {
            return
        }
        while (records.size >= capacity) {
            records.removeFirst()
        }
        records.addLast(record)
    }

    /**
     * 返回当前历史窗口快照，调用方可安全遍历且不会影响内部队列。
     *
     * @return 按时间顺序排列的消息记录副本。
     */
    @Synchronized
    fun snapshot(): List<MessageRecord> {
        return records.toList()
    }

    /**
     * 查找达到慢消息阈值的历史记录，用于后续归因定位历史慢消息和累计慢消息。
     *
     * @param thresholdMs 慢消息阈值，单位毫秒。
     * @return 满足 [MessageRecord.wallMs] 大于等于阈值的记录。
     */
    @Synchronized
    fun findSlowMessages(thresholdMs: Long): List<MessageRecord> {
        return records.filter { record -> record.wallMs >= thresholdMs }
    }

    /**
     * 清空历史窗口，供 SDK 停止或测试重置时释放已有记录。
     */
    @Synchronized
    fun clear(): Unit {
        records.clear()
    }
}
