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
import com.valiantyan.anrmonitor.domain.model.ThreadCpuRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证基础归因规则，确保高确定性证据优先且补充证据不会覆盖主因。
 */
class AttributionAnalyzerTest {
    /**
     * 主线程栈包含 SP 首次加载等待时，优先输出 [AnrAttributionCode.SP_LOAD_WAIT]。
     */
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

    /**
     * Pending 队头同步屏障阻塞同步消息时，归因为 Barrier 假死。
     */
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

    /**
     * 当前消息 wall/cpu 都高时，归因为当前消息执行慢。
     */
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

    /**
     * 当前消息 wall 高但 CPU 低时仍应归因当前消息慢，只降低置信度。
     */
    @Test
    fun analyzeReturnsCurrentSlowWhenCurrentWallIsHighAndCpuIsLow(): Unit {
        val result = AttributionAnalyzer(
            thresholds = AttributionThresholds(
                suspectAnrMs = 3_000L,
            ),
        ).analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 3_326L,
                    cpuMs = 0L,
                ),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("java.lang.Thread.sleep(Native Method)"),
            ),
        )

        assertEquals(AnrAttributionCode.CURRENT_MESSAGE_SLOW, result.primaryCode)
        assertEquals(Confidence.LOW, result.confidence)
    }

    /**
     * 线程 CPU TopN 是补充证据，应进入归因 evidence 但不覆盖主因编码。
     */
    @Test
    fun analyzeIncludesThreadCpuEvidenceWithoutChangingPrimaryCode(): Unit {
        val result = AttributionAnalyzer().analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 6_000L,
                    cpuMs = 20L,
                ),
                history = emptyList(),
                pending = emptyList(),
                frames = listOf("java.lang.Thread.sleep(Native Method)"),
                threadCpuRecords = listOf(
                    ThreadCpuRecord(
                        tid = 2,
                        threadName = "io-worker",
                        totalCpuMs = 100L,
                    ),
                ),
            ),
        )

        assertEquals(AnrAttributionCode.CURRENT_MESSAGE_SLOW, result.primaryCode)
        assertTrue(result.evidenceItems.contains("top thread io-worker cpu=100ms"))
    }

    /**
     * 历史消息曾经慢且当前消息很短时，使用历史消息作为主因。
     */
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

    /**
     * Pending 中大量重复 target 时，归因为消息风暴。
     */
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

    /**
     * 消息风暴证据比当前慢消息更能解释队列堆积，应优先输出。
     */
    @Test
    fun analyzeReturnsMessageStormBeforeCurrentSlowWhenPendingHasRepeatedTarget(): Unit {
        val pending = (0 until 30).map { index ->
            pending(
                index = index,
                isBarrierLike = false,
                blockedMs = 3_500L,
                targetClass = "android.os.Handler",
            )
        }

        val result = AttributionAnalyzer(
            thresholds = AttributionThresholds(
                suspectAnrMs = 3_000L,
            ),
        ).analyze(
            snapshot = snapshot(
                current = message(
                    seq = 1L,
                    wallMs = 3_300L,
                    cpuMs = 0L,
                ),
                history = emptyList(),
                pending = pending,
                frames = listOf("java.lang.Thread.sleep(Native Method)"),
            ),
        )

        assertEquals(AnrAttributionCode.MESSAGE_STORM, result.primaryCode)
    }

    private fun snapshot(
        current: MessageRecord?,
        history: List<MessageRecord>,
        pending: List<PendingMessage>,
        frames: List<String>,
        threadCpuRecords: List<ThreadCpuRecord> = emptyList(),
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
            threadCpuRecords = threadCpuRecords,
        )
    }

    // 构造主线程消息记录，保持测试只关注归因规则。
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

    // 构造 Pending 消息记录，保持 Barrier 和消息风暴测试可读。
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
