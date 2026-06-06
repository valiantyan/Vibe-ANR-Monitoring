package com.valiantyan.anrmonitor.domain.analyzer

import com.valiantyan.anrmonitor.domain.model.AnrAttributionCode
import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.Confidence
import com.valiantyan.anrmonitor.domain.model.MessageRecord
import com.valiantyan.anrmonitor.domain.model.MessageRecordKind
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class AttributionAnalyzerTest {
    @Test
    fun analyzeReturnsSpLoadWaitWhenStackContainsAwaitLoadedLocked(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 6_000L,
                    cpuMs = 20L,
                ),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("android.app.SharedPreferencesImpl.awaitLoadedLocked(SharedPreferencesImpl.java:300)"),
            ),
        )

        assertEquals(AnrAttributionCode.SP_LOAD_WAIT, result.primaryCode)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun analyzeReturnsBarrierWhenQueueHeadIsBarrierAndSyncMessageBlocked(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 6_000L,
                    cpuMs = 10L,
                ),
                history = emptyList(),
                pending = listOf(
                    pending(
                        index = 0,
                        isBarrierLike = true,
                        blockedMs = 12_000L,
                        targetClass = null,
                    ),
                    pending(
                        index = 1,
                        isBarrierLike = false,
                        blockedMs = 11_000L,
                        targetClass = "android.os.Handler",
                    ),
                ),
                frames = listOf("android.os.MessageQueue.nativePollOnce(Native Method)"),
            ),
        )

        assertEquals(AnrAttributionCode.SYNC_BARRIER_STUCK, result.primaryCode)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun analyzeReturnsCurrentSlowWhenCurrentWallAndCpuAreHigh(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 6_000L,
                    cpuMs = 5_000L,
                ),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("com.example.Feature.render(Feature.kt:20)"),
            ),
        )

        assertEquals(AnrAttributionCode.CURRENT_MESSAGE_SLOW, result.primaryCode)
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    @Test
    fun analyzeReturnsHistorySlowWhenPreviousMessageIsSlowAndCurrentIsShort(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 3L,
                    wallMs = 20L,
                    cpuMs = 10L,
                ),
                history = listOf(
                    message(
                        seq = 2L,
                        wallMs = 7_000L,
                        cpuMs = 6_000L,
                    ),
                ),
                pending = emptyList(),
                frames = emptyList(),
            ),
        )

        assertEquals(AnrAttributionCode.HISTORY_MESSAGE_SLOW, result.primaryCode)
    }

    @Test
    fun analyzeReturnsMessageStormWhenPendingHasRepeatedTarget(): Unit {
        val pending = (0 until 30).map { index ->
            pending(
                index = index,
                isBarrierLike = false,
                blockedMs = 1_000L,
                targetClass = "com.example.RefreshHandler",
            )
        }

        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 20L,
                    cpuMs = 15L,
                ),
                history = emptyList(),
                pending = pending,
                frames = emptyList(),
            ),
        )

        assertEquals(AnrAttributionCode.MESSAGE_STORM, result.primaryCode)
    }

    private fun snapshot(
        current: MessageRecord?,
        history: List<MessageRecord>,
        pending: List<PendingMessage>,
        frames: List<String>,
    ): AnrSnapshot {
        return AnrSnapshot(
            eventId = "test",
            eventType = AnrEventType.SUSPECT_ANR,
            appId = "demo",
            environment = "test",
            timeUptimeMs = 10_000L,
            currentMessage = current,
            historyMessages = history,
            pendingQueue = PendingQueueSnapshot(
                available = true,
                truncated = false,
                maxDepth = 200,
                messages = pending,
                failureReason = null,
            ),
            mainThreadStack = StackTraceSnapshot(
                stackId = "main",
                threadName = "main",
                frames = frames,
            ),
        )
    }

    private fun message(
        seq: Long,
        wallMs: Long,
        cpuMs: Long,
    ): MessageRecord {
        return MessageRecord(
            seq = seq,
            kind = MessageRecordKind.HISTORY,
            messageType = "looper_dispatch",
            what = null,
            targetClass = "android.os.Handler",
            callbackClass = null,
            isCriticalComponent = false,
            startUptimeMs = 0L,
            endUptimeMs = wallMs,
            wallMs = wallMs,
            cpuMs = cpuMs,
        )
    }

    private fun pending(
        index: Int,
        isBarrierLike: Boolean,
        blockedMs: Long,
        targetClass: String?,
    ): PendingMessage {
        return PendingMessage(
            index = index,
            whenUptimeMs = 0L,
            delayMs = -blockedMs,
            blockedMs = blockedMs,
            what = null,
            arg1 = 41,
            arg2 = 0,
            targetClass = targetClass,
            callbackClass = null,
            objClass = null,
            isAsynchronous = false,
            isBarrierLike = isBarrierLike,
            isCriticalComponent = false,
        )
    }
}
