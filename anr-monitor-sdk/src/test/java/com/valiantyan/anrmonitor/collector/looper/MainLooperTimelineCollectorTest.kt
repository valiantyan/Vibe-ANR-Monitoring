package com.valiantyan.anrmonitor.collector.looper

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSampler
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MainThreadEvidenceStore
import com.valiantyan.anrmonitor.domain.model.MainThreadEvidenceSnapshot
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证 [MainLooperTimelineCollector] 能把 Looper Printer 起止日志转换为主线程消息时间线。
 */
class MainLooperTimelineCollectorTest {
    /**
     * 一个完整 dispatch 周期结束后，当前消息应清空，历史缓冲区应记录 wall/cpu 耗时证据。
     */
    @Test
    fun onLooperLogCreatesCurrentThenHistoryRecord(): Unit {
        val clock: FakeClock = FakeClock(values = createValues(first = 100L, second = 250L))
        val cpuClock: FakeCpuClock = FakeCpuClock(currentValues = createValues(first = 10L, second = 80L))
        val store: MainThreadEvidenceStore = evidenceStore()
        val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            evidenceStore = store,
        )

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        collector.onLooperLog(line = "<<<<< Finished to Handler (android.os.Handler) {12345} null")

        val history = store.snapshot(currentMessage = null).historyMessages
        assertNull(collector.currentMessage())
        assertEquals(1, history.size)
        assertEquals(MessageRecordKind.HISTORY, history.first().kind)
        assertEquals(150L, history.first().wallMs)
        assertEquals(70L, history.first().cpuMs)
    }

    /**
     * Watchdog 读取慢消息时，应触发主线程栈采样并按目标线程 CPU 计算当前消息成本。
     */
    @Test
    fun currentMessageCollectsSlowStackSampleAndUsesTargetThreadCpu(): Unit {
        val clock: FakeClock = FakeClock(values = createValues(first = 100L, second = 1_300L, third = 1_300L))
        val cpuClock: FakeCpuClock = FakeCpuClock(
            currentValues = createValues(first = 10L, second = 999L),
            threadCpuValues = ArrayDeque(listOf(80L)),
        )
        val store: MainThreadEvidenceStore = evidenceStore()
        val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            evidenceStore = store,
            slowMessageMs = 1_000L,
            stackSampleIntervalMs = 500L,
            slowMessageSampler = SlowMessageStackSampler(
                maxSamplesPerMessage = 3,
                frameProvider = { listOf("com.example.Feature.render(Feature.kt:42)") },
            ),
        )

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        val current = requireNotNull(collector.currentMessage())
        val samples: List<StackSampleRecord> = store.snapshot(
            currentMessage = current,
            currentStackSamples = collector.stackSamplesFor(sampleStackIds = current.sampleStackIds),
        ).stackSamples

        assertEquals(1, current.sampleStackIds.size)
        assertEquals(1, samples.size)
        assertEquals(70L, current.cpuMs)
        assertEquals(current.sampleStackIds.first(), samples.first().stackId)
    }

    /**
     * 慢消息完成后，样本应迁移到证据仓库，采集器不再保留已完成消息的样本窗口。
     */
    @Test
    fun finishedSlowMessageMigratesStackSamplesToEvidenceStore(): Unit {
        val clock: FakeClock = FakeClock(values = createValues(first = 100L, second = 1_300L, third = 1_500L))
        val cpuClock: FakeCpuClock = FakeCpuClock(
            currentValues = createValues(first = 10L, second = 90L),
            threadCpuValues = ArrayDeque(listOf(80L)),
        )
        val store: MainThreadEvidenceStore = evidenceStore()
        val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            evidenceStore = store,
            slowMessageMs = 1_000L,
            stackSampleIntervalMs = 500L,
            slowMessageSampler = SlowMessageStackSampler(
                maxSamplesPerMessage = 3,
                frameProvider = { listOf("com.example.Feature.render(Feature.kt:42)") },
            ),
        )

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        val current = requireNotNull(collector.currentMessage())
        val sampleStackIds: List<String> = current.sampleStackIds
        collector.onLooperLog(line = "<<<<< Finished to Handler (android.os.Handler) {12345} null")

        val snapshot = store.snapshot(currentMessage = null)
        assertNull(collector.currentMessage())
        assertEquals(sampleStackIds, snapshot.historyMessages.first().sampleStackIds)
        assertEquals(1, snapshot.stackSamples.size)
        assertEquals(sampleStackIds.first(), snapshot.stackSamples.first().stackId)
        assertEquals(emptyList<StackSampleRecord>(), collector.stackSamplesFor(sampleStackIds = sampleStackIds))
    }

    /**
     * 完成消息交接期间，runtime 式读取不应把同一 [seq] 同时暴露为 current 和 history。
     */
    @Test
    fun snapshotDoesNotExposeFinishingMessageAsCurrentAndHistory(): Unit {
        val sampleCount: Int = 20_000
        val frameIndex: AtomicLong = AtomicLong(0L)
        val clock: IncrementingClock = IncrementingClock(start = 100L)
        val cpuClock: IncrementingCpuClock = IncrementingCpuClock(start = 10L)
        val store: MainThreadEvidenceStore = evidenceStore()
        val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            evidenceStore = store,
            slowMessageMs = 0L,
            stackSampleIntervalMs = 0L,
            slowMessageSampler = SlowMessageStackSampler(
                maxSamplesPerMessage = sampleCount,
                frameProvider = {
                    val frameSeq: Long = frameIndex.incrementAndGet()
                    listOf("com.example.Feature.render$frameSeq(Feature.kt:42)")
                },
            ),
        )
        val finishDone: AtomicBoolean = AtomicBoolean(false)
        val duplicateSeen: AtomicBoolean = AtomicBoolean(false)

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        repeat(times = sampleCount) {
            collector.currentMessage()
        }
        val observer: Thread = Thread {
            while (!finishDone.get()) {
                val current = collector.currentMessage()
                val snapshot = store.snapshot(
                    currentMessage = current,
                    currentStackSamples = collector.stackSamplesFor(
                        sampleStackIds = current?.sampleStackIds.orEmpty(),
                    ),
                )
                if (current != null && snapshot.historyMessages.any { record -> record.seq == current.seq }) {
                    duplicateSeen.set(true)
                }
            }
        }
        val finisher: Thread = Thread {
            collector.onLooperLog(line = "<<<<< Finished to Handler (android.os.Handler) {12345} null")
            finishDone.set(true)
        }

        observer.start()
        finisher.start()
        finisher.join()
        observer.join()

        assertFalse(duplicateSeen.get())
    }

    /**
     * Runtime 组装快照时，已进入保留证据的消息不应继续作为 current 输出。
     */
    @Test
    fun runtimeNormalizationDropsCurrentWhenRetainedEvidenceContainsSeq(): Unit {
        val staleCurrent: MessageRecord = message(
            seq = 8L,
            kind = MessageRecordKind.CURRENT,
            wallMs = 1_200L,
        )
        val retainedMessage: MessageRecord = message(
            seq = 8L,
            kind = MessageRecordKind.HISTORY,
            wallMs = 1_200L,
        )
        val unrelatedRetainedMessage: MessageRecord = message(
            seq = 9L,
            kind = MessageRecordKind.HISTORY,
            wallMs = 20L,
        )

        val droppedCurrent: MessageRecord? = normalizeRuntimeCurrentMessage(
            currentMessage = staleCurrent,
            mainThreadEvidence = MainThreadEvidenceSnapshot(historyMessages = listOf(retainedMessage)),
        )
        val keptCurrent: MessageRecord? = normalizeRuntimeCurrentMessage(
            currentMessage = staleCurrent,
            mainThreadEvidence = MainThreadEvidenceSnapshot(historyMessages = listOf(unrelatedRetainedMessage)),
        )

        assertNull(droppedCurrent)
        assertEquals(staleCurrent, keptCurrent)
    }

    /**
     * 创建测试用证据仓库，容量刻意较小以覆盖保留窗口写入路径。
     */
    private fun evidenceStore(): MainThreadEvidenceStore {
        return MainThreadEvidenceStore(
            historyLimit = 4,
            slowHistoryLimit = 2,
            aggregatedBurstLimit = 2,
            stackSampleLimit = 4,
            slowMessageMs = 1_000L,
            shortMessageAggregateMs = 300L,
            messageBurstCountThreshold = 20,
        )
    }

    /**
     * 创建固定顺序的时间源样本，避免测试依赖 Kotlin 版本差异中的集合工厂函数。
     */
    private fun createValues(
        first: Long,
        second: Long,
    ): ArrayDeque<Long> {
        return ArrayDeque(listOf(first, second))
    }

    // 创建三段固定时间样本，用于慢消息当前快照和采样间隔判断。
    private fun createValues(
        first: Long,
        second: Long,
        third: Long,
    ): ArrayDeque<Long> {
        return ArrayDeque(listOf(first, second, third))
    }

    /**
     * 通过反射调用 runtime 私有归一化函数，避免为了测试扩大生产可见性。
     */
    private fun normalizeRuntimeCurrentMessage(
        currentMessage: MessageRecord?,
        mainThreadEvidence: MainThreadEvidenceSnapshot,
    ): MessageRecord? {
        val method: Method = Class.forName("com.valiantyan.anrmonitor.internal.AnrMonitorRuntimeKt").getDeclaredMethod(
            "normalizeCurrentMessage",
            MessageRecord::class.java,
            MainThreadEvidenceSnapshot::class.java,
        )
        method.isAccessible = true
        return method.invoke(
            null,
            currentMessage,
            mainThreadEvidence,
        ) as MessageRecord?
    }

    /**
     * 构造 runtime 归一化测试用消息记录，字段只保留本用例关注的稳定身份。
     */
    private fun message(
        seq: Long,
        kind: MessageRecordKind,
        wallMs: Long,
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = kind,
            messageType = "looper_dispatch",
            what = 1,
            targetClass = "android.os.Handler",
            callbackClass = "com.example.RefreshRunnable",
            isCriticalComponent = false,
            startUptimeMs = seq * 10L,
            endUptimeMs = seq * 10L + wallMs,
            wallMs = wallMs,
            cpuMs = wallMs,
        )
    }

    /**
     * 可控 uptime 时钟，用于稳定验证消息 wall time 差值。
     */
    private class FakeClock(
        private val values: ArrayDeque<Long>,
    ) : Clock {
        /**
         * 按测试预设顺序返回 uptime。
         */
        override fun uptimeMillis(): Long {
            return values.removeFirst()
        }
    }

    /**
     * 可控 CPU 时钟，用于稳定验证主线程 CPU 差值。
     */
    private class FakeCpuClock(
        private val currentValues: ArrayDeque<Long>,
        private val threadCpuValues: ArrayDeque<Long> = ArrayDeque(),
    ) : MainLooperTimelineCollector.CpuClock {
        /**
         * 按测试预设顺序返回 CPU 时间。
         */
        override fun currentThreadCpuMs(): Long {
            return currentValues.removeFirst()
        }

        /**
         * 按线程 ID 返回目标线程 CPU 时间，模拟 Watchdog 线程读取主线程 CPU 的路径。
         */
        override fun threadCpuMs(threadId: Int): Long? {
            if (threadCpuValues.isEmpty()) {
                return null
            }
            return threadCpuValues.removeFirst()
        }

        /**
         * 返回固定主线程 ID，确保当前消息快照按目标线程读取 CPU。
         */
        override fun currentThreadId(): Int {
            return 1
        }
    }

    /**
     * 线程安全递增 uptime 时钟，用于并发测试中避免共享队列竞争。
     */
    private class IncrementingClock(
        start: Long,
    ) : Clock {
        private val value: AtomicLong = AtomicLong(start)

        /**
         * 每次读取递增 1ms，保证慢消息采样持续满足间隔条件。
         */
        override fun uptimeMillis(): Long {
            return value.getAndIncrement()
        }
    }

    /**
     * 线程安全递增 CPU 时钟，用于并发测试中模拟主线程 CPU 持续增长。
     */
    private class IncrementingCpuClock(
        start: Long,
    ) : MainLooperTimelineCollector.CpuClock {
        private val value: AtomicLong = AtomicLong(start)

        /**
         * 返回递增 CPU 时间。
         */
        override fun currentThreadCpuMs(): Long {
            return value.getAndIncrement()
        }

        /**
         * 返回递增目标线程 CPU 时间。
         */
        override fun threadCpuMs(threadId: Int): Long? {
            return value.getAndIncrement()
        }

        /**
         * 返回固定主线程 ID。
         */
        override fun currentThreadId(): Int {
            return 1
        }
    }
}
