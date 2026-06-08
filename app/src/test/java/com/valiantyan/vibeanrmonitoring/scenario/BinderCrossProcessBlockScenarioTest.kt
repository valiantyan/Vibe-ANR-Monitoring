package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Binder 跨进程阻塞场景的元数据、绑定准备和远端阻塞调用。
 */
class BinderCrossProcessBlockScenarioTest {
    @Test
    fun initPreparesRemoteBinderConnection(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        BinderCrossProcessBlockScenario(remoteBinderClient = client)
        assertEquals(1, client.ensureBoundCount)
    }

    @Test
    fun runBlocksRemoteBinderForConfiguredDuration(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        val scenario: BinderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(
            remoteBinderClient = client,
            durationMs = 7_654L,
        )
        scenario.run()
        assertEquals(listOf(7_654L), client.blockedDurations)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        val scenario: BinderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(
            remoteBinderClient = client,
        )
        assertEquals("binder_cross_process_block", scenario.id)
        assertEquals("Binder / 跨进程阻塞", scenario.title)
        assertEquals("BINDER_BLOCK_SUSPECTED", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("attribution.primary = BINDER_BLOCK_SUSPECTED"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.suspected = true"))
        assertTrue(scenario.expectedJsonSignals.contains("binderBlock.mainThreadInBinder = true"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 BinderProxy.transact"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun releaseDelegatesToRemoteClient(): Unit {
        val client: RecordingRemoteBinderClient = RecordingRemoteBinderClient()
        val scenario: BinderCrossProcessBlockScenario = BinderCrossProcessBlockScenario(
            remoteBinderClient = client,
        )
        scenario.release()
        assertEquals(1, client.releaseCount)
    }

    private class RecordingRemoteBinderClient : RemoteBinderClient {
        var ensureBoundCount: Int = 0
        var releaseCount: Int = 0
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun ensureBound(): Unit {
            ensureBoundCount += 1
        }

        override fun blockRemote(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }

        override fun release(): Unit {
            releaseCount += 1
        }
    }
}
