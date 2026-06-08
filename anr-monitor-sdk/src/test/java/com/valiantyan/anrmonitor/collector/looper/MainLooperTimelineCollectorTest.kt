package com.valiantyan.anrmonitor.collector.looper

import com.valiantyan.anrmonitor.api.AnrPrivacyMode
import com.valiantyan.anrmonitor.collector.stack.SlowMessageStackSampler
import com.valiantyan.anrmonitor.core.clock.Clock
import com.valiantyan.anrmonitor.core.privacy.ClassNameSanitizer
import com.valiantyan.anrmonitor.core.timeline.MessageRingBuffer
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.StackSampleRecord
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
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
        val buffer: MessageRingBuffer = MessageRingBuffer(capacity = 4)
        val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            historyBuffer = buffer,
        )

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        collector.onLooperLog(line = "<<<<< Finished to Handler (android.os.Handler) {12345} null")

        val history = buffer.snapshot()
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
        val buffer: MessageRingBuffer = MessageRingBuffer(capacity = 4)
        val collector: MainLooperTimelineCollector = MainLooperTimelineCollector(
            clock = clock,
            threadCpuClock = cpuClock,
            sanitizer = ClassNameSanitizer(privacyMode = AnrPrivacyMode.SAFE),
            historyBuffer = buffer,
            slowMessageMs = 1_000L,
            stackSampleIntervalMs = 500L,
            slowMessageSampler = SlowMessageStackSampler(
                maxSamplesPerMessage = 3,
                frameProvider = { listOf("com.example.Feature.render(Feature.kt:42)") },
            ),
        )

        collector.onLooperLog(line = ">>>>> Dispatching to Handler (android.os.Handler) {12345} null: 1")
        val current = requireNotNull(collector.currentMessage())
        val samples: List<StackSampleRecord> = collector.stackSamples()

        assertEquals(1, current.sampleStackIds.size)
        assertEquals(1, samples.size)
        assertEquals(70L, current.cpuMs)
        assertEquals(current.sampleStackIds.first(), samples.first().stackId)
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
}
