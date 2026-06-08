package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 BroadcastReceiver 超时场景的触发入口和 Receiver 阻塞动作。
 */
class BroadcastTimeoutScenarioTest {
    @Test
    fun runSendsBroadcastWithConfiguredAction(): Unit {
        val sender: RecordingBroadcastSender = RecordingBroadcastSender()
        val scenario: BroadcastTimeoutScenario = BroadcastTimeoutScenario(
            broadcastSender = sender,
        )
        scenario.run()
        assertEquals(
            listOf(BroadcastTimeoutScenario.ACTION_BROADCAST_TIMEOUT),
            sender.actions,
        )
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val sender: RecordingBroadcastSender = RecordingBroadcastSender()
        val scenario: BroadcastTimeoutScenario = BroadcastTimeoutScenario(
            broadcastSender = sender,
        )
        assertEquals("broadcast_receiver_timeout", scenario.id)
        assertEquals("BroadcastReceiver 超时", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + BROADCAST component evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 BroadcastTimeoutReceiver.onReceive"))
        assertTrue(scenario.expectedJsonSignals.contains("systemAnr.anrType = BROADCAST_FOREGROUND 或 BROADCAST_BACKGROUND"))
        assertTrue(scenario.expectedJsonSignals.contains("systemAnr.componentTimeoutMs = 10000 或 60000"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun blockerBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val blocker: BroadcastTimeoutBlocker = BroadcastTimeoutBlocker(
            blockingAction = blockingAction,
            durationMs = 12_345L,
        )
        blocker.block()
        assertEquals(listOf(12_345L), blockingAction.blockedDurations)
    }

    private class RecordingBroadcastSender : BroadcastSender {
        val actions: MutableList<String> = mutableListOf()

        override fun send(action: String): Unit {
            actions.add(action)
        }
    }

    private class RecordingBlockingAction : BlockingAction {
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun block(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }
    }
}
