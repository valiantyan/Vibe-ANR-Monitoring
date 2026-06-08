package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证主线程 CPU 忙等场景的元数据和忙等动作，确保 JSON 栈能定位到独立场景入口。
 */
class MainThreadCpuBusyScenarioTest {
    @Test
    fun runBurnsCpuForConfiguredDuration(): Unit {
        val cpuBusyAction: RecordingCpuBusyAction = RecordingCpuBusyAction()
        val scenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario(
            cpuBusyAction = cpuBusyAction,
            durationMs = 4_321L,
        )
        scenario.run()
        assertEquals(listOf(4_321L), cpuBusyAction.busyDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val cpuBusyAction: RecordingCpuBusyAction = RecordingCpuBusyAction()
        val scenario: MainThreadCpuBusyScenario = MainThreadCpuBusyScenario(
            cpuBusyAction = cpuBusyAction,
        )
        assertEquals("main_thread_cpu_busy", scenario.id)
        assertEquals("当前消息忙等", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.cpuMs 明显高于等待类场景"))
        assertTrue(scenario.expectedJsonSignals.contains("threadCpu.topThreads 包含主线程高 CPU 证据"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 MainThreadCpuBusyScenario.run"))
    }

    private class RecordingCpuBusyAction : CpuBusyAction {
        val busyDurations: MutableList<Long> = mutableListOf()

        override fun burn(durationMs: Long): Double {
            busyDurations.add(durationMs)
            return 42.0
        }
    }
}
