package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证消息风暴场景会投递大量同类主线程消息，并保留清晰的 JSON 根因入口。
 */
class MessageStormScenarioTest {
    @Test
    fun runPostsConfiguredStormCallbacksAndBlocksCurrentMessage(): Unit {
        val poster: RecordingMessageStormPoster = RecordingMessageStormPoster()
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val scenario: MessageStormScenario = MessageStormScenario(
            poster = poster,
            blockingAction = blockingAction,
            messageCount = 24,
            blockDurationMs = 4_321L,
        )

        scenario.run()

        assertEquals(24, poster.callbacks.size)
        assertEquals(listOf(4_321L), blockingAction.blockedDurations)
        val firstCallbackClass: Class<out Runnable> = poster.callbacks.first().javaClass
        poster.callbacks.forEach { callback: Runnable ->
            assertSame(firstCallbackClass, callback.javaClass)
        }
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val scenario: MessageStormScenario = MessageStormScenario(
            poster = RecordingMessageStormPoster(),
            blockingAction = RecordingBlockingAction(),
        )

        assertEquals("message_storm", scenario.id)
        assertEquals("消息风暴", scenario.title)
        assertEquals("MESSAGE_STORM", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("attribution.primary = MESSAGE_STORM"))
        assertTrue(scenario.expectedJsonSignals.contains("pendingQueue.messages 中同类 MessageStormHandler 或 StormRunnable 数量 >= 20"))
        assertTrue(scenario.expectedJsonSignals.contains("attribution.evidence 包含 pending repeated target count"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 为空或不是主因"))
    }

    private class RecordingMessageStormPoster : MessageStormPoster {
        val callbacks: MutableList<Runnable> = mutableListOf()

        override fun post(callback: Runnable): Unit {
            callbacks.add(callback)
        }
    }

    private class RecordingBlockingAction : BlockingAction {
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun block(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }
    }
}
