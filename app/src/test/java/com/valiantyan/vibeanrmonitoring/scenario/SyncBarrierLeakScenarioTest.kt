package com.valiantyan.vibeanrmonitoring.scenario

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Sync Barrier 泄漏场景会形成 token、队头 Barrier、nativePollOnce 相关的可读证据链。
 */
class SyncBarrierLeakScenarioTest {
    @Test
    fun runRecordsBarrierTokenAndPostsBlockedWork(): Unit {
        val poster: RecordingSyncBarrierPoster = RecordingSyncBarrierPoster(token = 42)
        val recorder: RecordingBarrierDebugRecorder = RecordingBarrierDebugRecorder()
        val blockedMessagePoster: RecordingBarrierBlockedMessagePoster = RecordingBarrierBlockedMessagePoster()
        val componentStarter: RecordingBarrierLeakComponentStarter = RecordingBarrierLeakComponentStarter()
        val failureNotifier: RecordingScenarioFailureNotifier = RecordingScenarioFailureNotifier()
        val scenario: SyncBarrierLeakScenario = SyncBarrierLeakScenario(
            barrierPoster = poster,
            debugRecorder = recorder,
            blockedMessagePoster = blockedMessagePoster,
            componentStarter = componentStarter,
            failureNotifier = failureNotifier,
            blockedMessageCount = 6,
        )

        scenario.run()

        assertEquals(listOf(42), poster.postCalls)
        assertEquals(listOf(42), recorder.tokens)
        assertTrue(recorder.stackFrames.single().any { frame: String ->
            frame.contains("SyncBarrierLeakScenario.run")
        })
        assertEquals(6, blockedMessagePoster.callbacks.size)
        assertEquals(1, componentStarter.startCount)
        assertTrue(failureNotifier.errors.isEmpty())
    }

    @Test
    fun runDoesNotRecordEvidenceWhenPostingBarrierFails(): Unit {
        val poster: RecordingSyncBarrierPoster = RecordingSyncBarrierPoster(token = null)
        val recorder: RecordingBarrierDebugRecorder = RecordingBarrierDebugRecorder()
        val blockedMessagePoster: RecordingBarrierBlockedMessagePoster = RecordingBarrierBlockedMessagePoster()
        val componentStarter: RecordingBarrierLeakComponentStarter = RecordingBarrierLeakComponentStarter()
        val failureNotifier: RecordingScenarioFailureNotifier = RecordingScenarioFailureNotifier()
        val scenario: SyncBarrierLeakScenario = SyncBarrierLeakScenario(
            barrierPoster = poster,
            debugRecorder = recorder,
            blockedMessagePoster = blockedMessagePoster,
            componentStarter = componentStarter,
            failureNotifier = failureNotifier,
        )

        scenario.run()

        assertTrue(recorder.tokens.isEmpty())
        assertTrue(blockedMessagePoster.callbacks.isEmpty())
        assertEquals(0, componentStarter.startCount)
        assertEquals(listOf("postSyncBarrier failed"), failureNotifier.errors)
    }

    @Test
    fun descriptionExplainsExpectedJsonEvidence(): Unit {
        val scenario: SyncBarrierLeakScenario = SyncBarrierLeakScenario(
            barrierPoster = RecordingSyncBarrierPoster(token = 7),
            debugRecorder = RecordingBarrierDebugRecorder(),
            blockedMessagePoster = RecordingBarrierBlockedMessagePoster(),
            componentStarter = RecordingBarrierLeakComponentStarter(),
            failureNotifier = RecordingScenarioFailureNotifier(),
        )

        assertEquals("sync_barrier_native_poll_once", scenario.id)
        assertEquals("Sync Barrier 泄漏 / nativePollOnce", scenario.title)
        assertEquals("SYNC_BARRIER_STUCK", scenario.expectedAttribution)
        assertTrue(scenario.expectedJsonSignals.contains("attribution.primary = SYNC_BARRIER_STUCK"))
        assertTrue(scenario.expectedJsonSignals.contains("pendingQueue.messages[0].isBarrierLike = true"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.alignedWithPendingBarrier = true"))
        assertTrue(scenario.expectedJsonSignals.contains("barrierEvidence.nativePollOnceRecords 包含 STACK_INFERENCE 或 HOOK"))
    }

    private class RecordingSyncBarrierPoster(
        private val token: Int?,
    ) : SyncBarrierPoster {
        val postCalls: MutableList<Int> = mutableListOf()

        override fun post(): Int? {
            token?.let { value: Int -> postCalls.add(value) }
            return token
        }
    }

    private class RecordingBarrierDebugRecorder : BarrierDebugRecorder {
        val tokens: MutableList<Int> = mutableListOf()
        val stackFrames: MutableList<List<String>> = mutableListOf()

        override fun recordPostSyncBarrier(
            token: Int,
            stackFrames: List<String>,
        ): Unit {
            tokens.add(token)
            this.stackFrames.add(stackFrames)
        }
    }

    private class RecordingBarrierBlockedMessagePoster : BarrierBlockedMessagePoster {
        val callbacks: MutableList<Runnable> = mutableListOf()

        override fun post(callback: Runnable): Unit {
            callbacks.add(callback)
        }
    }

    private class RecordingBarrierLeakComponentStarter : BarrierLeakComponentStarter {
        var startCount: Int = 0

        override fun start(): Unit {
            startCount += 1
        }
    }

    private class RecordingScenarioFailureNotifier : ScenarioFailureNotifier {
        val errors: MutableList<String> = mutableListOf()

        override fun notifyFailure(
            message: String,
            error: Throwable?,
        ): Unit {
            errors.add(message)
        }
    }
}
