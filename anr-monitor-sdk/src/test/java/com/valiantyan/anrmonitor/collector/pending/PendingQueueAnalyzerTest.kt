package com.valiantyan.anrmonitor.collector.pending

import com.valiantyan.anrmonitor.domain.model.PendingMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingQueueAnalyzerTest {
    @Test
    fun analyzeDetectsBarrierAtQueueHead(): Unit {
        val summary: PendingQueueSummary = PendingQueueAnalyzer.analyze(
            messages = listOf(
                createPendingMessage(
                    index = 0,
                    isBarrierLike = true,
                    targetClass = null,
                    blockedMs = 12_000L,
                    arg1 = 41,
                ),
                createPendingMessage(
                    index = 1,
                    isBarrierLike = false,
                    targetClass = "android.os.Handler",
                    blockedMs = 11_000L,
                    arg1 = 0,
                ),
            ),
        )

        assertTrue(summary.hasBarrierHead)
        assertEquals(41, summary.barrierToken)
        assertEquals(11_000L, summary.firstSynchronousBlockedMs)
    }

    // 构造最小 Pending 消息，聚焦队头屏障与同步消息阻塞时长规则。
    private fun createPendingMessage(
        index: Int,
        isBarrierLike: Boolean,
        targetClass: String?,
        blockedMs: Long,
        arg1: Int,
    ): PendingMessage {
        return PendingMessage(
            index = index,
            whenUptimeMs = 100L,
            delayMs = -blockedMs,
            blockedMs = blockedMs,
            what = null,
            arg1 = arg1,
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
