package com.valiantyan.anrmonitor.core.timeline

import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证主线程证据保留层按价值而不是单一时间窗口保留关键证据。
 */
class MainThreadEvidenceStoreTest {
    @Test
    fun snapshotKeepsSlowMessageAfterShortMessageChurn(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        val slow: MessageRecord = message(seq = 1L, wallMs = 1_500L, sampleStackIds = listOf("sample-slow"))
        val sample: StackSampleRecord = StackSampleRecord(
            stackId = "sample-slow",
            frames = listOf("com.example.Slow.run(Slow.kt:10)"),
            hitCount = 1,
        )
        store.addFinishedMessage(record = slow, stackSamples = listOf(sample))
        (2L..501L).forEach { seq: Long ->
            store.addFinishedMessage(record = message(seq = seq, wallMs = 1L))
        }
        val snapshot = store.snapshot(currentMessage = null)
        assertFalse(snapshot.historyMessages.map { record: MessageRecord -> record.seq }.contains(1L))
        assertEquals(listOf(1L), snapshot.slowHistoryMessages.map { record: MessageRecord -> record.seq })
        assertEquals(listOf("sample-slow"), snapshot.stackSamples.map { record: StackSampleRecord -> record.stackId })
        assertTrue(snapshot.retention.historyDroppedCount > 0L)
        assertTrue(snapshot.retention.truncated)
    }

    @Test
    fun addFinishedMessageAggregatesContiguousShortBurst(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 10,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 3,
        )
        store.addFinishedMessage(record = message(seq = 1L, wallMs = 10L))
        store.addFinishedMessage(record = message(seq = 2L, wallMs = 20L))
        store.addFinishedMessage(record = message(seq = 3L, wallMs = 30L))
        val snapshot = store.snapshot(currentMessage = null)
        assertEquals(1, snapshot.aggregatedBursts.size)
        assertEquals(MessageRecordKind.AGGREGATED, snapshot.aggregatedBursts.first().kind)
        assertEquals(3, snapshot.aggregatedBursts.first().count)
        assertEquals(60L, snapshot.aggregatedBursts.first().wallMs)
        assertEquals(3L, snapshot.retention.aggregatedMessageCount)
        assertTrue(snapshot.historyMessages.any { record: MessageRecord -> record.kind == MessageRecordKind.AGGREGATED })
    }

    @Test
    fun criticalComponentAboveAggregateThresholdEntersSlowHistory(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        store.addFinishedMessage(
            record = message(
                seq = 7L,
                wallMs = 300L,
                targetClass = "android.app.ActivityThread\$H",
                isCriticalComponent = true,
            ),
        )
        assertEquals(listOf(7L), store.snapshot(currentMessage = null).slowHistoryMessages.map { record: MessageRecord -> record.seq })
    }

    @Test
    fun snapshotReturnsImmutableCopies(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 2,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 2,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        store.addFinishedMessage(record = message(seq = 1L, wallMs = 10L))
        val first = store.snapshot(currentMessage = null)
        store.addFinishedMessage(record = message(seq = 2L, wallMs = 10L))
        val second = store.snapshot(currentMessage = null)
        assertEquals(listOf(1L), first.historyMessages.map { record: MessageRecord -> record.seq })
        assertEquals(listOf(1L, 2L), second.historyMessages.map { record: MessageRecord -> record.seq })
    }

    @Test
    fun snapshotMarksTruncatedWhenPendingShortMessagesExceedHistoryLimit(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 2,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 2,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 10,
        )
        store.addFinishedMessage(record = message(seq = 1L, wallMs = 10L))
        store.addFinishedMessage(record = message(seq = 2L, wallMs = 10L))
        store.addFinishedMessage(record = message(seq = 3L, wallMs = 10L))
        val snapshot = store.snapshot(currentMessage = null)
        assertEquals(listOf(2L, 3L), snapshot.historyMessages.map { record: MessageRecord -> record.seq })
        assertEquals(1L, snapshot.retention.historyDroppedCount)
        assertTrue(snapshot.retention.truncated)
    }

    @Test
    fun stackSampleRetentionPrefersNewerSlowHistoryWhenLimitIsTight(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 1,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        store.addFinishedMessage(
            record = message(seq = 1L, wallMs = 1_200L, sampleStackIds = listOf("sample-old")),
            stackSamples = listOf(sample(stackId = "sample-old", hitCount = 1)),
        )
        store.addFinishedMessage(
            record = message(seq = 2L, wallMs = 1_300L, sampleStackIds = listOf("sample-new")),
            stackSamples = listOf(sample(stackId = "sample-new", hitCount = 1)),
        )
        val snapshot = store.snapshot(currentMessage = null)
        assertEquals(listOf("sample-new"), snapshot.stackSamples.map { record: StackSampleRecord -> record.stackId })
    }

    @Test
    fun currentStackSamplesMergeWithStoredSamplesByStackId(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 2,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        store.addFinishedMessage(
            record = message(seq = 1L, wallMs = 1_200L, sampleStackIds = listOf("sample-shared")),
            stackSamples = listOf(sample(stackId = "sample-shared", hitCount = 2)),
        )
        val snapshot = store.snapshot(
            currentMessage = message(seq = 2L, wallMs = 100L, sampleStackIds = listOf("sample-shared")),
            currentStackSamples = listOf(
                sample(stackId = "sample-shared", hitCount = 3),
                sample(stackId = "sample-shared", hitCount = 4),
            ),
        )
        assertEquals(listOf("sample-shared"), snapshot.stackSamples.map { record: StackSampleRecord -> record.stackId })
        assertEquals(9, snapshot.stackSamples.first().hitCount)
    }

    @Test
    fun currentStackSamplesWinOverStoredSamplesWhenUnreferenced(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 1,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        store.addFinishedMessage(
            record = message(seq = 1L, wallMs = 1_200L, sampleStackIds = listOf("sample-old")),
            stackSamples = listOf(sample(stackId = "sample-old", hitCount = 1)),
        )
        val snapshot = store.snapshot(
            currentMessage = null,
            currentStackSamples = listOf(sample(stackId = "sample-current", hitCount = 1)),
        )
        assertEquals(listOf("sample-current"), snapshot.stackSamples.map { record: StackSampleRecord -> record.stackId })
    }

    @Test
    fun snapshotMarksTruncatedWhenCurrentStackSamplesExceedLimit(): Unit {
        val store: MainThreadEvidenceStore = MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 1,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
        val snapshot = store.snapshot(
            currentMessage = null,
            currentStackSamples = listOf(
                sample(stackId = "sample-current-1", hitCount = 1),
                sample(stackId = "sample-current-2", hitCount = 1),
            ),
        )
        assertEquals(1, snapshot.stackSamples.size)
        assertTrue(snapshot.retention.truncated)
    }

    private fun sample(
        stackId: String,
        hitCount: Int,
    ): StackSampleRecord {
        return StackSampleRecord(
            stackId = stackId,
            frames = listOf("com.example.$stackId.run(Sample.kt:10)"),
            hitCount = hitCount,
        )
    }

    private fun message(
        seq: Long,
        wallMs: Long,
        targetClass: String = "android.os.Handler",
        callbackClass: String? = "com.example.RefreshRunnable",
        what: Int? = 1,
        isCriticalComponent: Boolean = false,
        sampleStackIds: List<String> = emptyList(),
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = MessageRecordKind.HISTORY,
            messageType = "looper_dispatch",
            what = what,
            targetClass = targetClass,
            callbackClass = callbackClass,
            isCriticalComponent = isCriticalComponent,
            startUptimeMs = seq * 10L,
            endUptimeMs = seq * 10L + wallMs,
            wallMs = wallMs,
            cpuMs = wallMs,
            sampleStackIds = sampleStackIds,
        )
    }
}
