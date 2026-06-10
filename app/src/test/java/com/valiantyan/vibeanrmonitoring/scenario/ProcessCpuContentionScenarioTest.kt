package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证进程内 CPU 竞争场景的元数据和工作负载入口。
 */
class ProcessCpuContentionScenarioTest {
    @Test
    fun runExecutesConfiguredCpuContentionWorkload(): Unit {
        val workload: RecordingProcessCpuContentionWorkload = RecordingProcessCpuContentionWorkload()
        val scenario: ProcessCpuContentionScenario = ProcessCpuContentionScenario(
            workload = workload,
            contenderCount = 6,
            contentionDurationMs = 7_000L,
            mainThreadWaitMs = 4_321L,
        )
        scenario.run()
        assertEquals(listOf(RecordedCall(6, 7_000L, 4_321L)), workload.recordedCalls)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingProcessCpuContentionWorkload = RecordingProcessCpuContentionWorkload()
        val scenario: ProcessCpuContentionScenario = ProcessCpuContentionScenario(
            workload = workload,
        )
        assertEquals("process_cpu_contention", scenario.id)
        assertEquals("进程内 CPU 竞争", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + process thread CPU contention evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ProcessCpuContentionScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("threadCpu.topThreads 包含 DemoCpuContender-"))
        assertTrue(scenario.expectedJsonSignals.contains("后台竞争线程 totalCpuMs 位于 Top 线程前列"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.cpuMs 低于纯主线程忙等场景"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingProcessCpuContentionWorkload : ProcessCpuContentionWorkload {
        val recordedCalls: MutableList<RecordedCall> = mutableListOf()

        override fun createContentionAndWaitOnMainThread(
            contenderCount: Int,
            contentionDurationMs: Long,
            mainThreadWaitMs: Long,
        ): Unit {
            recordedCalls.add(
                RecordedCall(
                    contenderCount = contenderCount,
                    contentionDurationMs = contentionDurationMs,
                    mainThreadWaitMs = mainThreadWaitMs,
                ),
            )
        }
    }

    private data class RecordedCall(
        val contenderCount: Int,
        val contentionDurationMs: Long,
        val mainThreadWaitMs: Long,
    )
}
