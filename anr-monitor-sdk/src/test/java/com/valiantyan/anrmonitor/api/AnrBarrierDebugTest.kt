package com.valiantyan.anrmonitor.api

import com.valiantyan.anrmonitor.collector.barrier.BarrierTokenTracker
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 [AnrBarrierDebug] 只记录 Barrier token 证据，不主动改变系统消息队列。
 */
class AnrBarrierDebugTest {
    // 当前用例使用的 token，结束时清理避免污染全局追踪器。
    private val token: Int = 8_601

    /**
     * 每个用例结束后移除 token，避免全局调试证据跨测试泄漏。
     */
    @After
    fun tearDown(): Unit {
        AnrBarrierDebug.recordRemoveSyncBarrier(
            token = token,
            uptimeMs = 9_000L,
        )
    }

    /**
     * Debug API 应把 postSyncBarrier token 写入全局追踪器，供报告与 Pending 队头对齐。
     */
    @Test
    fun recordPostSyncBarrierStoresTokenEvidence(): Unit {
        AnrBarrierDebug.recordPostSyncBarrier(
            token = token,
            uptimeMs = 1_000L,
            stackFrames = listOf("demo.SyncBarrierLeakScenario.postSyncBarrier"),
        )

        val stuckTokens = BarrierTokenTracker.global.findStuckTokens(
            nowUptimeMs = 7_000L,
            thresholdMs = 5_000L,
        )

        assertEquals(token, stuckTokens.first().token)
        assertEquals(6_000L, stuckTokens.first().aliveMs)
        assertTrue(stuckTokens.first().postStack.first().contains("SyncBarrierLeakScenario"))
    }

    /**
     * Debug API 记录 remove 后，token 不应继续作为卡住证据输出。
     */
    @Test
    fun recordRemoveSyncBarrierClearsTokenEvidence(): Unit {
        AnrBarrierDebug.recordPostSyncBarrier(
            token = token,
            uptimeMs = 1_000L,
            stackFrames = listOf("demo.SyncBarrierLeakScenario.postSyncBarrier"),
        )
        AnrBarrierDebug.recordRemoveSyncBarrier(
            token = token,
            uptimeMs = 1_200L,
        )

        val stuckTokens = BarrierTokenTracker.global.findStuckTokens(
            nowUptimeMs = 7_000L,
            thresholdMs = 5_000L,
        )

        assertTrue(stuckTokens.none { record -> record.token == token })
    }
}
