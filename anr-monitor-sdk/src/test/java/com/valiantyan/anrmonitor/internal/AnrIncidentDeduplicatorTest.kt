package com.valiantyan.anrmonitor.internal

import com.valiantyan.anrmonitor.domain.model.AnrEventType
import com.valiantyan.anrmonitor.domain.model.AnrInfoSnapshot
import com.valiantyan.anrmonitor.domain.model.AnrSnapshot
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import com.valiantyan.anrmonitor.domain.model.StackTraceSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证活跃 ANR 去重，避免同一个卡死窗口持续污染本地报告和上报数据。
 */
class AnrIncidentDeduplicatorTest {
    /**
     * 同一个 Barrier token 在恢复前只能写出第一份报告。
     */
    @Test
    fun shouldSuppressSameBarrierIncidentUntilRecovery(): Unit {
        val deduplicator = AnrIncidentDeduplicator()

        assertTrue(deduplicator.shouldReport(snapshot = snapshotWithBarrier(token = 23)))
        assertFalse(deduplicator.shouldReport(snapshot = snapshotWithBarrier(token = 23)))

        deduplicator.markRecovered()

        assertTrue(deduplicator.shouldReport(snapshot = snapshotWithBarrier(token = 23)))
    }

    /**
     * 不同 Barrier token 代表新的卡死窗口，应允许单独生成报告。
     */
    @Test
    fun shouldAllowDifferentBarrierIncidentBeforeRecovery(): Unit {
        val deduplicator = AnrIncidentDeduplicator()

        assertTrue(deduplicator.shouldReport(snapshot = snapshotWithBarrier(token = 23)))
        assertTrue(deduplicator.shouldReport(snapshot = snapshotWithBarrier(token = 24)))
    }

    private fun snapshotWithBarrier(token: Int): AnrSnapshot {
        return AnrSnapshot(
            eventId = "event-$token",
            eventType = AnrEventType.SUSPECT_ANR,
            appId = "demo",
            environment = "debug",
            timeUptimeMs = 7_000L,
            anrInfo = AnrInfoSnapshot.unconfirmed(),
            currentMessage = null,
            historyMessages = emptyList(),
            pendingQueue = PendingQueueSnapshot(
                available = true,
                truncated = false,
                maxDepth = 20,
                messages = listOf(
                    PendingMessage(
                        index = 0,
                        whenUptimeMs = 1_000L,
                        delayMs = -6_000L,
                        blockedMs = 6_000L,
                        what = null,
                        arg1 = token,
                        arg2 = 0,
                        targetClass = null,
                        callbackClass = null,
                        objClass = null,
                        isAsynchronous = null,
                        isBarrierLike = true,
                        isCriticalComponent = false,
                    ),
                ),
                failureReason = null,
            ),
            mainThreadStack = StackTraceSnapshot(
                stackId = "main-native-poll",
                threadName = "main",
                frames = listOf("android.os.MessageQueue.nativePollOnce(Native Method)"),
            ),
        )
    }
}
