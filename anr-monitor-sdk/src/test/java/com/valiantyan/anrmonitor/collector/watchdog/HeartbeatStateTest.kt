package com.valiantyan.anrmonitor.collector.watchdog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HeartbeatState] 的纯状态机测试，保证 Watchdog 判定只依赖心跳是否被主线程消费。
 */
class HeartbeatStateTest {
    /**
     * 已处理的心跳不应再触发超时。
     */
    @Test
    fun isTimedOutReturnsFalseWhenHeartbeatHandledBeforeThreshold(): Unit {
        val state = HeartbeatState(
            timeoutMs = 5_000L,
        )
        state.markPosted(
            seq = 1L,
            postedUptimeMs = 1_000L,
        )
        state.markHandled(seq = 1L)
        assertFalse(state.isTimedOut(nowUptimeMs = 7_000L))
    }

    /**
     * 未处理心跳超过阈值时应触发疑似 ANR。
     */
    @Test
    fun isTimedOutReturnsTrueWhenPostedHeartbeatIsTooOld(): Unit {
        val state = HeartbeatState(
            timeoutMs = 5_000L,
        )
        state.markPosted(
            seq = 7L,
            postedUptimeMs = 1_000L,
        )
        assertTrue(state.isTimedOut(nowUptimeMs = 6_500L))
    }

    /**
     * 未处理心跳在主线程执行后应从 pending 状态移除。
     */
    @Test
    fun hasPendingReturnsTrueUntilHeartbeatIsHandled(): Unit {
        val state = HeartbeatState(
            timeoutMs = 5_000L,
        )
        state.markPosted(
            seq = 9L,
            postedUptimeMs = 1_000L,
        )
        assertTrue(state.hasPending())
        state.markHandled(seq = 9L)
        assertFalse(state.hasPending())
    }
}
