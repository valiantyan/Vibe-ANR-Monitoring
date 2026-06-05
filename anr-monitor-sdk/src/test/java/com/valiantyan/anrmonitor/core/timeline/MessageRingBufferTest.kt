package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证 [MessageRingBuffer] 作为主线程历史消息窗口时的容量和筛选行为。
 */
class MessageRingBufferTest {
    /**
     * 缓冲区达到容量后必须淘汰最旧消息，避免长时间运行造成内存无界增长。
     */
    @Test
    fun addRecordsEvictsOldestWhenCapacityExceeded(): Unit {
        val buffer = MessageRingBuffer(
            capacity = 2,
        )

        buffer.add(record = record(seq = 1L, wallMs = 10L))
        buffer.add(record = record(seq = 2L, wallMs = 20L))
        buffer.add(record = record(seq = 3L, wallMs = 30L))

        assertEquals(listOf(2L, 3L), buffer.snapshot().map { item -> item.seq })
    }

    /**
     * 慢消息筛选需要包含等于阈值的记录，用于后续归因时定位历史慢消息证据。
     */
    @Test
    fun findSlowMessagesReturnsRecordsAtOrAboveThreshold(): Unit {
        val buffer = MessageRingBuffer(
            capacity = 4,
        )

        buffer.add(record = record(seq = 1L, wallMs = 100L))
        buffer.add(record = record(seq = 2L, wallMs = 1_500L))

        assertEquals(listOf(2L), buffer.findSlowMessages(thresholdMs = 1_000L).map { item -> item.seq })
    }

    /**
     * 创建最小历史消息记录，让测试聚焦在缓冲区行为而不是消息字段构造。
     */
    private fun record(
        seq: Long,
        wallMs: Long,
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = MessageRecordKind.HISTORY,
            messageType = "dispatch",
            what = null,
            targetClass = "android.os.Handler",
            callbackClass = null,
            isCriticalComponent = false,
            startUptimeMs = 0L,
            endUptimeMs = wallMs,
            wallMs = wallMs,
            cpuMs = wallMs,
        )
    }
}
