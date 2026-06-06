package com.valiantyan.anrmonitor.collector.stack

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 验证慢消息堆栈采样器能按栈指纹聚合同一条消息中的重复采样。
 */
class SlowMessageStackSamplerTest {
    /**
     * 同一条消息内采到相同栈时，应合并成一个 [StackSample] 并累计命中次数。
     */
    @Test
    fun collectSampleAggregatesSameStackHash(): Unit {
        val sampler: SlowMessageStackSampler = SlowMessageStackSampler(
            maxSamplesPerMessage = 3,
            frameProvider = { listOf("com.example.Feature.render(Feature.kt:42)") },
        )

        sampler.startMessage(seq = 1L)
        sampler.collectSample(seq = 1L)
        sampler.collectSample(seq = 1L)

        val samples: List<StackSample> = sampler.finishMessage(seq = 1L)

        assertEquals(1, samples.size)
        assertEquals(2, samples.first().hitCount)
    }
}
