package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证当前慢消息场景的元数据和阻塞动作，避免按钮只触发匿名 sleep 而缺少可读根因入口。
 */
class CurrentSlowInputScenarioTest {
    @Test
    fun runBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val scenario: CurrentSlowInputScenario = CurrentSlowInputScenario(
            blockingAction = blockingAction,
            durationMs = 4_321L,
        )
        scenario.run()
        assertEquals(listOf(4_321L), blockingAction.blockedDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val scenario: CurrentSlowInputScenario = CurrentSlowInputScenario(
            blockingAction = blockingAction,
        )
        assertEquals("current_slow_input", scenario.id)
        assertEquals("当前消息慢", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 CurrentSlowInputScenario.run"))
    }

    private class RecordingBlockingAction : BlockingAction {
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun block(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }
    }
}
