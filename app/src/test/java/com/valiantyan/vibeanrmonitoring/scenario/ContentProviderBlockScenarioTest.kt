package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 ContentProvider 阻塞场景的触发入口和 Provider 内部阻塞动作。
 */
class ContentProviderBlockScenarioTest {
    @Test
    fun runQueriesProviderWithConfiguredAuthorityAndPath(): Unit {
        val caller: RecordingContentProviderCaller = RecordingContentProviderCaller()
        val scenario: ContentProviderBlockScenario = ContentProviderBlockScenario(
            contentProviderCaller = caller,
        )
        scenario.run()
        assertEquals(
            listOf(
                RecordingContentProviderCall(
                    authority = ContentProviderBlockScenario.AUTHORITY,
                    path = ContentProviderBlockScenario.BLOCKING_PATH,
                ),
            ),
            caller.calls,
        )
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val caller: RecordingContentProviderCaller = RecordingContentProviderCaller()
        val scenario: ContentProviderBlockScenario = ContentProviderBlockScenario(
            contentProviderCaller = caller,
        )
        assertEquals("content_provider_block", scenario.id)
        assertEquals("ContentProvider 阻塞", scenario.title)
        assertEquals("CURRENT_MESSAGE_SLOW + PROVIDER call stack evidence", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 BlockingContentProvider.query"))
        assertTrue(scenario.expectedJsonSignals.contains("mainThread.stackFrames 包含 ContentProviderBlocker.block"))
        assertTrue(
            scenario.expectedJsonSignals.contains(
                "mainThread.stackFrames 包含 ContentResolver.query 或 ContentProvider.Transport.query",
            ),
        )
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.stuckTokens 不是主因"))
    }

    @Test
    fun blockerBlocksForConfiguredDuration(): Unit {
        val blockingAction: RecordingBlockingAction = RecordingBlockingAction()
        val blocker: ContentProviderBlocker = ContentProviderBlocker(
            blockingAction = blockingAction,
            durationMs = 12_345L,
        )
        blocker.block()
        assertEquals(listOf(12_345L), blockingAction.blockedDurations)
    }

    private class RecordingContentProviderCaller : ContentProviderCaller {
        val calls: MutableList<RecordingContentProviderCall> = mutableListOf()

        override fun query(authority: String, path: String): Unit {
            calls.add(
                RecordingContentProviderCall(
                    authority = authority,
                    path = path,
                ),
            )
        }
    }

    private data class RecordingContentProviderCall(
        val authority: String,
        val path: String,
    )

    private class RecordingBlockingAction : BlockingAction {
        val blockedDurations: MutableList<Long> = mutableListOf()

        override fun block(durationMs: Long): Unit {
            blockedDurations.add(durationMs)
        }
    }
}
