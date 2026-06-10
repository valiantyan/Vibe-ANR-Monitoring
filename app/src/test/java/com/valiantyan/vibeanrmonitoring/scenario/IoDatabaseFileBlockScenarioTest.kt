package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 IO / 数据库 / 文件阻塞场景的元数据和主线程工作负载入口。
 */
class IoDatabaseFileBlockScenarioTest {
    @Test
    fun runExecutesConfiguredIoWorkload(): Unit {
        val workload: RecordingMainThreadIoWorkload = RecordingMainThreadIoWorkload()
        val scenario: IoDatabaseFileBlockScenario = IoDatabaseFileBlockScenario(
            workload = workload,
        )
        scenario.run()
        assertEquals(1, workload.runCount)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val workload: RecordingMainThreadIoWorkload = RecordingMainThreadIoWorkload()
        val scenario: IoDatabaseFileBlockScenario = IoDatabaseFileBlockScenario(
            workload = workload,
        )
        assertEquals("io_database_file_block", scenario.id)
        assertEquals("IO / 数据库 / 文件阻塞", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + IO/DB call stack evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.current.wallMs >= 3000"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 IoDatabaseFileBlockScenario.run"))
        assertTrue(
            scenario.expectedJsonSignals.contains(
                "mainThread.stackFrames 包含 FileAndDatabaseBlockingWorkload.runIoDatabaseFileWorkload",
            ),
        )
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 FileOutputStream.write 或 SQLiteDatabase"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected 不是主因"))
    }

    private class RecordingMainThreadIoWorkload : MainThreadIoWorkload {
        var runCount: Int = 0

        override fun runIoDatabaseFileWorkload(): Unit {
            runCount += 1
        }
    }
}
