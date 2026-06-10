package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 GC / 内存抖动场景的元数据和工作负载入口。
 */
class GcMemoryChurnScenarioTest {
    @Test
    fun runExecutesConfiguredMemoryChurnWorkload(): Unit {
        val workload: RecordingMemoryChurnWorkload = RecordingMemoryChurnWorkload()
        val scenario: GcMemoryChurnScenario = GcMemoryChurnScenario(
            workload = workload,
            targetDurationMs = 4_321L,
        )

        scenario.run()

        assertEquals(listOf(4_321L), workload.targetDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingMemoryChurnWorkload = RecordingMemoryChurnWorkload()
        val scenario: GcMemoryChurnScenario = GcMemoryChurnScenario(
            workload = workload,
        )

        assertEquals("gc_memory_churn", scenario.id)
        assertEquals("GC / 内存抖动", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + GC/memory pressure evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 GcMemoryChurnScenario.run"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 GcMemoryChurnWorkload.churnMemoryOnMainThread"))
        assertTrue(scenario.expectedJsonSignals.contains("environmentSnapshot.memory 可用"))
        assertTrue(scenario.expectedJsonSignals.contains("logcat 同一时间窗出现系统 GC 日志"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingMemoryChurnWorkload : MemoryChurnWorkload {
        val targetDurations: MutableList<Long> = mutableListOf()

        override fun churnMemoryOnMainThread(targetDurationMs: Long): Unit {
            targetDurations.add(targetDurationMs)
        }
    }
}
