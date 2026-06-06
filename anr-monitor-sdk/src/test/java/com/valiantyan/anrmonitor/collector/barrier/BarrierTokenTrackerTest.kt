package com.valiantyan.anrmonitor.collector.barrier

import com.valiantyan.anrmonitor.collector.nativepoll.NativePollOnceMonitor
import com.valiantyan.anrmonitor.domain.model.PendingMessage
import com.valiantyan.anrmonitor.domain.model.PendingQueueSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 Barrier token 生命周期和 Pending 队列对齐证据，避免只看到 [nativePollOnce] 栈就误判为空闲。
 */
class BarrierTokenTrackerTest {
    /**
     * 未移除的 Barrier token 超过阈值时，应输出 token、存活时间和插入栈。
     */
    @Test
    fun trackReturnsStuckTokenWhenNotRemoved(): Unit {
        val tracker = BarrierTokenTracker()

        tracker.onPostBarrier(
            token = 7,
            uptimeMs = 1_000L,
            stack = listOf("android.os.MessageQueue.postSyncBarrier(MessageQueue.java:250)"),
        )

        val stuck = tracker.findStuckTokens(
            nowUptimeMs = 7_000L,
            thresholdMs = 5_000L,
        )

        assertEquals(7, stuck.first().token)
        assertTrue(stuck.first().aliveMs >= 6_000L)
        assertTrue(stuck.first().postStack.first().contains("postSyncBarrier"))
    }

    /**
     * token 被移除后不应继续作为卡住证据，防止正常 UI 刷新屏障被误报。
     */
    @Test
    fun removeBarrierExcludesTokenFromStuckEvidence(): Unit {
        val tracker = BarrierTokenTracker()

        tracker.onPostBarrier(
            token = 9,
            uptimeMs = 1_000L,
            stack = listOf("postSyncBarrier"),
        )
        tracker.onRemoveBarrier(
            token = 9,
            uptimeMs = 1_200L,
        )

        val stuck = tracker.findStuckTokens(
            nowUptimeMs = 8_000L,
            thresholdMs = 5_000L,
        )

        assertTrue(stuck.isEmpty())
        assertEquals(1_200L, tracker.recentRecords(maxRecords = 10).first().removeUptimeMs)
    }

    /**
     * 增强证据应把 token、队头 Barrier 和反复 [nativePollOnce(-1)] 放在同一快照里。
     */
    @Test
    fun collectAlignsBarrierTokenWithPendingQueueAndNativePollOnce(): Unit {
        val tracker = BarrierTokenTracker()
        val nativePollOnceMonitor = NativePollOnceMonitor()
        val collector = BarrierEvidenceCollector(
            tokenTracker = tracker,
            nativePollOnceMonitor = nativePollOnceMonitor,
        )
        tracker.onPostBarrier(
            token = 41,
            uptimeMs = 1_000L,
            stack = listOf("postSyncBarrier token=41"),
        )
        nativePollOnceMonitor.onEnter(
            timeoutMillis = -1,
            uptimeMs = 4_000L,
        )
        nativePollOnceMonitor.onExit(uptimeMs = 4_300L)
        nativePollOnceMonitor.onEnter(
            timeoutMillis = -1,
            uptimeMs = 4_400L,
        )
        nativePollOnceMonitor.onExit(uptimeMs = 4_800L)

        val snapshot = collector.collect(
            enabled = true,
            nowUptimeMs = 7_000L,
            stuckThresholdMs = 5_000L,
            maxRecords = 10,
            pendingQueue = pendingBarrierQueue(token = 41),
        )

        assertTrue(snapshot.available)
        assertTrue(snapshot.alignedWithPendingBarrier)
        assertEquals(41, snapshot.stuckTokens.first().token)
        assertEquals(2, snapshot.repeatedInfinitePollCount)
        assertEquals(-1, snapshot.recentNativePollOnceRecords.first().timeoutMillis)
    }

    /**
     * 高风险增强采集关闭时必须明确降级原因，不能输出误导性的空成功快照。
     */
    @Test
    fun collectReturnsUnavailableWhenBarrierEvidenceDisabled(): Unit {
        val collector = BarrierEvidenceCollector(
            tokenTracker = BarrierTokenTracker(),
            nativePollOnceMonitor = NativePollOnceMonitor(),
        )

        val snapshot = collector.collect(
            enabled = false,
            nowUptimeMs = 7_000L,
            stuckThresholdMs = 5_000L,
            maxRecords = 10,
            pendingQueue = pendingBarrierQueue(token = 41),
        )

        assertFalse(snapshot.available)
        assertEquals("barrier evidence capture disabled", snapshot.failureReason)
    }

    // 构造队头同步屏障，保持测试只关注增强证据对齐。
    private fun pendingBarrierQueue(token: Int): PendingQueueSnapshot {
        return PendingQueueSnapshot(
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
                PendingMessage(
                    index = 1,
                    whenUptimeMs = 1_100L,
                    delayMs = -5_900L,
                    blockedMs = 5_900L,
                    what = null,
                    arg1 = 0,
                    arg2 = 0,
                    targetClass = "android.view.Choreographer\$FrameHandler",
                    callbackClass = null,
                    objClass = null,
                    isAsynchronous = false,
                    isBarrierLike = false,
                    isCriticalComponent = false,
                ),
            ),
            failureReason = null,
        )
    }
}
