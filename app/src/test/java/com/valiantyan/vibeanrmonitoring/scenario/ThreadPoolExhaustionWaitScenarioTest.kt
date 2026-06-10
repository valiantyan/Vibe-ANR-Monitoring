package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证线程池耗尽 + 主线程等待场景的元数据和工作负载入口。
 */
class ThreadPoolExhaustionWaitScenarioTest {
    @Test
    fun runExecutesConfiguredThreadPoolWorkload(): Unit {
        val workload: RecordingThreadPoolWaitWorkload = RecordingThreadPoolWaitWorkload()
        val scenario: ThreadPoolExhaustionWaitScenario = ThreadPoolExhaustionWaitScenario(
            workload = workload,
        )

        scenario.run()

        assertEquals(1, workload.runCount)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingThreadPoolWaitWorkload = RecordingThreadPoolWaitWorkload()
        val scenario: ThreadPoolExhaustionWaitScenario = ThreadPoolExhaustionWaitScenario(
            workload = workload,
        )

        assertEquals("thread_pool_exhaustion_wait", scenario.id)
        assertEquals("线程池耗尽 + 主线程等待", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + thread pool wait stack evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ThreadPoolExhaustionWaitScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ThreadPoolExhaustionWorkload.exhaustPoolAndWait"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 java.util.concurrent.FutureTask.get"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingThreadPoolWaitWorkload : ThreadPoolWaitWorkload {
        var runCount: Int = 0

        override fun exhaustPoolAndWait(): Unit {
            runCount += 1
        }
    }
}
